/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.codec.http.websocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoRcvQueueView;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.PartitionKey;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.codec.http.*;

/**
 * Bridge duplexer that upgrades one routed client HTTP exchange into a websocket route.
 * <p>
 * Ordinary HTTP traffic passes through unchanged. Once an outbound request is
 * recognized as a websocket upgrade request, the request/response pair is
 * delegated to {@link WebSocketClientHandshakeDuplexer}. When the handshake
 * completes, the route is switched to the configured websocket branch.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-24
 */
public class WebSocketClientUpgradeRouteDuplexer implements ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject> {
    private static final String                    ROUTE_STATE_STORE_KEY = WebSocketClientUpgradeRouteDuplexer.class.getName() + ".routeStateStore";
    private static final String                    SEND_STAGE_QUEUE_KEY  = WebSocketClientUpgradeRouteDuplexer.class.getName() + ".send";
    private final WebSocketClientHandshakeDuplexer delegate;
    private final ProtoRoutingControl              control;
    private final String                           targetRoute;

    private static final class RouteState {
        private final HttpMessageParts requestParts         = new HttpMessageParts();
        private final List<HttpObject> bufferedRequestParts = new ArrayList<>();
        private boolean                handshakePending;
        private Boolean                currentRequestHandshake;
        /** Buffered request parts that must be retried before consuming new input. */
        private boolean                pendingFlush;
    }

    private static final class RouteStateStore {
        private final ConcurrentHashMap<PartitionKey, RouteState> states = new ConcurrentHashMap<>();
    }

    /**
     * Create a client-side route bridge for one websocket upgrade transaction.
     * @param control routing controller used to switch branches
     * @param version websocket version to negotiate
     * @param targetRoute route name to switch to after a successful handshake
     */
    public WebSocketClientUpgradeRouteDuplexer(ProtoRoutingControl control, WebSocketVersion version, String targetRoute) {
        this.delegate = new WebSocketClientHandshakeDuplexer(version);
        this.control = java.util.Objects.requireNonNull(control, "control is null");
        this.targetRoute = targetRoute;
    }

    /**
     * Initialize the delegated handshake duplexer.
     */
    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.delegate.onInit(name, rcvSize, sndSize, context);
        state(context);
    }

    /**
     * Forward channel activation to the delegated handshake duplexer.
     */
    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.delegate.onActive(context);
    }

    /**
     * Forward events to the delegated handshake duplexer.
     */
    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        return this.delegate.onEvent(context, event, isRcv);
    }

    /**
     * Intercept the upgrade exchange and switch the route once websocket is ready.
     */
    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown, ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        if (isRcv) {
            return this.handleReceive(context, rcvUp, rcvDown);
        } else {
            return this.handleSend(context, sndUp, sndDown);
        }
    }

    /**
     * Reset buffered route state when the upgrade flow fails.
     */
    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        RouteState state = state(context);
        state.handshakePending = false;
        this.resetRequestRoutingState(state, true);
        return this.delegate.onError(context, isRcv, e, eh);
    }

    /**
     * Clear any buffered route state when the channel closes.
     */
    @Override
    public void onClose(ProtoContext context) {
        RouteState state = state(context);
        state.handshakePending = false;
        this.resetRequestRoutingState(state, true);
        removeState(context);
        this.delegate.onClose(context);
    }

    private ProtoStatus handleReceive(ProtoContext context, ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown) throws Throwable {
        while (rcvUp.hasMore()) {
            HttpObject msg = rcvUp.peekMessage();
            if (msg == null) {
                rcvUp.takeMessage();
                continue;
            }

            RouteState state = state(context, msg);

            if ((state.handshakePending || this.delegate.isHandshakePending(context, msg)) && isHandshakeResponsePart(msg)) {
                state.handshakePending = true;
                this.delegate.onReceiveData(context, rcvUp, rcvDown);
                if (this.delegate.isHandshakeReady(context)) {
                    state.handshakePending = false;
                    this.control.switchRouteNextTick(this.targetRoute);
                }
                continue;
            }

            // Do not take from rcvUp until rcvDown can accept one message.
            if (!rcvDown.hasSlot()) {
                return ProtoStatus.Stop;
            }

            HttpObject forward = rcvUp.takeMessage();
            if (forward != null) {
                rcvDown.offerMessage(forward);
            }
        }

        return ProtoStatus.Next;
    }

    private ProtoStatus handleSend(ProtoContext context, ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        while (sndUp.hasMore()) {
            HttpObject msg = sndUp.peekMessage();
            if (msg == null) {
                sndUp.takeMessage();
                continue;
            }

            RouteState state = state(context, msg);

            // Retry a deferred buffered flush before consuming new request parts.
            if (state.pendingFlush) {
                if (sndDown.slotSize() < state.bufferedRequestParts.size()) {
                    return ProtoStatus.Stop;
                }

                sndDown.offerMessage(state.bufferedRequestParts);
                state.pendingFlush = false;
                this.resetRequestRoutingState(state, false);
            }

            if (state.currentRequestHandshake != null) {
                if (state.currentRequestHandshake) {
                    ProtoStatus status = this.forwardSingleToHandshake(context, sndUp, sndDown);
                    if (status != ProtoStatus.Next) {
                        return status;
                    }
                } else {
                    if (!sndDown.hasSlot()) {
                        return ProtoStatus.Stop;
                    }

                    HttpObject forward = sndUp.takeMessage();
                    if (forward != null) {
                        sndDown.offerMessage(forward);
                    }
                }
                if (isRequestComplete(msg)) {
                    this.resetRequestRoutingState(state, false);
                }
                continue;
            }

            if (!state.requestParts.isActive() && !(msg instanceof HttpRequest)) {
                if (!sndDown.hasSlot()) {
                    return ProtoStatus.Stop;
                }

                HttpObject forward = sndUp.takeMessage();
                if (forward != null) {
                    sndDown.offerMessage(forward);
                }
                continue;
            }

            msg = sndUp.takeMessage();
            state.bufferedRequestParts.add(msg);
            state.requestParts.appendRequest(msg);

            if (!isHeaderSectionClosed(msg)) {
                if (isRequestComplete(msg)) {
                    // Keep buffered parts in memory and retry the flush on the next tick.
                    if (sndDown.slotSize() < state.bufferedRequestParts.size()) {
                        state.pendingFlush = true;
                        return ProtoStatus.Stop;
                    }

                    sndDown.offerMessage(state.bufferedRequestParts);
                    this.resetRequestRoutingState(state, false);
                }
                continue;
            }

            boolean handshakeRequest = isHandshakeRequest(state.requestParts);
            boolean requestComplete = isRequestComplete(msg);

            if (handshakeRequest) {
                RouteState handshakeState = bindHandshakeState(context, state);
                handshakeState.handshakePending = true;
                for (HttpObject buffered : state.bufferedRequestParts) {
                    ProtoStatus status = this.delegate.onSendMessage(context, buffered, sndDown);
                    if (status != ProtoStatus.Next) {
                        return status;
                    }
                }
            } else {
                // Keep buffered parts in memory and retry the flush on the next tick.
                if (sndDown.slotSize() < state.bufferedRequestParts.size()) {
                    state.pendingFlush = true;
                    return ProtoStatus.Stop;
                }
                sndDown.offerMessage(state.bufferedRequestParts);
            }

            if (requestComplete) {
                this.resetRequestRoutingState(state, false);
            } else {
                state.currentRequestHandshake = handshakeRequest;
                state.bufferedRequestParts.clear();
                state.requestParts.reset();
            }
        }

        return ProtoStatus.Next;
    }

    private void resetRequestRoutingState(RouteState state, boolean releaseBuffered) {
        if (releaseBuffered) {
            for (HttpObject msg : state.bufferedRequestParts) {
                if (msg != null) {
                    msg.release();
                }
            }
        }

        state.bufferedRequestParts.clear();
        state.pendingFlush = false;
        state.requestParts.reset();
        state.currentRequestHandshake = null;
    }

    private RouteState state(ProtoContext context) {
        RouteState state = context.context(RouteState.class);
        return state != null ? state : state(context, null);
    }

    private RouteState state(ProtoContext context, long streamId) {
        RouteStateStore store = stateStore(context);
        PartitionKey key = PartitionKey.newKey(streamId);
        RouteState state = store.states.get(key);

        if (state == null) {
            RouteState newState = new RouteState();
            RouteState oldState = store.states.putIfAbsent(key, newState);
            state = oldState != null ? oldState : newState;
        }

        context.context(RouteState.class, state);
        return state;
    }

    private RouteState state(ProtoContext context, HttpObject msg) {
        if (msg == null || msg.streamId() <= 0) {
            RouteState localState = context.context(RouteState.class);
            if (localState != null) {
                return localState;
            }
        }

        RouteStateStore store = stateStore(context);
        PartitionKey key = partitionKey(context, msg);
        RouteState state = store.states.get(key);

        if (state == null) {
            RouteState newState = new RouteState();
            RouteState oldState = store.states.putIfAbsent(key, newState);
            state = oldState != null ? oldState : newState;
        }

        context.context(RouteState.class, state);
        return state;
    }

    private void removeState(ProtoContext context) {
        RouteStateStore store = stateStore(context);
        store.states.remove(partitionKey(context, null));
    }

    private RouteStateStore stateStore(ProtoContext context) {
        RouteStateStore shared = context.rootContext(RouteStateStore.class);
        if (shared != null) {
            return shared;
        }

        RouteStateStore store = new RouteStateStore();
        RouteStateStore rootStore = context.rootContext(RouteStateStore.class, store);
        if (rootStore != null) {
            return rootStore;
        }

        SoChannel<?> channel = context.getChannel();
        Object attr = channel != null ? channel.getAttribute(ROUTE_STATE_STORE_KEY) : null;
        if (attr instanceof RouteStateStore) {
            return (RouteStateStore) attr;
        }

        if (channel != null) {
            channel.setAttribute(ROUTE_STATE_STORE_KEY, store);
        }
        return store;
    }

    private static PartitionKey partitionKey(ProtoContext context, HttpObject msg) {
        if (msg != null && msg.streamId() > 0 && isHttp2StreamMessage(context, msg)) {
            return PartitionKey.newKey(msg.streamId());
        }

        PartitionKey key = PartitionKey.findKey(context);
        if (key != null && !PartitionKey.defaultKey().equals(key)) {
            return key;
        }

        return key != null ? key : PartitionKey.defaultKey();
    }

    private static boolean isHttp2StreamMessage(ProtoContext context, HttpObject msg) {
        if (InternalUtils.resolveHttpScope(context) == HttpScope.STREAM) {
            return true;
        }
        if (msg instanceof HttpRequest) {
            return ((HttpRequest) msg).protocolVersion().majorVersion() == 2;
        }
        if (msg instanceof HttpResponse) {
            return ((HttpResponse) msg).protocolVersion().majorVersion() == 2;
        }
        HttpVersion version = context.context(HttpVersion.class);
        return version != null && version.majorVersion() == 2;
    }

    private RouteState bindHandshakeState(ProtoContext context, RouteState state) {
        long streamId = state.requestParts.streamId();
        if (streamId <= 0) {
            return state;
        } else {
            return state(context, streamId);
        }
    }

    private ProtoStatus forwardSingleToHandshake(ProtoContext context, ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        ProtoRcvQueueView<HttpObject> stagedView = this.stageNextMessage(sndUp);
        return this.delegate.onSendData(context, stagedView, sndDown);
    }

    private ProtoRcvQueueView<HttpObject> stageNextMessage(ProtoRcvQueue<HttpObject> src) {
        src.drainToQueue(SEND_STAGE_QUEUE_KEY, 1);
        return src.queueView(SEND_STAGE_QUEUE_KEY);
    }

    private static boolean isHandshakeRequest(HttpMessageParts request) {
        if (InternalUtils.isStandardHttp2WebSocketRequest(request)) {
            return true;
        }
        String upgrade = request.header(HttpHeaderNames.UPGRADE);
        String connection = request.header(HttpHeaderNames.CONNECTION);
        return HttpHeaderValues.WEBSOCKET.equalsIgnoreCase(upgrade) && connection != null && connection.toLowerCase().contains(HttpHeaderValues.UPGRADE);
    }

    private static boolean isHeaderSectionClosed(HttpObject msg) {
        return msg instanceof LastHttpHeaders || isAggregateLikeRequest(msg);
    }

    private static boolean isRequestComplete(HttpObject msg) {
        return msg instanceof LastHttpContent || isAggregateLikeRequest(msg);
    }

    private static boolean isAggregateLikeRequest(HttpObject msg) {
        return msg instanceof HttpRequest && msg instanceof HttpContent;
    }

    private static boolean isHandshakeResponsePart(HttpObject msg) {
        return msg instanceof HttpResponse || msg instanceof HttpHeaders || msg instanceof HttpContent;
    }
}