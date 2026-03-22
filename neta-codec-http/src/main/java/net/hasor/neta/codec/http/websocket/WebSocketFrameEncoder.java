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
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.DefaultHttpByteBuf;
import net.hasor.neta.codec.http.HttpObject;

/**
 * Encodes {@link WebSocketFrame} objects into transparent-mode HTTP payload buffers.
 * <p>
 * This encoder sits on the outbound side after WebSocket frame construction and before
 * the HTTP/1.x codec writes raw bytes to the socket. It converts frame-level objects into
 * {@link net.hasor.neta.codec.http.HttpByteBuf}, which the HTTP layer forwards unchanged
 * once transparent mode is enabled by the opening handshake duplexer.
 * <p>
 * Preferred usage for a bidirectional pipeline is {@link WebSocketFrameDuplexer}.
 * Install this encoder directly only when the send direction must be assembled
 * independently from the receive direction.
 * When the no-arg constructor is used behind {@link WebSocketHandshakeDuplexer},
 * the encoder first tries to resolve the negotiated version from {@link WebSocketContext}
 * and falls back to RFC 6455 version 13 when no handshake context is available.
 * <p>
 * Typical usage in a manually assembled outbound-only pipeline:
 * <pre>
 *   ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder());
 *   ctx.addLast("http", new HttpServerDuplexe());
 * </pre>
 * <p>
 * pipeline view:
 * <pre>
 *   WebSocketFrame
 *      -> WebSocketFrameEncoder
 *      -> HttpByteBuf
 *      -> HttpServerDuplexe / HttpClientDuplexe
 *      -> socket bytes
 * </pre>
 * <p>
 * Version support:
 * <ul>
 *   <li>{@link WebSocketVersion#V0}: Hixie-76 framing.</li>
 *   <li>{@link WebSocketVersion#V7}, {@link WebSocketVersion#V8}, {@link WebSocketVersion#V13}: RFC 6455 framing family.</li>
 * </ul>
 * <p><b>Ownership:</b> once a {@link WebSocketFrame} is consumed by this encoder, the
 * encoder takes over its lifecycle and releases the source frame after the outbound bytes
 * have been produced. Callers should not release a successfully handed-off frame twice.
 */
public class WebSocketFrameEncoder implements ProtoHandler<WebSocketFrame, HttpObject> {
    private static final Logger              logger           = Logger.getLogger(WebSocketFrameEncoder.class);
    private static final int                 XOR_SCRATCH_SIZE = 4096;
    private static final ThreadLocal<byte[]> XOR_SCRATCH      = ThreadLocal.withInitial(() -> new byte[XOR_SCRATCH_SIZE]);
    private final        WebSocketVersion    defaultVersion;
    private final        boolean             detectVersion;

    /** Creates an encoder for the specified WebSocket protocol version. */
    public WebSocketFrameEncoder(WebSocketVersion version) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }

        this.defaultVersion = version;
        this.detectVersion = false;
    }

    /** Creates an encoder that first uses the negotiated handshake version and falls back to RFC 6455 (version 13). */
    public WebSocketFrameEncoder() {
        this.defaultVersion = WebSocketVersion.V13;
        this.detectVersion = true;
    }

    private WebSocketVersion resolveVersion(ProtoContext context) {
        if (!this.detectVersion) {
            return this.defaultVersion;
        }
        WebSocketContext wsContext = context.context(WebSocketContext.class);
        if (wsContext != null) {
            WebSocketVersion detectedVersion = WebSocketVersion.of(wsContext.version());
            if (detectedVersion != null) {
                return detectedVersion;
            }
        }
        return this.defaultVersion;
    }

    // RFC 6455 encoding (V7, V8, V13)

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<WebSocketFrame> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        WebSocketVersion version = resolveVersion(context);
        while (src.hasMore()) {
            WebSocketFrame frame = src.takeMessage();
            if (frame == null) {
                continue;
            }

            try {
                ByteBuf encoded;
                if (version.isRfc6455Framing()) {
                    encoded = encodeRfc6455(context, frame);
                } else {
                    encoded = encodeHixie76(context, frame);
                }
                dst.offerMessage(new DefaultHttpByteBuf(encoded));
            } finally {
                frame.release();
            }
        }
        return ProtoStatus.Next;
    }

    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        long channelID = context.getChannel().getChannelId();
        if (context.getConfig().isPrintLog()) {
            logger.warn("[WS-ENC] channel=" + channelID + " encoder error, stateless reset. cause=" + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } else {
            logger.warn("[WS-ENC] channel=" + channelID + " encoder error, stateless reset. cause=" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return ProtoStatus.Next;
    }

    // Hixie-76 encoding (V0)

    /** Writes a variable-length integer in Hixie-76 style (7-bit groups, MSB first, continuation high bit). */
    private static void writeVariableLength(ByteBuf out, int length) {
        // Determine number of 7-bit groups needed
        int groups = 1;
        int tmp = length;
        while (tmp > 0x7F) {
            groups++;
            tmp >>= 7;
        }
        // Write from most-significant group to least-significant
        for (int i = groups - 1; i >= 0; i--) {
            byte b = (byte) ((length >> (i * 7)) & 0x7F);
            if (i > 0) {
                b |= 0x80; // continuation bit
            }
            out.writeByte(b);
        }
    }

    private ByteBuf encodeRfc6455(ProtoContext context, WebSocketFrame frame) {
        WebSocketOpcode opcode = requireOpcode(frame);
        ByteBuf content = frame.content();
        int payloadLen = (content != null) ? content.readableBytes() : 0;
        boolean masked = frame.isMasked() && frame.maskingKey() != null;
        byte[] maskKey = masked ? frame.maskingKey() : null;

        int headerSize = 2;
        if (payloadLen >= 126 && payloadLen <= 65535) {
            headerSize += 2;
        } else if (payloadLen > 65535) {
            headerSize += 8;
        }
        if (masked) {
            headerSize += 4;
        }

        ByteBuf out = context.byteBufAllocator().buffer(headerSize + payloadLen, Integer.MAX_VALUE);

        // Byte 0: FIN + opcode
        byte byte0 = (byte) ((frame.isFinalFragment() ? 0x80 : 0x00) | (opcode.code() & 0x0F));
        out.writeByte(byte0);

        // Byte 1: MASK flag + payload length indicator
        if (payloadLen < 126) {
            out.writeByte((byte) ((masked ? 0x80 : 0x00) | payloadLen));
        } else if (payloadLen <= 65535) {
            out.writeByte((byte) ((masked ? 0x80 : 0x00) | 126));
            out.writeByte((byte) ((payloadLen >> 8) & 0xFF));
            out.writeByte((byte) (payloadLen & 0xFF));
        } else {
            out.writeByte((byte) ((masked ? 0x80 : 0x00) | 127));
            out.writeByte((byte) 0);
            out.writeByte((byte) 0);
            out.writeByte((byte) 0);
            out.writeByte((byte) 0);
            out.writeByte((byte) ((payloadLen >> 24) & 0xFF));
            out.writeByte((byte) ((payloadLen >> 16) & 0xFF));
            out.writeByte((byte) ((payloadLen >> 8) & 0xFF));
            out.writeByte((byte) (payloadLen & 0xFF));
        }

        if (masked) {
            out.writeBytes(maskKey, 0, 4);
        }

        if (payloadLen > 0) {
            if (masked) {
                byte[] scratch = XOR_SCRATCH.get();
                int remaining = payloadLen;
                int srcOff = 0;
                while (remaining > 0) {
                    int chunk = Math.min(remaining, XOR_SCRATCH_SIZE);
                    content.getBytes(srcOff, scratch, 0, chunk);
                    int j = 0;
                    int chunk4 = chunk & ~3;
                    for (; j < chunk4; j += 4) {
                        scratch[j] ^= maskKey[0];
                        scratch[j + 1] ^= maskKey[1];
                        scratch[j + 2] ^= maskKey[2];
                        scratch[j + 3] ^= maskKey[3];
                    }
                    for (; j < chunk; j++) {
                        scratch[j] ^= maskKey[j & 3];
                    }
                    out.writeBytes(scratch, 0, chunk);
                    srcOff += chunk;
                    remaining -= chunk;
                }
            } else {
                content.getBuffer(0, out, payloadLen);
            }
        }

        out.markWriter();
        return out;
    }

    private ByteBuf encodeHixie76(ProtoContext context, WebSocketFrame frame) {
        WebSocketOpcode opcode = requireOpcode(frame);
        ByteBuf content = frame.content();
        int payloadLen = (content != null) ? content.readableBytes() : 0;

        if (opcode == WebSocketOpcode.CLOSE) {
            // Close frame: 0xFF 0x00
            ByteBuf out = context.byteBufAllocator().buffer(2, Integer.MAX_VALUE);
            out.writeByte((byte) 0xFF);
            out.writeByte((byte) 0x00);
            out.markWriter();
            return out;
        }

        if (opcode == WebSocketOpcode.BINARY) {
            // Binary frame: 0x80 + variable-length length + payload
            // Calculate length encoding size
            int tmpLen = payloadLen;
            int lengthBytes = 1;
            while (tmpLen > 0x7F) {
                lengthBytes++;
                tmpLen >>= 7;
            }
            ByteBuf out = context.byteBufAllocator().buffer(1 + lengthBytes + payloadLen, Integer.MAX_VALUE);
            out.writeByte((byte) 0x80);
            // Write variable-length integer (most-significant 7-bit group first, continuation bit on all but last)
            writeVariableLength(out, payloadLen);
            if (payloadLen > 0) {
                content.getBuffer(0, out, payloadLen);
            }
            out.markWriter();
            return out;
        }

        // Text frame (and all other opcodes fall through): 0x00 + payload + 0xFF
        ByteBuf out = context.byteBufAllocator().buffer(2 + payloadLen, Integer.MAX_VALUE);
        out.writeByte((byte) 0x00);
        if (payloadLen > 0) {
            content.getBuffer(0, out, payloadLen);
        }
        out.writeByte((byte) 0xFF);
        out.markWriter();
        return out;
    }

    private WebSocketOpcode requireOpcode(WebSocketFrame frame) {
        WebSocketOpcode opcode = frame.opcode();
        if (opcode == null) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "WebSocket frame opcode must not be null.");
        }
        return opcode;
    }
}
