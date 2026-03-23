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
    private       boolean                          handshakePending;

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
    public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
        return this.delegate.onUserEvent(context, event, isRcv);
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

            if (isHandshakeRequest(msg)) {
                this.handshakePending = true;
                this.delegate.onMessage(context, false, ProtoQueue.emptyRcv(), null, sndUp, sndDown);
                continue;
            }

            sndDown.offerMessage(sndUp.takeMessage());
        }
        return ProtoStatus.Next;
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        this.handshakePending = false;
        return this.delegate.onError(context, isRcv, e, eh);
    }

    @Override
    public void onClose(ProtoContext context) {
        this.handshakePending = false;
        this.delegate.onClose(context);
    }

    private static void switchRoute(ProtoContext context, String targetRoute) {
        ProtoRoutingControl routingControl = context.context(ProtoRoutingControl.class);
        if (routingControl != null) {
            routingControl.switchRoute(targetRoute);
        }
    }

    private static boolean isHandshakeRequest(HttpObject msg) {
        if (!(msg instanceof FullHttpRequest)) {
            return false;
        }
        FullHttpRequest request = (FullHttpRequest) msg;
        String upgrade = request.getString(HttpHeaderNames.UPGRADE);
        String connection = request.getString(HttpHeaderNames.CONNECTION);
        return HttpHeaderValues.WEBSOCKET.equalsIgnoreCase(upgrade) && connection != null && connection.toLowerCase().contains(HttpHeaderValues.UPGRADE);
    }

    private static boolean isHandshakeResponsePart(HttpObject msg) {
        return msg instanceof HttpResponse || msg instanceof HttpHeaders || msg instanceof HttpContent;
    }
}