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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;

/**
 * Decodes raw {@link ByteBuf} bytes into {@link WebSocketFrame} objects following
 * <a href="https://tools.ietf.org/html/rfc6455#section-5.2">RFC 6455 §5.2</a>.
 * <h3>Wire format (per frame)</h3>
 * <pre>
 *  Byte 0: FIN (1-bit) | RSV1-3 (3-bits) | Opcode (4-bits)
 *  Byte 1: MASK (1-bit) | Payload length (7-bits)
 *    if payloadLen  == 126: next 2 bytes = true 16-bit length
 *    if payloadLen  == 127: next 8 bytes = true 64-bit length
 *  Masking-key: 4 bytes (only if MASK bit is set)
 *  Payload: payloadLen bytes (XOR-decoded with masking key if masked)
 * </pre>
 * <p>Usage in a pipeline (server-side, receiving masked frames from client):
 * <pre>
 *   ctx.addLastDecoder("ws-frame", new WebSocketFrameDecoder());
 * </pre>
 */
public class WebSocketFrameDecoder implements ProtoHandler<ByteBuf, WebSocketFrame> {
    private static final int     XOR_SCRATCH_SIZE = 4096;
    private final        byte[]  maskKeyBuf       = new byte[4];
    private final        byte[]  headerBuf        = new byte[14]; // 2 base + 8 ext-len + 4 mask-key
    private final        byte[]  xorScratch       = new byte[XOR_SCRATCH_SIZE];
    private              ByteBuf accumulator;

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<WebSocketFrame> dst) throws Throwable {
        // Create a zero-copy view over all queued ByteBuf data
        if (this.accumulator != null) {
            this.accumulator.free();
        }
        this.accumulator = ByteBufUtils.queueBuffer(src);

        // Attempt to decode as many complete frames as possible
        boolean decoded = true;
        while (decoded) {
            decoded = decodeFrame(context, dst);
        }

        // Consume fully-read ByteBuf messages from the queue
        this.accumulator.markReader();

        return ProtoStatus.Next;
    }

    /**
     * Attempts to decode one complete frame from the accumulator.
     * @return true if a complete frame was decoded and emitted
     */
    private boolean decodeFrame(ProtoContext context, ProtoSndQueue<WebSocketFrame> dst) {
        int readable = this.accumulator.readableBytes();
        // Need at least 2 header bytes
        if (readable < 2) {
            return false;
        }

        // Bulk-read header bytes (max 14: 2 base + 8 extended-len + 4 mask-key)
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

        // Extended payload length
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

        // Masking key (4 bytes)
        if (masked) {
            headerSize += 4;
        }

        int totalNeeded = (int) (headerSize + payloadLen);
        if (readable < totalNeeded) {
            return false;
        }

        // All bytes are available – skip the entire header at once
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
            // For masked frames: read and unmask payload in fixed-size chunks
            // to avoid allocating a payload-sized byte array
            contentBuf = context.byteBufAllocator().buffer(len, Integer.MAX_VALUE);
            int remaining = len;
            while (remaining > 0) {
                int chunk = Math.min(remaining, XOR_SCRATCH_SIZE);
                this.accumulator.readBytes(this.xorScratch, 0, chunk);
                // Unrolled XOR unmask (XOR_SCRATCH_SIZE is multiple of 4, so alignment is guaranteed per chunk)
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
            // For unmasked frames: direct buffer-to-buffer copy (avoids intermediate byte[])
            contentBuf = context.byteBufAllocator().buffer(len, Integer.MAX_VALUE);
            this.accumulator.readBuffer(contentBuf, len);
            contentBuf.markWriter();
        }

        WebSocketOpcode opcode = WebSocketOpcode.of(opcodeVal);
        // Copy maskKey since maskKeyBuf is reused across frames
        byte[] frameMaskKey = masked ? new byte[] { this.maskKeyBuf[0], this.maskKeyBuf[1], this.maskKeyBuf[2], this.maskKeyBuf[3] } : null;
        WebSocketFrame frame = new DefaultWebSocketFrame(opcode, fin, masked, frameMaskKey, contentBuf);
        dst.offerMessage(frame);
        return true;
    }

    @Override
    public void onClose(ProtoContext context) {
        if (this.accumulator != null) {
            this.accumulator.free();
            this.accumulator = null;
        }
    }
}
