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

/**
 * Public bidirectional message-layer entry for post-handshake WebSocket traffic.
 * <p>
 * This duplexer pairs {@link WebSocketInboundHandler} and {@link WebSocketOutboundHandler}
 * as a single protocol node, so a pipeline that already works on {@link WebSocketFrame}
 * can expose a higher-level {@link WebSocketMessage} stream without wiring the two
 * handlers separately.
 * The duplexer assumes a completed {@link WebSocketHandshakeDuplexer} is installed earlier
 * in the same pipeline so outbound message encoding can resolve the negotiated
 * {@link WebSocketContext}.
 * <p>
 * Typical usage:
 * <pre>{@code
 * ctx.addLast("http", new HttpServerDuplexe());
 * ctx.addLast("ws-handshake", new WebSocketHandshakeDuplexer(true, WebSocketVersion.V13));
 * ctx.addLast("ws-frame", new WebSocketFrameDuplexer());
 * ctx.addLast("ws-message", new WebSocketMessageDuplexer());
 * }</pre>
 * <p>
 * Behavior summary:
 * <ul>
 *   <li>Receive direction: consumes {@link WebSocketFrame} and emits {@link WebSocketMessage} chunks.</li>
 *   <li>Send direction: consumes {@link WebSocketMessage} chunks and emits {@link WebSocketFrame}.</li>
 *   <li>Control-frame behavior follows the paired handlers: inbound PING/PONG/CLOSE are handled at protocol level and close events are published as user events.</li>
 *   <li>Protocol-generated control replies are internally represented as private {@link WebSocketMessage} objects while the public send-side contract remains {@link WebSocketMessage}.</li>
 *   <li>The duplexer does not perform the opening handshake and does not replace {@link WebSocketFrameDuplexer}.</li>
 *   <li>The duplexer keeps fragmented messages as chunked {@link WebSocketMessage} flow via {@link WebSocketMessage#sequence()} rather than aggregating them.</li>
 * </ul>
 */
public class WebSocketMessageDuplexer implements ProtoDuplexer<WebSocketFrame, WebSocketMessage, WebSocketMessage, WebSocketFrame> {
    private final WebSocketInboundHandler  inbound  = new WebSocketInboundHandler();
    private final WebSocketOutboundHandler outbound = new WebSocketOutboundHandler();

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.inbound.onInit(name, rcvSize, context);
        this.outbound.onInit(name, sndSize, context);
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        this.inbound.onActive(context);
        this.outbound.onActive(context);
    }

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
        if (isRcv) {
            return this.inbound.onUserEvent(context, event);
        } else {
            return this.outbound.onUserEvent(context, event);
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv,                    //
            ProtoRcvQueue<WebSocketFrame> rcvUp, ProtoSndQueue<WebSocketMessage> rcvDown,//
            ProtoRcvQueue<WebSocketMessage> sndUp, ProtoSndQueue<WebSocketFrame> sndDown) throws Throwable {
        if (isRcv) {
            return this.inbound.onMessage(context, rcvUp, rcvDown);
        } else {
            return this.outbound.onMessage(context, sndUp, sndDown);
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.inbound.onError(context, e, eh);
        } else {
            return this.outbound.onError(context, e, eh);
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        this.inbound.onClose(context);
        this.outbound.onClose(context);
    }
}