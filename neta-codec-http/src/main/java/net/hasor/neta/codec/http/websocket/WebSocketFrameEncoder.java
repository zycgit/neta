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
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoExceptionHolder;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.DefaultHttpByteBuf;
import net.hasor.neta.codec.http.HttpObject;

/**
 * Encode {@link WebSocketFrame} into upgraded HTTP payload objects.
 * <p>
 * Main responsibilities:
 * <pre>
 *   serialize the frame header and payload
 *   apply RFC 6455 or V0 framing rules
 *   produce transparent {@code HttpByteBuf} objects for the HTTP codec
 * </pre>
 * <p>
 * Pipeline view:
 * <pre>
 *   WebSocketFrame -> WebSocketFrameEncoder -> HttpByteBuf -> HTTP codec -> socket bytes
 * </pre>
 * <p>
 * Typical usage:
 * <pre>
 *   ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder());
 *   ctx.addLast("http", new HttpClientDuplexe());
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class WebSocketFrameEncoder implements ProtoHandler<WebSocketFrame, HttpObject> {
    private static final Logger              logger           = Logger.getLogger(WebSocketFrameEncoder.class);
    private static final int                 XOR_SCRATCH_SIZE = 4096;
    private static final ThreadLocal<byte[]> XOR_SCRATCH      = ThreadLocal.withInitial(() -> new byte[XOR_SCRATCH_SIZE]);
    private final        WebSocketVersion    defaultVersion;
    private final        boolean             detectVersion;

    /**
     * Create an encoder for the specified WebSocket protocol version.
     * @param version WebSocket version
     */
    public WebSocketFrameEncoder(WebSocketVersion version) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }

        this.defaultVersion = version;
        this.detectVersion = false;
    }

    /**
     * Create an encoder with automatic version detection, preferring the
     * negotiated handshake version and falling back to RFC 6455 version 13.
     */
    public WebSocketFrameEncoder() {
        this.defaultVersion = WebSocketVersion.V13;
        this.detectVersion = true;
    }

    private WebSocketVersion resolveVersion(ProtoContext context) {
        if (!this.detectVersion) {
            return this.defaultVersion;
        }

        WebSocketContext wsContext = WebSocketRegistry.resolve(context);
        if (wsContext != null) {
            WebSocketVersion version = WebSocketVersion.of(wsContext.version());
            if (version != null) {
                return version;
            }
        }

        return this.defaultVersion;
    }

    // RFC 6455 encoding (V7, V8, V13)

    /**
     * Encode outbound WebSocket frames into HTTP payload objects.
     */
    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<WebSocketFrame> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        WebSocketVersion version = resolveVersion(context);
        while (src.hasMore()) {
            WebSocketFrame inputFrame = src.takeMessage();
            if (inputFrame == null) {
                continue;
            }

            try {
                ByteBuf encoded;
                if (version.isRfc6455Framing()) {
                    encoded = encodeRfc6455(context, inputFrame);
                } else {
                    encoded = encodeHixie76(context, inputFrame);
                }

                dst.offerMessage(new DefaultHttpByteBuf(encoded, inputFrame.streamId()));
            } finally {
                inputFrame.release();
            }
        }

        return ProtoStatus.Next;
    }

    /**
     * Handle encoding errors. The encoder itself is stateless and does not keep
     * buffered frame state.
     */
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

    /**
     * Close the encoder. No additional cleanup is required because no buffered
     * state is retained.
     */
    @Override
    public void onClose(ProtoContext context) {
    }

    // Hixie-76 encoding (V0)

    /**
     * Write a variable-length integer in Hixie-76 style using big-endian 7-bit
     * groups, with the high bit indicating continuation.
     * @param out target buffer
     * @param length length value
     */
    private static void writeVariableLength(ByteBuf out, int length) {
        // Determine how many 7-bit groups are needed.
        int groups = 1;
        int tmp = length;
        while (tmp > 0x7F) {
            groups++;
            tmp >>= 7;
        }

        // Write from the most-significant group to the least-significant group.
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
        boolean masked = frame.isMasked();
        byte[] maskKey = masked ? frame.maskingKey() : null;

        validateRfc6455Frame(context, frame, opcode, masked, maskKey);

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
        byte byte0 = (byte) ((frame.isFinalFragment() ? 0x80 : 0x00) |//
                (frame.isRsv1() ? 0x40 : 0x00) |                      //
                (frame.isRsv2() ? 0x20 : 0x00) |                      //
                (frame.isRsv3() ? 0x10 : 0x00) |                      //
                (opcode.code() & 0x0F));
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

    private void validateRfc6455Frame(ProtoContext context, WebSocketFrame frame, WebSocketOpcode opcode, boolean masked, byte[] maskKey) {
        if (masked && (maskKey == null || maskKey.length != 4)) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "masked websocket frame requires a 4-byte masking key.");
        }

        WebSocketContext wsContext = resolveHandshakeContext(context);
        if (wsContext != null) {
            if (wsContext.isClient() && !masked) {
                throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "client-to-server websocket frames must be masked.");
            }
            if (wsContext.isServer() && masked) {
                throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "server-to-client websocket frames must not be masked.");
            }
        }

        if (opcode == WebSocketOpcode.PING || opcode == WebSocketOpcode.PONG || opcode == WebSocketOpcode.CLOSE) {
            WebSocketUtils.validateControlFrame(frame, wsContext != null && wsContext.isClient());
        }
    }

    private WebSocketContext resolveHandshakeContext(ProtoContext context) {
        return WebSocketUtils.readyContext(context);
    }
}
