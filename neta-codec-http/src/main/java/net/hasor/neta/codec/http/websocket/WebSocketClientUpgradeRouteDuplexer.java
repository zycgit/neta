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
import net.hasor.neta.channel.*;
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
    private final WebSocketClientHandshakeDuplexer delegate;
    private final ProtoRoutingControl              control;
    private final String                           targetRoute;
    //
    private final HttpMessageParts                 requestParts         = new HttpMessageParts();
    private final List<HttpObject>                 bufferedRequestParts = new ArrayList<>();
    private       boolean                          handshakePending;
    private       Boolean                          currentRequestHandshake;

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
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,          //
            ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown,//
            ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
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
        this.handshakePending = false;
        this.resetRequestRoutingState(true);
        return this.delegate.onError(context, isRcv, e, eh);
    }

    /**
     * Clear any buffered route state when the channel closes.
     */
    @Override
    public void onClose(ProtoContext context) {
        this.handshakePending = false;
        this.resetRequestRoutingState(true);
        this.delegate.onClose(context);
    }

    //

    private ProtoStatus handleReceive(ProtoContext context, ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown) throws Throwable {
        while (rcvUp.hasMore()) {
            HttpObject msg = rcvUp.peekMessage();
            if (msg == null) {
                rcvUp.takeMessage();
                continue;
            }

            if (this.handshakePending && isHandshakeResponsePart(msg)) {
                this.delegate.onMessage(context, true, rcvUp, rcvDown, ProtoQueue.emptyRcv(), null);
                if (WebSocketUtils.isReady(context)) {
                    this.handshakePending = false;
                    this.control.switchRoute(this.targetRoute);
                }
                continue;
            }

            rcvDown.offerMessage(rcvUp.takeMessage());
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

            if (this.currentRequestHandshake != null) {
                msg = sndUp.takeMessage();
                if (Boolean.TRUE.equals(this.currentRequestHandshake)) {
                    ProtoQueue<HttpObject> queue = new ProtoQueue<>(1);
                    queue.offerMessage(msg);
                    this.delegate.onMessage(context, false, ProtoQueue.emptyRcv(), null, queue, sndDown);
                } else {
                    sndDown.offerMessage(msg);
                }
                if (isRequestComplete(msg)) {
                    this.resetRequestRoutingState(false);
                }
                continue;
            }

            if (!this.requestParts.isActive() && !(msg instanceof HttpRequest)) {
                sndDown.offerMessage(sndUp.takeMessage());
                continue;
            }

            msg = sndUp.takeMessage();
            this.bufferedRequestParts.add(msg);
            this.requestParts.appendRequest(msg);

            if (!isHeaderSectionClosed(msg)) {
                if (isRequestComplete(msg)) {
                    sndDown.offerMessage(this.bufferedRequestParts);
                    this.resetRequestRoutingState(false);
                }
                continue;
            }

            boolean handshakeRequest = isHandshakeRequest(this.requestParts);
            boolean requestComplete = isRequestComplete(msg);
            if (handshakeRequest) {
                this.handshakePending = true;
                ProtoQueue<HttpObject> queue = new ProtoQueue<>(this.bufferedRequestParts.size());
                queue.offerMessage(this.bufferedRequestParts);
                this.delegate.onMessage(context, false, ProtoQueue.emptyRcv(), null, queue, sndDown);
            } else {
                sndDown.offerMessage(this.bufferedRequestParts);
            }

            if (requestComplete) {
                this.resetRequestRoutingState(false);
            } else {
                this.currentRequestHandshake = handshakeRequest;
                this.bufferedRequestParts.clear();
                this.requestParts.reset();
            }
        }
        return ProtoStatus.Next;
    }

    private void resetRequestRoutingState(boolean releaseBuffered) {
        if (releaseBuffered) {
            for (HttpObject msg : this.bufferedRequestParts) {
                if (msg != null) {
                    msg.release();
                }
            }
        }
        this.bufferedRequestParts.clear();
        this.requestParts.reset();
        this.currentRequestHandshake = null;
    }

    private static boolean isHandshakeRequest(HttpMessageParts request) {
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