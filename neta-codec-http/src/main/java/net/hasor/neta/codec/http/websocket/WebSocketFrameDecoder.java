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

    private ByteBuf accumulator;

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<WebSocketFrame> dst) throws Throwable {
        if (accumulator == null) {
            accumulator = context.byteBufAllocator().buffer(256, Integer.MAX_VALUE);
        }

        // Append all available chunks into the accumulator
        while (src.hasMore()) {
            ByteBuf chunk = src.takeMessage();
            if (chunk != null && chunk.readableBytes() > 0) {
                accumulator.writeBuffer(chunk, chunk.readableBytes());
            }
        }
        accumulator.markWriter();

        // Attempt to decode as many complete frames as possible
        boolean decoded = true;
        while (decoded) {
            decoded = decodeFrame(context, dst);
        }

        // Compact consumed bytes
        if (accumulator.readableBytes() == 0) {
            accumulator.clear();
        } else {
            accumulator.discardReadBytes();
        }

        return ProtoStatus.Next;
    }

    /**
     * Attempts to decode one complete frame from the accumulator.
     * @return true if a complete frame was decoded and emitted
     */
    private boolean decodeFrame(ProtoContext context, ProtoSndQueue<WebSocketFrame> dst) {
        // Need at least 2 header bytes
        if (accumulator.readableBytes() < 2) {
            return false;
        }

        // Peek at header bytes without advancing read index
        int readerStart = accumulator.readerIndex();

        byte byte0 = accumulator.getByte(readerStart);
        byte byte1 = accumulator.getByte(readerStart + 1);

        boolean fin = (byte0 & 0x80) != 0;
        int opcodeVal = byte0 & 0x0F;
        boolean masked = (byte1 & 0x80) != 0;
        long payloadLen = byte1 & 0x7F;

        int headerSize = 2;

        // Extended payload length
        if (payloadLen == 126) {
            if (accumulator.readableBytes() < 4) {
                return false;
            }
            payloadLen = ((accumulator.getByte(readerStart + 2) & 0xFF) << 8) | (accumulator.getByte(readerStart + 3) & 0xFF);
            headerSize = 4;
        } else if (payloadLen == 127) {
            if (accumulator.readableBytes() < 10) {
                return false;
            }
            payloadLen = 0;
            for (int i = 0; i < 8; i++) {
                payloadLen = (payloadLen << 8) | (accumulator.getByte(readerStart + 2 + i) & 0xFF);
            }
            headerSize = 10;
        }

        // Masking key (4 bytes)
        if (masked) {
            headerSize += 4;
        }

        int totalNeeded = (int) (headerSize + payloadLen);
        if (accumulator.readableBytes() < totalNeeded) {
            return false;
        }

        // All bytes are available – consume the frame
        accumulator.skipReadableBytes(2); // skip byte0, byte1
        if ((payloadLen >= 126 && payloadLen <= 0xFFFF && (byte1 & 0x7F) == 126)) {
            accumulator.skipReadableBytes(2); // skip extended 16-bit length
        } else if ((byte1 & 0x7F) == 127) {
            accumulator.skipReadableBytes(8); // skip extended 64-bit length
        }

        byte[] maskKey = null;
        if (masked) {
            maskKey = new byte[4];
            accumulator.readBytes(maskKey, 0, 4);
        }

        int len = (int) payloadLen;
        ByteBuf contentBuf;

        if (len == 0) {
            contentBuf = ByteBuf.EMPTY;
        } else if (masked) {
            // For masked frames: read into byte[], unmask, then write to ByteBuf
            byte[] payload = new byte[len];
            accumulator.readBytes(payload, 0, len);

            // Unrolled XOR unmask
            int i = 0;
            int len4 = len & ~3;
            for (; i < len4; i += 4) {
                payload[i] ^= maskKey[0];
                payload[i + 1] ^= maskKey[1];
                payload[i + 2] ^= maskKey[2];
                payload[i + 3] ^= maskKey[3];
            }
            for (; i < len; i++) {
                payload[i] ^= maskKey[i & 3];
            }

            contentBuf = context.byteBufAllocator().buffer(len, Integer.MAX_VALUE);
            contentBuf.writeBytes(payload, 0, len);
            contentBuf.markWriter();
        } else {
            // For unmasked frames: direct buffer-to-buffer copy (avoids intermediate byte[])
            contentBuf = context.byteBufAllocator().buffer(len, Integer.MAX_VALUE);
            accumulator.readBuffer(contentBuf, len);
            contentBuf.markWriter();
        }

        WebSocketOpcode opcode = WebSocketOpcode.of(opcodeVal);
        WebSocketFrame frame = new DefaultWebSocketFrame(opcode, fin, masked, masked ? maskKey : null, contentBuf);
        dst.offerMessage(frame);
        return true;
    }

    @Override
    public void onClose(ProtoContext context) {
        if (accumulator != null) {
            accumulator.free();
            accumulator = null;
        }
    }
}
