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
import net.hasor.neta.codec.http.websocket.extension.WebSocketRuntimeExtension;

/**
 * Encodes {@link WebSocketFrame} objects into upgraded HTTP payload.
 * <p>
 * Function:
 * <pre>
 *   serialize frame header and payload
 *   apply RFC 6455 or V0 framing rules
 *   emit transparent HttpByteBuf for the HTTP codec
 * </pre>
 * <p>
 * pipeline view:
 * <pre>
 *   WebSocketFrame -> WebSocketFrameEncoder -> HttpByteBuf -> HTTP codec -> socket bytes
 * </pre>
 * <p>
 * Typical usage:
 * <pre>
 *   ctx.addLastEncoder("ws-frame", new WebSocketFrameEncoder());
 *   ctx.addLast("http", new HttpClientDuplexe());
 * </pre>
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
            WebSocketVersion version = WebSocketVersion.of(wsContext.version());
            if (version != null) {
                return version;
            }
        }

        return this.defaultVersion;
    }

    // RFC 6455 encoding (V7, V8, V13)

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<WebSocketFrame> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        WebSocketVersion version = resolveVersion(context);
        while (src.hasMore()) {
            WebSocketFrame inputFrame = src.takeMessage();
            if (inputFrame == null) {
                continue;
            }

            WebSocketFrame frame = inputFrame;
            try {
                frame = this.applyOutboundExtensions(context, inputFrame);
                ByteBuf encoded;
                if (version.isRfc6455Framing()) {
                    encoded = encodeRfc6455(context, frame);
                } else {
                    encoded = encodeHixie76(context, frame);
                }

                dst.offerMessage(new DefaultHttpByteBuf(encoded));
            } finally {
                if (frame != inputFrame) {
                    inputFrame.release();
                }
                frame.release();
            }
        }

        return ProtoStatus.Next;
    }

    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        this.resetRuntimeExtensions(context, false);
        long channelID = context.getChannel().getChannelId();
        if (context.getConfig().isPrintLog()) {
            logger.warn("[WS-ENC] channel=" + channelID + " encoder error, stateless reset. cause=" + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } else {
            logger.warn("[WS-ENC] channel=" + channelID + " encoder error, stateless reset. cause=" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        this.resetRuntimeExtensions(context, true);
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

        this.validateNegotiatedRsv(context, frame);

        if (opcode == WebSocketOpcode.PING || opcode == WebSocketOpcode.PONG || opcode == WebSocketOpcode.CLOSE) {
            WebSocketUtils.validateControlFrame(frame, wsContext != null && wsContext.isClient());
        }
    }

    private WebSocketFrame applyOutboundExtensions(ProtoContext context, WebSocketFrame frame) {
        WebSocketContextImpl wsContext = resolveRuntimeContext(context);
        if (wsContext == null) {
            return frame;
        }

        WebSocketFrame current = frame;
        for (WebSocketRuntimeExtension runtimeExtension : wsContext.runtimeList()) {
            WebSocketFrame encoded = runtimeExtension.encodeFrame(context, current);
            if (encoded != current) {
                current.release();
                current = encoded;
            }
        }

        return current;
    }

    private void validateNegotiatedRsv(ProtoContext context, WebSocketFrame frame) {
        if (!frame.isRsv1() && !frame.isRsv2() && !frame.isRsv3()) {
            return;
        }

        WebSocketContextImpl wsContext = resolveRuntimeContext(context);
        if (wsContext != null) {
            for (WebSocketRuntimeExtension runtimeExtension : wsContext.runtimeList()) {
                if (runtimeExtension.handlesOutboundFrame(frame)) {
                    return;
                }
            }
        }

        throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "RSV bits require a negotiated websocket extension.");
    }

    private WebSocketContext resolveHandshakeContext(ProtoContext context) {
        WebSocketContext wsContext = context.context(WebSocketContext.class);
        if (wsContext != null && wsContext.isReady()) {
            return wsContext;
        }

        wsContext = context.rootContext(WebSocketContext.class);
        if (wsContext != null && wsContext.isReady()) {
            return wsContext;
        }
        return null;
    }

    private WebSocketContextImpl resolveRuntimeContext(ProtoContext context) {
        WebSocketContext wsContext = resolveHandshakeContext(context);
        if (wsContext instanceof WebSocketContextImpl) {
            return (WebSocketContextImpl) wsContext;
        }

        return null;
    }

    private void resetRuntimeExtensions(ProtoContext context, boolean close) {
        WebSocketContextImpl wsContext = resolveRuntimeContext(context);
        if (wsContext == null) {
            return;
        }

        for (WebSocketRuntimeExtension runtimeExtension : wsContext.runtimeList()) {
            if (close) {
                runtimeExtension.close();
            } else {
                runtimeExtension.reset();
            }
        }
    }
}
