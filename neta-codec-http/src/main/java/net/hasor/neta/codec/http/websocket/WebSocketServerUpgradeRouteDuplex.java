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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoRcvQueueView;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.PartitionKey;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.codec.http.*;
/**
 * Bridge duplexer that upgrades one routed server HTTP exchange into a websocket route.
 * <p>
 * Ordinary HTTP requests keep flowing through the current route. When an inbound
 * request is recognized as a websocket upgrade request, the request is delegated
 * to {@link WebSocketServerHandshakeDuplex}. After a successful handshake the
 * active route switches to the configured websocket branch.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-24
 */
public class WebSocketServerUpgradeRouteDuplex implements ProtoDuplex<HttpObject, HttpObject, HttpObject, HttpObject> {
    private static final String                  ROUTE_STATE_STORE_KEY   = WebSocketServerUpgradeRouteDuplex.class.getName() + ".routeStateStore";
    private static final String                  BUFFER_QUEUE_PREFIX     = WebSocketServerUpgradeRouteDuplex.class.getName() + ".pendingRequest.";
    private static final String                  RECEIVE_STAGE_QUEUE_KEY = WebSocketServerUpgradeRouteDuplex.class.getName() + ".receive";
    private static final String                  SEND_STAGE_QUEUE_KEY    = WebSocketServerUpgradeRouteDuplex.class.getName() + ".send";
    private final WebSocketServerHandshakeDuplex delegate;
    private final ProtoRoutingControl            routingControl;
    private final String                         targetRoute;

    private static final class RouteState {
        private final HttpMessageParts        requestParts = new HttpMessageParts();
        private ProtoRcvQueueView<HttpObject> bufferedRequestViewRef;
        private boolean                       handshakePending;
        private Boolean                       currentRequestHandshake;
        /** Buffered request view that must be flushed before consuming new input. */
        private boolean                       pendingFlush;
    }

    private static final class RouteStateStore {
        private final ConcurrentHashMap<PartitionKey, RouteState> states = new ConcurrentHashMap<>();
    }

    /**
     * Create a server-side route bridge with default accept-all authorization.
     * @param routingControl routing controller used to switch branches
     * @param version websocket version to negotiate
     * @param targetRoute route name to switch to after a successful handshake
     */
    public WebSocketServerUpgradeRouteDuplex(ProtoRoutingControl routingControl, WebSocketVersion version, String targetRoute) {
        this(routingControl, version, targetRoute, (event, callback) -> callback.accept());
    }

    /**
     * Create a server-side route bridge with an explicit handshake authorizer.
     * @param routingControl routing controller used to switch branches
     * @param version websocket version to negotiate
     * @param targetRoute route name to switch to after a successful handshake
     * @param authorizer callback that accepts or rejects the request
     */
    public WebSocketServerUpgradeRouteDuplex(ProtoRoutingControl routingControl, WebSocketVersion version, String targetRoute, WebSocketHandshakeAuthorizer authorizer) {
        this.delegate = new WebSocketServerHandshakeDuplex(version, Objects.requireNonNull(authorizer, "authorizer is null"));
        this.routingControl = Objects.requireNonNull(routingControl, "routingControl is null");
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
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,//
            ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown,//
            ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        if (isRcv) {
            return this.handleReceive(context, rcvUp, rcvDown);
        } else {
            return this.handleSend(context, sndUp, sndDown);
        }
    }

    private ProtoStatus handleReceive(ProtoContext context, ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown) throws Throwable {
        while (rcvUp.hasMore()) {
            HttpObject msg = rcvUp.peekMessage();
            if (msg == null) {
                rcvUp.takeMessage();
                continue;
            }

            // Retry a deferred buffered flush before consuming new request parts.
            RouteState pendingState = state(context, msg);
            if (pendingState.pendingFlush) {
                if (!this.flushBufferedRequest(pendingState, rcvDown)) {
                    return ProtoStatus.Stop;
                }
                this.resetRequestRoutingState(pendingState, false);
            }

            if (isResetTerminalContent(msg)) {
                HttpObject ignored = rcvUp.takeMessage();
                if (ignored != null) {
                    ignored.release();
                }
                continue;
            }

            RouteState state = state(context, msg);

            if (state.currentRequestHandshake != null) {
                if (state.currentRequestHandshake) {
                    ProtoStatus status = this.forwardSingleToHandshake(context, rcvUp, RECEIVE_STAGE_QUEUE_KEY, rcvDown, true);
                    if (status != ProtoStatus.Next) {
                        return status;
                    }
                    this.switchIfReady(context, state);
                } else {
                    // Do not take from rcvUp until rcvDown can accept one message.
                    if (!rcvDown.hasSlot()) {
                        return ProtoStatus.Stop;
                    }

                    HttpObject forward = rcvUp.takeMessage();
                    if (forward != null) {
                        rcvDown.offerMessage(forward);
                    }
                }

                if (isRequestComplete(msg)) {
                    this.resetRequestRoutingState(state, false);
                }

                continue;
            }

            if (!state.requestParts.isActive() && !(msg instanceof HttpRequest)) {
                if (!rcvDown.hasSlot()) {
                    return ProtoStatus.Stop;
                }

                HttpObject forward = rcvUp.takeMessage();
                if (forward != null) {
                    rcvDown.offerMessage(forward);
                }
                continue;
            }

            this.bufferNextRequestMessage(context, state, rcvUp, msg);
            state.requestParts.appendRequest(msg);

            if (!isHeaderSectionClosed(msg)) {
                if (isRequestComplete(msg)) {
                    if (!this.flushBufferedRequest(state, rcvDown)) {
                        return ProtoStatus.Stop;
                    }
                    this.resetRequestRoutingState(state, false);
                }
                continue;
            }

            boolean handshakeRequest = isHandshakeRequest(state.requestParts);
            boolean requestComplete = isRequestComplete(msg);
            if (handshakeRequest) {
                state.handshakePending = true;
                this.forwardBufferedToHandshake(state, context, rcvDown);
                this.switchIfReady(context, state);
            } else {
                if (!this.flushBufferedRequest(state, rcvDown)) {
                    return ProtoStatus.Stop;
                }
            }

            if (requestComplete) {
                this.resetRequestRoutingState(state, false);
            } else {
                state.currentRequestHandshake = handshakeRequest;
                state.bufferedRequestViewRef = null;
                state.requestParts.reset();
            }
        }
        return ProtoStatus.Next;
    }

    private ProtoStatus handleSend(ProtoContext context, ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        while (sndUp.hasMore()) {
            HttpObject peek = sndUp.peekMessage();
            if (peek == null) {
                sndUp.takeMessage();
                continue;
            }

            RouteState state = state(context, peek);
            if (state.handshakePending) {
                ProtoStatus status = this.forwardSingleToHandshake(context, sndUp, SEND_STAGE_QUEUE_KEY, sndDown, false);
                if (status != ProtoStatus.Next) {
                    return status;
                }

                if (isResponseComplete(peek)) {
                    if (this.delegate.isHandshakeReady(context)) {
                        state.handshakePending = false;
                        this.switchRoute(this.targetRoute);
                    } else {
                        state.handshakePending = false;
                    }
                }
                continue;
            }

            // Do not take from sndUp until sndDown can accept one message.
            if (!sndDown.hasSlot()) {
                return ProtoStatus.Stop;
            }
            HttpObject msg = sndUp.takeMessage();
            if (msg != null) {
                sndDown.offerMessage(msg);
            }
        }
        return ProtoStatus.Next;
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

    private void switchIfReady(ProtoContext context, RouteState state) {
        if (this.delegate.isHandshakeReady(context)) {
            state.handshakePending = false;
            this.switchRoute(this.targetRoute);
        }
    }

    private void switchRoute(String targetRoute) {
        this.routingControl.switchRouteNextTick(targetRoute);
    }

    private void forwardBufferedToHandshake(RouteState state, ProtoContext context, ProtoSndQueue<HttpObject> rcvDown) throws Throwable {
        ProtoRcvQueueView<HttpObject> bufferedQueue = state.bufferedRequestViewRef;
        if (bufferedQueue == null) {
            return;
        }

        try {
            while (bufferedQueue.hasMore()) {
                HttpObject buffered = bufferedQueue.takeMessage();
                if (buffered == null) {
                    continue;
                }

                ProtoStatus status = this.delegate.onReceiveMessage(context, buffered, rcvDown);
                if (status != ProtoStatus.Next) {
                    throw new IllegalStateException("unexpected server handshake status while forwarding buffered request: " + status);
                }
            }
        } finally {
            state.bufferedRequestViewRef = null;
        }
    }

    private ProtoStatus forwardSingleToHandshake(ProtoContext context, ProtoRcvQueue<HttpObject> src, String stagingKey, ProtoSndQueue<HttpObject> down, boolean isRcv) throws Throwable {
        ProtoRcvQueueView<HttpObject> stagedView = this.stageNextMessage(src, stagingKey);
        if (isRcv) {
            return this.delegate.onReceiveData(context, stagedView, down);
        } else {
            return this.delegate.onSendData(context, stagedView, down);
        }
    }

    private ProtoRcvQueueView<HttpObject> stageNextMessage(ProtoRcvQueue<HttpObject> src, String stagingKey) {
        src.drainToQueue(stagingKey, 1);
        return src.queueView(stagingKey);
    }

    private boolean flushBufferedRequest(RouteState state, ProtoSndQueue<HttpObject> rcvDown) {
        ProtoRcvQueueView<HttpObject> bufferedQueue = state.bufferedRequestViewRef;
        if (bufferedQueue == null || !bufferedQueue.hasMore()) {
            state.bufferedRequestViewRef = null;
            state.pendingFlush = false;
            return true;
        }

        // Leave buffered data in staging when downstream capacity is still insufficient.
        if (rcvDown.slotSize() < bufferedQueue.queueSize()) {
            state.pendingFlush = true;
            return false;
        }

        rcvDown.offerMessage(bufferedQueue.takeMessage(-1));
        state.bufferedRequestViewRef = null;
        state.pendingFlush = false;
        return true;
    }

    private void resetRequestRoutingState(RouteState state, boolean releaseBuffered) {
        if (state.bufferedRequestViewRef != null) {
            if (releaseBuffered) {
                state.bufferedRequestViewRef.discard();
            }
            state.bufferedRequestViewRef = null;
        }
        state.pendingFlush = false;
        state.requestParts.reset();
        state.currentRequestHandshake = null;
    }

    private void bufferNextRequestMessage(ProtoContext context, RouteState state, ProtoRcvQueue<HttpObject> src, HttpObject msg) {
        ProtoRcvQueueView<HttpObject> bufferedQueue = state.bufferedRequestViewRef;
        if (bufferedQueue == null) {
            String key = BUFFER_QUEUE_PREFIX + partitionKey(context, msg).getKey();
            src.drainToQueue(key, 1);
            state.bufferedRequestViewRef = src.queueView(key);
        } else {
            src.drainToQueue(bufferedQueue.getKey(), 1);
        }
    }

    private RouteState state(ProtoContext context) {
        RouteState state = context.context(RouteState.class);
        return state != null ? state : state(context, null);
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
        if (store != null) {
            store.states.remove(partitionKey(context, null));
        }
    }

    private RouteStateStore stateStore(ProtoContext context) {
        SoChannel<?> channel = context.getChannel();
        Object attr = channel != null ? channel.getAttribute(ROUTE_STATE_STORE_KEY) : null;
        if (attr instanceof RouteStateStore) {
            return (RouteStateStore) attr;
        }

        RouteStateStore store = new RouteStateStore();
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

    private static boolean isResponseComplete(HttpObject msg) {
        return msg instanceof LastHttpHeaders || msg instanceof LastHttpContent || isAggregateLikeResponse(msg);
    }

    private static boolean isAggregateLikeResponse(HttpObject msg) {
        return msg instanceof HttpResponse && msg instanceof HttpContent;
    }

    private static boolean isResetTerminalContent(HttpObject msg) {
        return msg instanceof LastHttpContent && msg.isBad() && msg.badReason() != null && msg.badReason().startsWith("HTTP/2 stream reset:");
    }
}