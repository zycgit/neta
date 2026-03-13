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
import java.util.concurrent.ThreadLocalRandom;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpObject;

/**
 * Converts outbound message-level WebSocket objects back into transport frames.
 * <p>
 * This handler is the outbound mirror of {@link WebSocketInboundAggregator}. It takes
 * application-visible {@link WebSocketMessage} instances and turns them into
 * {@link WebSocketFrame} objects that can then be serialized by
 * {@link WebSocketFrameEncoder}.
 * <p>
 * Typical manual pipeline:
 * <pre>
 *   App Handler
 *      -> WebSocketOutboundAggregator
 *      -> WebSocketFrameEncoder
 *      -> HttpByteBuf
 * </pre>
 * <p>
 * pipeline view:
 * <pre>
 *   WebSocketMessage
 *      -> WebSocketOutboundAggregator
 *      -> WebSocketFrame
 *      -> WebSocketFrameEncoder
 *      -> HttpByteBuf
 * </pre>
 * <p>
 * When {@code clientMode} is enabled and the selected version is in the RFC 6455 family,
 * outbound frames are masked automatically to satisfy client-to-server framing rules.
 * <p>
 * Any non-{@link WebSocketMessage} object is passed through unchanged. Raw
 * {@link WebSocketFrame} instances also pass through so callers can still control
 * fragmentation and control-frame timing manually when needed.
 */
public class WebSocketOutboundAggregator implements ProtoHandler<HttpObject, HttpObject> {
    private final WebSocketVersion version;
    private final boolean          clientMode;

    public WebSocketOutboundAggregator(WebSocketVersion version, boolean clientMode) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        this.version = version;
        this.clientMode = clientMode;
    }

    public WebSocketOutboundAggregator(WebSocketVersion version) {
        this(version, false);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        while (src.hasMore()) {
            HttpObject obj = src.takeMessage();
            if (obj == null) {
                continue;
            }
            if (obj instanceof WebSocketFrame || obj instanceof HandshakeWebSocketMessage || !(obj instanceof WebSocketMessage)) {
                dst.offerMessage(obj);
                continue;
            }

            boolean consumed = false;
            try {
                WebSocketMessage msg = (WebSocketMessage) obj;
                dst.offerMessage(this.toFrame(msg));
                consumed = true;
            } finally {
                if (consumed) {
                    obj.release();
                }
            }
        }
        return ProtoStatus.Next;
    }

    private WebSocketFrame toFrame(WebSocketMessage msg) {
        WebSocketOpcode type = msg.type();
        if (type == null) {
            throw new WebSocketProtocolViolationException("WebSocket message type must not be null.");
        }

        boolean masked = this.clientMode && this.version.isRfc6455Framing();
        byte[] maskingKey = masked ? newMaskingKey() : null;
        ByteBuf content = msg.content();

        switch (type) {
            case TEXT:
                return WebSocketUtils.textFrame(true, masked, maskingKey, retainContent(content));
            case BINARY:
                return WebSocketUtils.binaryFrame(true, masked, maskingKey, retainContent(content));
            case CONTINUATION:
                return WebSocketUtils.continuationFrame(true, masked, maskingKey, retainContent(content));
            case PING:
                return WebSocketUtils.pingFrame(masked, maskingKey, retainContent(content));
            case PONG:
                return WebSocketUtils.pongFrame(masked, maskingKey, retainContent(content));
            case CLOSE:
                if (!(msg instanceof WebSocketCloseMessage)) {
                    throw new WebSocketProtocolViolationException("close message must be an instance of WebSocketCloseMessage.");
                }
                WebSocketCloseMessage closeMsg = (WebSocketCloseMessage) msg;
                return WebSocketUtils.closeFrame(closeMsg.statusCode(), closeMsg.reason());
            case HANDSHAKE_COMPLETE:
                throw new WebSocketProtocolViolationException("HandshakeWebSocketMessage must not be encoded into a WebSocket frame.");
            default:
                throw new WebSocketProtocolViolationException("unsupported WebSocket message type: " + type);
        }
    }

    private static ByteBuf retainContent(ByteBuf content) {
        return content == null ? ByteBuf.EMPTY : content.retain();
    }

    private static byte[] newMaskingKey() {
        int random = ThreadLocalRandom.current().nextInt();
        return new byte[] { (byte) (random >> 24), (byte) (random >> 16), (byte) (random >> 8), (byte) random };
    }
}