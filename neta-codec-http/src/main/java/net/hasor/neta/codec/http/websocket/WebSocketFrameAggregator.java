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
import java.nio.charset.StandardCharsets;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.bytebuf.CompositeByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpObject;

/**
 * Aggregates inbound {@link WebSocketFrame} sequences into application-visible messages.
 * <p>
 * This handler sits after {@link WebSocketFrameDecoder}. It reassembles fragmented text
 * and binary messages, translates control frames into higher-level events, and keeps the
 * downstream side focused on {@link WebSocketMessage} rather than transport fragments.
 * <p>
 * Typical manual pipeline:
 * <pre>
 *   ctx.addLast("ws-handshake", new WebSocketServerDuplexer(version));
 *   ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder(version));
 *   ctx.addLastDecoder("ws-msg", new WebSocketFrameAggregator(65536));
 *   ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder(version));
 * </pre>
 * <p>
 * pipeline view:
 * <pre>
 *   HttpByteBuf
 *      -> WebSocketFrameDecoder
 *      -> WebSocketFrame / Continuation frame
 *      -> WebSocketFrameAggregator
 *      -> WebSocketMessage / WebSocketCloseMessage / passthrough event
 * </pre>
 * <p>
 * Main behaviors:
 * <ul>
 *   <li>Reassembles TEXT/BINARY + CONTINUATION fragments into one {@link WebSocketMessage}.</li>
 *   <li>Consumes PING and sends PONG automatically.</li>
 *   <li>Translates PONG into a message-level object for downstream inspection.</li>
 *   <li>Translates CLOSE into {@link WebSocketCloseMessage} and emits the close reply.</li>
 * </ul>
 * <p>
 * Any non-{@link WebSocketFrame} {@link HttpObject} is passed through unchanged.
 * If you prefer a direction-explicit name, use {@link WebSocketInboundAggregator}, which
 * keeps the same behavior but reads more clearly inside duplex assembly code.
 */
public class WebSocketFrameAggregator implements ProtoHandler<HttpObject, HttpObject> {
    /** Default maximum message size (64 KB). */
    public static final int             DEFAULT_MAX_MESSAGE_SIZE = 65536;
    private final       int             maxMessageSize;
    private             WebSocketOpcode fragmentType;
    private             ByteBuf         fragmentBuf;

    /** Creates an aggregator with the default maximum message size (64 KB). */
    public WebSocketFrameAggregator() {
        this(DEFAULT_MAX_MESSAGE_SIZE);
    }

    /**
     * Creates an aggregator with the specified maximum message size.
     * @param maxMessageSize the maximum allowed reassembled message size in bytes
     */
    public WebSocketFrameAggregator(int maxMessageSize) {
        if (maxMessageSize <= 0) {
            throw new IllegalArgumentException("maxMessageSize must be positive");
        }
        this.maxMessageSize = maxMessageSize;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }
            if (!(msg instanceof WebSocketFrame)) {
                dst.offerMessage(msg);
                continue;
            }
            WebSocketFrame frame = (WebSocketFrame) msg;
            try {
                handleFrame(context, frame, dst);
            } finally {
                frame.release();
            }
        }
        return ProtoStatus.Next;
    }

    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        resetFragment();
        return ProtoStatus.Next;
    }

    private void handleFrame(ProtoContext context, WebSocketFrame frame, ProtoSndQueue<HttpObject> dst) {
        WebSocketOpcode opcode = frame.opcode();
        if (opcode == null) {
            resetFragment();
            return;
        }

        switch (opcode) {
            case TEXT:
            case BINARY:
                handleDataFrame(frame, dst);
                break;
            case CONTINUATION:
                handleContinuation(frame, dst);
                break;
            case PING:
                handlePing(context, frame, dst);
                break;
            case PONG:
                handlePong(frame, dst);
                break;
            case CLOSE:
                handleClose(context, frame, dst);
                break;
            default:
                break;
        }
    }

    // =========================================================================
    // Data frames (TEXT / BINARY)
    // =========================================================================

    private void handleDataFrame(WebSocketFrame frame, ProtoSndQueue<HttpObject> dst) {
        if (this.fragmentType != null) {
            // Received a new data frame while a fragmented message is still being assembled.
            // Per RFC 6455 §5.4, this is a protocol error. Discard the incomplete fragment.
            resetFragment();
        }

        if (frame.isFinalFragment()) {
            // Complete single-frame message
            if (frame.opcode() == WebSocketOpcode.TEXT) {
                dst.offerMessage(WebSocketUtils.textMessage(frame.content()));
            } else if (frame.opcode() == WebSocketOpcode.BINARY) {
                dst.offerMessage(WebSocketUtils.binaryMessage(frame.content()));
            }
        } else {
            // First fragment of a multi-frame message
            this.fragmentType = frame.opcode();
            this.fragmentBuf = cloneContent(frame.content());
        }
    }

    private void handleContinuation(WebSocketFrame frame, ProtoSndQueue<HttpObject> dst) {
        if (this.fragmentType == null) {
            // No fragmented message in progress — surface it as an explicit continuation message.
            dst.offerMessage(WebSocketUtils.continuationMessage(frame.content()));
            return;
        }

        appendContent(frame.content());

        if (frame.isFinalFragment()) {
            ByteBuf assembled = this.fragmentBuf;
            WebSocketOpcode type = this.fragmentType;
            this.fragmentType = null;
            this.fragmentBuf = null;
            try {
                if (type == WebSocketOpcode.TEXT) {
                    dst.offerMessage(WebSocketUtils.textMessage(assembled));
                } else if (type == WebSocketOpcode.BINARY) {
                    dst.offerMessage(WebSocketUtils.binaryMessage(assembled));
                }
            } finally {
                if (assembled != null) {
                    assembled.release();
                }
            }
        }
    }

    private void appendContent(ByteBuf content) {
        if (content == null || content.readableBytes() == 0) {
            return;
        }
        int newSize = (this.fragmentBuf != null ? this.fragmentBuf.readableBytes() : 0) + content.readableBytes();
        if (newSize > this.maxMessageSize) {
            resetFragment();
            throw new WebSocketMessageTooLargeException("WebSocket message exceeds maximum size: " + newSize + " > " + this.maxMessageSize, this.maxMessageSize, newSize);
        }

        if (this.fragmentBuf == null || this.fragmentBuf.readableBytes() == 0) {
            this.fragmentBuf = cloneContent(content);
        } else if (this.fragmentBuf instanceof CompositeByteBuf) {
            ((CompositeByteBuf) this.fragmentBuf).addComponent(content);
        } else {
            CompositeByteBuf composite = ByteBufUtils.compositeBuffer(this.fragmentBuf.alloc());
            composite.addComponent(this.fragmentBuf);
            this.fragmentBuf.free();
            composite.addComponent(content);
            this.fragmentBuf = composite;
        }
    }

    private ByteBuf cloneContent(ByteBuf content) {
        if (content == null || content.readableBytes() == 0) {
            return ByteBuf.EMPTY;
        }
        return content.retain();
    }

    // =========================================================================
    // Control frames
    // =========================================================================

    private void handlePing(ProtoContext context, WebSocketFrame frame, ProtoSndQueue<HttpObject> dst) {
        // Auto-reply with PONG carrying the same payload
        WebSocketFrame pong;
        if (frame.content() != null && frame.content().readableBytes() > 0) {
            pong = WebSocketUtils.pongFrame(false, null, frame.content().retain());
        } else {
            pong = WebSocketUtils.pongFrame();
        }
        context.sendData(pong);
        dst.offerMessage(WebSocketUtils.pingMessage(frame.content()));
    }

    private void handlePong(WebSocketFrame frame, ProtoSndQueue<HttpObject> dst) {
        dst.offerMessage(WebSocketUtils.pongMessage(frame.content()));
    }

    private void handleClose(ProtoContext context, WebSocketFrame frame, ProtoSndQueue<HttpObject> dst) {
        ByteBuf content = frame.content();
        int statusCode;
        String reason;

        if (content != null && content.readableBytes() >= 2) {
            statusCode = ((content.getByte(0) & 0xFF) << 8) | (content.getByte(1) & 0xFF);
            int reasonLen = content.readableBytes() - 2;
            if (reasonLen > 0) {
                byte[] reasonBytes = new byte[reasonLen];
                content.getBytes(2, reasonBytes, 0, reasonLen);
                reason = new String(reasonBytes, StandardCharsets.UTF_8);
            } else {
                reason = null;
            }
        } else {
            statusCode = WebSocketCloseCode.NO_STATUS;
            reason = null;
        }

        // Send close reply
        context.sendData(WebSocketUtils.closeFrame(statusCode, reason));

        // Emit close message downstream
        dst.offerMessage(WebSocketUtils.closeMessage(statusCode, reason));
    }

    // =========================================================================
    // State management
    // =========================================================================

    private void resetFragment() {
        if (this.fragmentBuf != null) {
            this.fragmentBuf.free();
            this.fragmentBuf = null;
        }
        this.fragmentType = null;
    }

    @Override
    public void onClose(ProtoContext context) {
        resetFragment();
    }
}
