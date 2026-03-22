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
import net.hasor.neta.bytebuf.CompositeByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpByteBuf;
import net.hasor.neta.codec.http.HttpObject;

/**
 * Decodes transparent-mode HTTP payload bytes into {@link WebSocketFrame} objects.
 * <p>
 * This decoder is used only after the HTTP/1.x upgrade handshake has completed and the
 * HTTP codec has switched into transparent mode. At that point inbound network bytes are
 * wrapped as {@link HttpByteBuf}, and this handler turns those transport bytes into
 * frame-level WebSocket objects.
 * <p>
 * Preferred usage for a bidirectional pipeline is {@link WebSocketFrameDuplexer}.
 * Install this decoder directly only when the receive direction must be assembled
 * independently from the send direction.
 * When the no-arg constructor is used behind {@link WebSocketHandshakeDuplexer},
 * the decoder first tries to resolve the negotiated version from {@link WebSocketContext}
 * and falls back to RFC 6455 version 13 when no handshake context is available.
 * <p>
 * Typical usage in a manually assembled inbound-only pipeline:
 * <pre>
 *   ctx.addLast("http", new HttpServerDuplexe());
 *   ctx.addLast("ws-handshake", new WebSocketHandshakeDuplexer(true, WebSocketVersion.V13));
 *   ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder());
 *   ctx.addLastDecoder("ws-inbound", new WebSocketInboundHandler());
 * </pre>
 * <p>
 * pipeline view:
 * <pre>
 *   socket bytes
 *      -> HttpServerDuplexe / HttpClientDuplexe
 *      -> transparent HttpByteBuf
 *      -> WebSocketFrameDecoder
 *      -> WebSocketFrame
 *      -> next inbound handler
 * </pre>
 * <p>
 * Version support:
 * <ul>
 *   <li>{@link WebSocketVersion#V0}: Hixie-76 framing.</li>
 *   <li>{@link WebSocketVersion#V7}, {@link WebSocketVersion#V8}, {@link WebSocketVersion#V13}: RFC 6455 framing family.</li>
 * </ul>
 * <p>
 * This decoder only accepts transparent-mode {@link HttpByteBuf} input. Any other
 * {@link HttpObject} type is unsupported and will be handled by the decoder error path,
 * which resets the internal frame state for subsequent input.
 */
public class WebSocketFrameDecoder implements ProtoHandler<HttpObject, WebSocketFrame> {
    private static final Logger           logger           = Logger.getLogger(WebSocketFrameDecoder.class);
    private static final int              XOR_SCRATCH_SIZE = 4096;
    private final        byte[]           maskKeyBuf       = new byte[4];
    private final        byte[]           headerBuf        = new byte[14]; // 2 base + 8 ext-len + 4 mask-key
    private final        byte[]           xorScratch       = new byte[XOR_SCRATCH_SIZE];
    private final        WebSocketVersion defaultVersion;
    private final        boolean          detectVersion;
    private              CompositeByteBuf accumulator;

    /** Creates a decoder for the specified WebSocket protocol version. */
    public WebSocketFrameDecoder(WebSocketVersion version) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }

        this.defaultVersion = version;
        this.detectVersion = false;
    }

    /** Creates a decoder that first uses the negotiated handshake version and falls back to RFC 6455 (version 13). */
    public WebSocketFrameDecoder() {
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

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<WebSocketFrame> dst) throws Throwable {
        while (src.hasMore()) {
            HttpByteBuf obj = (HttpByteBuf) src.takeMessage();
            if (obj == null) {
                continue;
            }
            try {
                ByteBuf content = obj.content();
                if (content != null && content.readableBytes() > 0) {
                    if (this.accumulator == null) {
                        this.accumulator = new CompositeByteBuf(context.byteBufAllocator());
                    }
                    this.accumulator.addComponent(content);
                }
            } finally {
                obj.release();
            }
        }

        if (this.accumulator != null && this.accumulator.readableBytes() > 0) {
            WebSocketVersion version = resolveVersion(context);
            if (version.isRfc6455Framing()) {
                while (decodeRfc6455Frame(context, dst)) { /* loop */ }
            } else {
                while (decodeHixie76Frame(context, dst)) { /* loop */ }
            }
            this.accumulator.discardReadBytes();
        }

        return ProtoStatus.Next;
    }

    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        this.resetAccumulator();

        long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            logger.warn("[WS-DEC] channel=" + channelID + " decoder error, frame state reset. cause=" + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } else {
            logger.warn("[WS-DEC] channel=" + channelID + " decoder error, frame state reset. cause=" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return ProtoStatus.Next;
    }

    // =========================================================================
    // RFC 6455 framing (V7, V8, V13)
    // =========================================================================

    private boolean decodeRfc6455Frame(ProtoContext context, ProtoSndQueue<WebSocketFrame> dst) {
        int readable = this.accumulator.readableBytes();
        if (readable < 2) {
            return false;
        }

        int peekLen = Math.min(readable, 14);
        byte[] hdr = this.headerBuf;
        this.accumulator.getBytes(0, hdr, 0, peekLen);

        byte byte0 = hdr[0];
        byte byte1 = hdr[1];

        boolean fin = (byte0 & 0x80) != 0;
        int opcodeVal = byte0 & 0x0F;
        boolean masked = (byte1 & 0x80) != 0;
        long payloadLen = byte1 & 0x7F;

        int headerSize = 2;

        if (payloadLen == 126) {
            if (readable < 4) {
                return false;
            }
            payloadLen = ((hdr[2] & 0xFF) << 8) | (hdr[3] & 0xFF);
            headerSize = 4;
        } else if (payloadLen == 127) {
            if (readable < 10) {
                return false;
            }
            payloadLen = 0;
            for (int i = 0; i < 8; i++) {
                payloadLen = (payloadLen << 8) | (hdr[2 + i] & 0xFF);
            }
            headerSize = 10;
        }

        if (masked) {
            headerSize += 4;
        }

        int totalNeeded = (int) (headerSize + payloadLen);
        if (readable < totalNeeded) {
            return false;
        }

        // Skip header (without mask key portion)
        this.accumulator.skipReadableBytes(headerSize - (masked ? 4 : 0));

        byte[] maskKey = null;
        if (masked) {
            this.accumulator.readBytes(this.maskKeyBuf, 0, 4);
            maskKey = this.maskKeyBuf;
        }

        int len = (int) payloadLen;
        ByteBuf contentBuf;

        if (len == 0) {
            contentBuf = ByteBuf.EMPTY;
        } else if (masked) {
            contentBuf = context.byteBufAllocator().buffer(len, Integer.MAX_VALUE);
            int remaining = len;
            while (remaining > 0) {
                int chunk = Math.min(remaining, XOR_SCRATCH_SIZE);
                this.accumulator.readBytes(this.xorScratch, 0, chunk);
                int j = 0;
                int chunk4 = chunk & ~3;
                for (; j < chunk4; j += 4) {
                    this.xorScratch[j] ^= maskKey[0];
                    this.xorScratch[j + 1] ^= maskKey[1];
                    this.xorScratch[j + 2] ^= maskKey[2];
                    this.xorScratch[j + 3] ^= maskKey[3];
                }
                for (; j < chunk; j++) {
                    this.xorScratch[j] ^= maskKey[j & 3];
                }
                contentBuf.writeBytes(this.xorScratch, 0, chunk);
                remaining -= chunk;
            }
            contentBuf.markWriter();
        } else {
            contentBuf = context.byteBufAllocator().buffer(len, Integer.MAX_VALUE);
            this.accumulator.readBuffer(contentBuf, len);
            contentBuf.markWriter();
        }

        WebSocketOpcode opcode = WebSocketOpcode.of(opcodeVal);
        if (opcode == null) {
            if (contentBuf != ByteBuf.EMPTY) {
                contentBuf.release();
            }
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "unsupported WebSocket opcode: " + opcodeVal);
        }
        byte[] frameMaskKey = masked ? new byte[] { this.maskKeyBuf[0], this.maskKeyBuf[1], this.maskKeyBuf[2], this.maskKeyBuf[3] } : null;
        WebSocketFrame frame;
        switch (opcode) {
            case TEXT:
                frame = WebSocketUtils.textFrame(fin, masked, frameMaskKey, contentBuf);
                break;
            case BINARY:
                frame = WebSocketUtils.binaryFrame(fin, masked, frameMaskKey, contentBuf);
                break;
            case CONTINUATION:
                frame = WebSocketUtils.continuationFrame(fin, masked, frameMaskKey, contentBuf);
                break;
            case PING:
                frame = WebSocketUtils.pingFrame(masked, frameMaskKey, contentBuf);
                break;
            case PONG:
                frame = WebSocketUtils.pongFrame(masked, frameMaskKey, contentBuf);
                break;
            case CLOSE:
                frame = WebSocketUtils.closeFrame(masked, frameMaskKey, contentBuf);
                break;
            default:
                if (contentBuf != ByteBuf.EMPTY) {
                    contentBuf.release();
                }
                throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "unsupported WebSocket opcode: " + opcodeVal);
        }
        dst.offerMessage(frame);
        return true;
    }

    // =========================================================================
    // Hixie-76 framing (V0)
    // =========================================================================

    private boolean decodeHixie76Frame(ProtoContext context, ProtoSndQueue<WebSocketFrame> dst) {
        int readable = this.accumulator.readableBytes();
        if (readable < 1) {
            return false;
        }

        byte frameType = this.accumulator.getByte(0);

        if ((frameType & 0x80) != 0) {
            // High bit set: close frame (0xFF 0x00) or length-prefixed binary frame
            if (frameType == (byte) 0xFF) {
                // Close frame: 0xFF 0x00
                if (readable < 2) {
                    return false;
                }
                this.accumulator.skipReadableBytes(2);
                dst.offerMessage(WebSocketUtils.closeFrame(false, null, ByteBuf.EMPTY));
                return true;
            }

            // Length-prefixed binary: read variable-length integer
            int lengthBytes = 0;
            long payloadLen = 0;
            for (int i = 1; ; i++) {
                if (i >= readable) {
                    return false; // need more data for length field
                }
                byte b = this.accumulator.getByte(i);
                payloadLen = (payloadLen << 7) | (b & 0x7F);
                lengthBytes++;
                if ((b & 0x80) == 0) {
                    break; // last length byte
                }
            }

            int totalNeeded = 1 + lengthBytes + (int) payloadLen;
            if (readable < totalNeeded) {
                return false;
            }

            this.accumulator.skipReadableBytes(1 + lengthBytes);
            ByteBuf contentBuf;
            int len = (int) payloadLen;
            if (len == 0) {
                contentBuf = ByteBuf.EMPTY;
            } else {
                contentBuf = context.byteBufAllocator().buffer(len, Integer.MAX_VALUE);
                this.accumulator.readBuffer(contentBuf, len);
                contentBuf.markWriter();
            }
            dst.offerMessage(WebSocketUtils.binaryFrame(true, false, null, contentBuf));
            return true;
        }

        // Low byte (0x00): text frame — data runs until 0xFF sentinel
        // Scan for the 0xFF terminator
        int terminatorIdx = -1;
        for (int i = 1; i < readable; i++) {
            if (this.accumulator.getByte(i) == (byte) 0xFF) {
                terminatorIdx = i;
                break;
            }
        }
        if (terminatorIdx < 0) {
            return false; // incomplete text frame, need more data
        }

        // Skip the 0x00 start byte
        this.accumulator.skipReadableBytes(1);
        int textLen = terminatorIdx - 1;

        ByteBuf contentBuf;
        if (textLen == 0) {
            contentBuf = ByteBuf.EMPTY;
        } else {
            contentBuf = context.byteBufAllocator().buffer(textLen, Integer.MAX_VALUE);
            this.accumulator.readBuffer(contentBuf, textLen);
            contentBuf.markWriter();
        }
        // Skip the 0xFF terminator
        this.accumulator.skipReadableBytes(1);

        dst.offerMessage(WebSocketUtils.textFrame(true, false, null, contentBuf));
        return true;
    }

    @Override
    public void onClose(ProtoContext context) {
        this.resetAccumulator();
    }

    private void resetAccumulator() {
        if (this.accumulator != null) {
            this.accumulator.free();
            this.accumulator = null;
        }
    }
}