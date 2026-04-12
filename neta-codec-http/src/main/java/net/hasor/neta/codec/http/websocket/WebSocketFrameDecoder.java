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
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoExceptionHolder;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoQueue;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.HttpByteBuf;
import net.hasor.neta.codec.http.HttpContent;
import net.hasor.neta.codec.http.HttpObject;

/**
 * Decode upgraded HTTP payloads into {@link WebSocketFrame} objects.
 * <p>
 * Main responsibilities:
 * <pre>
 *   read transparent {@link HttpByteBuf} payloads
 *   parse the WebSocket frame header and payload
 *   emit {@link WebSocketFrame} objects
 * </pre>
 * <p>
 * Pipeline view:
 * <pre>
 *   socket bytes -> HTTP codec -> HttpByteBuf -> WebSocketFrameDecoder -> WebSocketFrame
 * </pre>
 * <p>
 * Typical usage:
 * <pre>
 *   ctx.addLast("http", new HttpServerDuplexe());
 *   ctx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
 *   ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder());
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class WebSocketFrameDecoder implements ProtoHandler<HttpObject, WebSocketFrame> {
    private static final Logger              logger           = Logger.getLogger(WebSocketFrameDecoder.class);
    private static final int                 XOR_SCRATCH_SIZE = 4096;
    private final        byte[]              maskKeyBuf       = new byte[4];
    private final        byte[]              headerBuf        = new byte[14]; // 2 base + 8 ext-len + 4 mask-key
    private final        byte[]              xorScratch       = new byte[XOR_SCRATCH_SIZE];
    private final        WebSocketVersion    defaultVersion;
    private final        boolean             detectVersion;
    private final        int                 maxPayloadChunkLength;
    private final        ProtoQueue<ByteBuf> payloadQueue     = new ProtoQueue<>(-1);
    private              ByteBuf             accumulator;
    private              Rfc6455PayloadState streamingState;
    private              long                currentStreamId;

    /**
     * Streaming state used when a large RFC 6455 payload is emitted as multiple
     * frame slices.
     */
    private static final class Rfc6455PayloadState {
        /** Opcode of the original wire frame being streamed. */
        private final WebSocketOpcode opcode;
        /** Whether the original wire frame carries FIN. */
        private final boolean         finalFragment;
        /** Cached RSV1 bit from the wire frame header. */
        private final boolean         rsv1;
        /** Cached RSV2 bit from the wire frame header. */
        private final boolean         rsv2;
        /** Cached RSV3 bit from the wire frame header. */
        private final boolean         rsv3;
        /** Whether the original frame is masked. */
        private final boolean         masked;
        /** Masking key copied from the original header when MASK is present. */
        private final byte[]          maskKey;
        /** Total payload length of the original wire frame. */
        private final long            payloadLength;
        /** Remaining bytes still to be emitted. */
        private       long            remainingPayloadLength;
        /** Payload bytes that have already been emitted. */
        private       long            emittedPayloadLength;

        private Rfc6455PayloadState(WebSocketOpcode opcode, boolean finalFragment, boolean rsv1, boolean rsv2, boolean rsv3, boolean masked, byte[] maskKey, long payloadLength) {
            this.opcode = opcode;
            this.finalFragment = finalFragment;
            this.rsv1 = rsv1;
            this.rsv2 = rsv2;
            this.rsv3 = rsv3;
            this.masked = masked;
            this.maskKey = maskKey;
            this.payloadLength = payloadLength;
            this.remainingPayloadLength = payloadLength;
            this.emittedPayloadLength = 0L;
        }
    }

    /**
     * Create a decoder for the specified WebSocket protocol version.
     * @param version WebSocket version
     */
    public WebSocketFrameDecoder(WebSocketVersion version) {
        this(version, Integer.MAX_VALUE);
    }

    /**
     * Create a decoder for the specified WebSocket version and maximum payload
     * chunk length.
     * @param version WebSocket version
     * @param maxPayloadChunkLength maximum length of emitted payload slices
     */
    public WebSocketFrameDecoder(WebSocketVersion version, int maxPayloadChunkLength) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        if (maxPayloadChunkLength <= 0) {
            throw new IllegalArgumentException("maxPayloadChunkLength must be greater than 0.");
        }

        this.defaultVersion = version;
        this.detectVersion = false;
        this.maxPayloadChunkLength = maxPayloadChunkLength;
    }

    /**
     * Create a decoder with automatic version detection, preferring the
     * negotiated handshake version and falling back to RFC 6455 version 13.
     */
    public WebSocketFrameDecoder() {
        this(Integer.MAX_VALUE);
    }

    /**
     * Create a decoder with automatic version detection and limit the maximum
     * emitted chunk length for large RFC 6455 payloads.
     * @param maxPayloadChunkLength maximum length of emitted payload slices
     */
    public WebSocketFrameDecoder(int maxPayloadChunkLength) {
        if (maxPayloadChunkLength <= 0) {
            throw new IllegalArgumentException("maxPayloadChunkLength must be greater than 0.");
        }

        this.defaultVersion = WebSocketVersion.V13;
        this.detectVersion = true;
        this.maxPayloadChunkLength = maxPayloadChunkLength;
    }

    private WebSocketVersion resolveVersion(ProtoContext context) {
        if (!this.detectVersion) {
            return this.defaultVersion;
        }

        WebSocketContext wsContext = WebSocketRegistry.resolve(context);
        if (wsContext != null) {
            WebSocketVersion detectedVersion = WebSocketVersion.of(wsContext.version());
            if (detectedVersion != null) {
                return detectedVersion;
            }
        }

        return this.defaultVersion;
    }

    /**
     * Decode inbound HTTP payload objects and emit WebSocket frames.
     */
    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<WebSocketFrame> dst) throws Throwable {
        while (src.hasMore()) {
            HttpObject obj = src.takeMessage();
            if (obj == null) {
                continue;
            }

            try {
                if (obj.streamId() > 0) {
                    this.currentStreamId = obj.streamId();
                }
                ByteBuf content = rawContent(obj);
                if (content != null && content.readableBytes() > 0) {
                    this.payloadQueue.offerMessage(content.retain());
                }
            } finally {
                obj.release();
            }
        }

        if (this.payloadQueue.queueSize() > 0) {
            this.accumulator = ByteBufUtils.queueBuffer(this.payloadQueue);
        }

        if (this.accumulator != null && this.accumulator.readableBytes() > 0) {
            WebSocketVersion version = resolveVersion(context);
            try {
                if (version.isRfc6455Framing()) {
                    while (decodeRfc6455Frame(context, dst)) { /* loop */ }
                } else {
                    while (decodeHixie76Frame(context, dst)) { /* loop */ }
                }
                this.accumulator.markReader();
            } finally {
                this.accumulator.free();
                this.accumulator = null;
            }
        }

        return ProtoStatus.Next;
    }

    private static ByteBuf rawContent(HttpObject obj) {
        if (obj instanceof HttpByteBuf) {
            return ((HttpByteBuf) obj).content();
        }
        if (obj instanceof HttpContent) {
            return ((HttpContent) obj).content();
        }
        throw new ClassCastException(obj.getClass().getName() + " cannot be cast to HttpByteBuf or HttpContent");
    }

    /**
     * Handle decoding errors and reset internal state.
     */
    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        this.resetAccumulator();
        this.streamingState = null;

        long channelID = context.getChannel().getChannelId();
        if (context.getConfig().isPrintLog()) {
            logger.warn("[WS-DEC] channel=" + channelID + " decoder error, frame state reset. cause=" + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } else {
            logger.warn("[WS-DEC] channel=" + channelID + " decoder error, frame state reset. cause=" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return ProtoStatus.Next;
    }

    /**
     * Release buffered state when the decoder is closed.
     */
    @Override
    public void onClose(ProtoContext context) {
        this.resetAccumulator();
        this.streamingState = null;
    }

    // =========================================================================
    // RFC 6455 framing (V7, V8, V13)
    // =========================================================================

    private boolean decodeRfc6455Frame(ProtoContext context, ProtoSndQueue<WebSocketFrame> dst) {
        if (this.streamingState != null) {
            return emitRfc6455PayloadSlice(context, dst);
        }

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
        boolean rsv1 = (byte0 & 0x40) != 0;
        boolean rsv2 = (byte0 & 0x20) != 0;
        boolean rsv3 = (byte0 & 0x10) != 0;
        int opcodeVal = byte0 & 0x0F;
        boolean masked = (byte1 & 0x80) != 0;
        long payloadLen = byte1 & 0x7F;

        this.validateMasking(context, masked);

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
            if ((hdr[2] & 0x80) != 0) {
                throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "most significant bit of 64-bit websocket payload length must be 0.");
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

        long totalNeededLong = headerSize + payloadLen;
        if (totalNeededLong > Integer.MAX_VALUE) {
            if (!shouldStreamPayload(opcodeVal, payloadLen)) {
                throw new WebSocketProtocolViolationException(WebSocketCode.MESSAGE_TOO_BIG, "websocket frame exceeds supported max length: " + totalNeededLong);
            }
        }

        if (!shouldStreamPayload(opcodeVal, payloadLen)) {
            int totalNeeded = (int) totalNeededLong;
            if (readable < totalNeeded) {
                return false;
            }
        } else if (readable < headerSize) {
            return false;
        }

        // Skip header (without mask key portion)
        this.accumulator.skipReadableBytes(headerSize - (masked ? 4 : 0));

        byte[] maskKey = null;
        if (masked) {
            this.accumulator.readBytes(this.maskKeyBuf, 0, 4);
            maskKey = this.maskKeyBuf;
        }

        WebSocketOpcode opcode = WebSocketOpcode.of(opcodeVal);
        if (opcode == null) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "unsupported WebSocket opcode: " + opcodeVal);
        }

        if (shouldStreamPayload(opcodeVal, payloadLen)) {
            this.streamingState = new Rfc6455PayloadState(opcode, fin, rsv1, rsv2, rsv3, masked, masked ? new byte[] { this.maskKeyBuf[0], this.maskKeyBuf[1], this.maskKeyBuf[2], this.maskKeyBuf[3] } : null, payloadLen);
            return emitRfc6455PayloadSlice(context, dst);
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

        byte[] frameMaskKey = masked ? new byte[] { this.maskKeyBuf[0], this.maskKeyBuf[1], this.maskKeyBuf[2], this.maskKeyBuf[3] } : null;
        WebSocketFrame frame;
        switch (opcode) {
            case TEXT:
                frame = WebSocketFrame.create(WebSocketOpcode.TEXT, fin, rsv1, rsv2, rsv3, masked, frameMaskKey, contentBuf, len);
                break;
            case BINARY:
                frame = WebSocketFrame.create(WebSocketOpcode.BINARY, fin, rsv1, rsv2, rsv3, masked, frameMaskKey, contentBuf, len);
                break;
            case CONTINUATION:
                frame = WebSocketFrame.create(WebSocketOpcode.CONTINUATION, fin, rsv1, rsv2, rsv3, masked, frameMaskKey, contentBuf, len);
                break;
            case PING:
                frame = WebSocketFrame.create(WebSocketOpcode.PING, true, rsv1, rsv2, rsv3, masked, frameMaskKey, contentBuf, len);
                break;
            case PONG:
                frame = WebSocketFrame.create(WebSocketOpcode.PONG, true, rsv1, rsv2, rsv3, masked, frameMaskKey, contentBuf, len);
                break;
            case CLOSE:
                frame = WebSocketFrame.create(WebSocketOpcode.CLOSE, true, rsv1, rsv2, rsv3, masked, frameMaskKey, contentBuf, len);
                break;
            default:
                if (contentBuf != ByteBuf.EMPTY) {
                    contentBuf.release();
                }
                throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "unsupported WebSocket opcode: " + opcodeVal);
        }

        frame.streamId(this.currentStreamId);
        dst.offerMessage(frame);
        return true;
    }

    private boolean shouldStreamPayload(int opcodeValue, long payloadLen) {
        WebSocketOpcode opcode = WebSocketOpcode.of(opcodeValue);
        if (opcode == null) {
            return false;
        }

        if (opcode == WebSocketOpcode.PING || opcode == WebSocketOpcode.PONG || opcode == WebSocketOpcode.CLOSE) {
            return false;
        }

        return payloadLen > this.maxPayloadChunkLength || payloadLen > Integer.MAX_VALUE;
    }

    private boolean emitRfc6455PayloadSlice(ProtoContext context, ProtoSndQueue<WebSocketFrame> dst) {
        if (this.streamingState == null) {
            return false;
        }
        if (this.streamingState.remainingPayloadLength <= 0L) {
            this.streamingState = null;
            return false;
        }
        if (this.accumulator.readableBytes() <= 0) {
            return false;
        }

        int chunkLength = (int) Math.min(this.accumulator.readableBytes(), Math.min(this.maxPayloadChunkLength, this.streamingState.remainingPayloadLength));
        ByteBuf contentBuf;
        if (chunkLength == 0) {
            contentBuf = ByteBuf.EMPTY;
        } else if (this.streamingState.masked) {
            contentBuf = context.byteBufAllocator().buffer(chunkLength, Integer.MAX_VALUE);
            int remaining = chunkLength;
            int emittedOffset = 0;
            while (remaining > 0) {
                int chunk = Math.min(remaining, XOR_SCRATCH_SIZE);
                this.accumulator.readBytes(this.xorScratch, 0, chunk);
                long payloadOffset = this.streamingState.emittedPayloadLength + emittedOffset;
                for (int i = 0; i < chunk; i++) {
                    this.xorScratch[i] ^= this.streamingState.maskKey[(int) ((payloadOffset + i) & 3L)];
                }
                contentBuf.writeBytes(this.xorScratch, 0, chunk);
                emittedOffset += chunk;
                remaining -= chunk;
            }
            contentBuf.markWriter();
        } else {
            contentBuf = context.byteBufAllocator().buffer(chunkLength, Integer.MAX_VALUE);
            this.accumulator.readBuffer(contentBuf, chunkLength);
            contentBuf.markWriter();
        }

        boolean firstSlice = this.streamingState.emittedPayloadLength == 0L;
        boolean lastSlice = this.streamingState.remainingPayloadLength == chunkLength;
        WebSocketOpcode emittedOpcode = firstSlice ? this.streamingState.opcode : WebSocketOpcode.CONTINUATION;
        boolean finalFragment = lastSlice && this.streamingState.finalFragment;
        byte[] maskKey = this.streamingState.masked ? new byte[] { this.streamingState.maskKey[0], this.streamingState.maskKey[1], this.streamingState.maskKey[2], this.streamingState.maskKey[3] } : null;
        WebSocketFrame frame = WebSocketFrame.create(emittedOpcode, finalFragment, firstSlice && this.streamingState.rsv1, firstSlice && this.streamingState.rsv2, firstSlice && this.streamingState.rsv3, this.streamingState.masked, maskKey, contentBuf, chunkLength);
        frame.streamId(this.currentStreamId);

        this.streamingState.emittedPayloadLength += chunkLength;
        this.streamingState.remainingPayloadLength -= chunkLength;
        if (this.streamingState.remainingPayloadLength == 0L) {
            this.streamingState = null;
        }

        dst.offerMessage(frame);
        return true;
    }

    private void validateMasking(ProtoContext context, boolean masked) {
        WebSocketContext wsContext = resolveHandshakeContext(context);
        if (wsContext == null || !WebSocketVersion.of(wsContext.version()).isRfc6455Framing()) {
            return;
        }
        if (wsContext.isServer() && !masked) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "client-to-server websocket frames must be masked.");
        }
        if (wsContext.isClient() && masked) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "server-to-client websocket frames must not be masked.");
        }
    }

    private WebSocketContext resolveHandshakeContext(ProtoContext context) {
        return WebSocketUtils.readyContext(context);
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
                WebSocketFrame closeFrame = WebSocketUtils.closeFrame(false, null, ByteBuf.EMPTY);
                closeFrame.streamId(this.currentStreamId);
                dst.offerMessage(closeFrame);
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

    private void resetAccumulator() {
        if (this.accumulator != null) {
            this.accumulator.free();
            this.accumulator = null;
        }
        this.payloadQueue.skipMessage(this.payloadQueue.queueSize());
        this.streamingState = null;
    }
}