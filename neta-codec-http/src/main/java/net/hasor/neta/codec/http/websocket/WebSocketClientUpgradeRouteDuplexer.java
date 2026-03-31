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
 * Client-side upgrade bridge used inside HTTP/WebSocket mixed routing.
 * <p>
 * This duplexer is intended for routed client pipelines where the same connection first carries
 * normal HTTP traffic and later upgrades to WebSocket. Before upgrade, ordinary HTTP messages are
 * passed through unchanged. When an outbound WebSocket upgrade request is detected, the duplexer
 * temporarily delegates request/response processing to {@link WebSocketClientHandshakeDuplexer}.
 * After the handshake succeeds, it switches the current {@link ProtoRoutingControl route} to the
 * configured target branch.
 * <p>
 * Function:
 * <pre>
 *   keep ordinary HTTP traffic on the current route
 *   intercept one client upgrade transaction
 *   validate the 101 response through WebSocketClientHandshakeDuplexer
 *   switch to the target route after WebSocket becomes ready
 * </pre>
 * <p>
 * pipeline view:
 * <pre>
 *   HTTP branch
 *      -> WebSocketClientUpgradeRouteDuplexer
 *      -> HttpResponseAggregator / other HTTP handlers
 *      -> switchRoute(target)
 *      -> WebSocket branch
 * </pre>
 * <p>
 * Typical usage:
 * <pre>
 *   ProtoRoutingBuilder&lt;Object, Object&gt; routing = ProtoHelper.typedRoutingAsDefault("http", branchCtx -> {
 *       branchCtx.addLast("ws-upgrade", new WebSocketClientUpgradeRouteDuplexer(WebSocketVersion.V13, "websocket"));
 *       branchCtx.addLastDecoder("resp-agg", new HttpResponseAggregator());
 *   }).branchByInitializer("websocket", branchCtx -> {
 *       branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
 *       branchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
 *   });
 * </pre>
 * <p>
 * This duplexer does not replace a full HTTP codec and does not handle server-side upgrade flow.
 * It only coordinates one client-side HTTP-to-WebSocket route transition on top of an existing
 * routed pipeline.
 */
public class WebSocketClientUpgradeRouteDuplexer implements ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject> {
    private final WebSocketClientHandshakeDuplexer delegate;
    private final String                           targetRoute;
    private final HttpMessageParts                 requestParts         = new HttpMessageParts();
    private final List<HttpObject>                 bufferedRequestParts = new ArrayList<>();
    private       boolean                          handshakePending;
    private       Boolean                          currentRequestHandshake;

    public WebSocketClientUpgradeRouteDuplexer(WebSocketVersion version, String targetRoute) {
        this.delegate = new WebSocketClientHandshakeDuplexer(version);
        this.targetRoute = targetRoute;
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.delegate.onInit(name, rcvSize, sndSize, context);
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.delegate.onActive(context);
    }

    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        return this.delegate.onEvent(context, event, isRcv);
    }

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

            if (this.handshakePending && isHandshakeResponsePart(msg)) {
                this.delegate.onMessage(context, true, rcvUp, rcvDown, ProtoQueue.emptyRcv(), null);
                if (WebSocketUtils.isReady(context)) {
                    this.handshakePending = false;
                    switchRoute(context, this.targetRoute);
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
                    this.forwardToHandshake(context, msg, sndDown);
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
                    this.flushBufferedRequest(sndDown);
                    this.resetRequestRoutingState(false);
                }
                continue;
            }

            boolean handshakeRequest = isHandshakeRequest(this.requestParts);
            boolean requestComplete = isRequestComplete(msg);
            if (handshakeRequest) {
                this.handshakePending = true;
                this.forwardBufferedToHandshake(context, sndDown);
            } else {
                this.flushBufferedRequest(sndDown);
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

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        this.handshakePending = false;
        this.resetRequestRoutingState(true);
        return this.delegate.onError(context, isRcv, e, eh);
    }

    @Override
    public void onClose(ProtoContext context) {
        this.handshakePending = false;
        this.resetRequestRoutingState(true);
        this.delegate.onClose(context);
    }

    private static void switchRoute(ProtoContext context, String targetRoute) {
        ProtoRoutingControl routingControl = context.context(ProtoRoutingControl.class);
        if (routingControl != null) {
            routingControl.switchRoute(targetRoute);
        }
    }

    private void forwardBufferedToHandshake(ProtoContext context, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        ProtoQueue<HttpObject> queue = new ProtoQueue<>(this.bufferedRequestParts.size());
        queue.offerMessage(this.bufferedRequestParts);
        this.delegate.onMessage(context, false, ProtoQueue.emptyRcv(), null, queue, sndDown);
    }

    private void forwardToHandshake(ProtoContext context, HttpObject msg, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        ProtoQueue<HttpObject> queue = new ProtoQueue<>(1);
        queue.offerMessage(msg);
        this.delegate.onMessage(context, false, ProtoQueue.emptyRcv(), null, queue, sndDown);
    }

    private void flushBufferedRequest(ProtoSndQueue<HttpObject> sndDown) {
        sndDown.offerMessage(this.bufferedRequestParts);
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