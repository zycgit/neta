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
import net.hasor.neta.bytebuf.CompositeByteBuf;
import net.hasor.neta.channel.*;

/**
 * Encodes {@link WebSocketFrame} objects to raw {@link ByteBuf} bytes following
 * <a href="https://tools.ietf.org/html/rfc6455#section-5.2">RFC 6455 §5.2</a>.
 * <h3>Wire format (per frame)</h3>
 * <pre>
 *  Byte 0: FIN (1-bit) | RSV1-3=000 (3-bits) | Opcode (4-bits)
 *  Byte 1: MASK (1-bit) | Payload length (7-bits)
 *    if payloadLen in [126..65535]: 2-byte extended length follows
 *    if payloadLen &gt; 65535:       8-byte extended length follows
 *  Masking-key: 4 bytes (only when mask=true in the frame)
 *  Payload: xor-masked if masking key is set, otherwise plain
 * </pre>
 * <p>Usage in a pipeline:
 * <pre>
 *   ctx.addLastEncoder("ws-frame-enc", new WebSocketFrameEncoder());
 * </pre>
 */
public class WebSocketFrameEncoder implements ProtoHandler<WebSocketFrame, ByteBuf> {

    private static final int                 XOR_SCRATCH_SIZE    = 4096;
    private static final int                 COMPOSITE_THRESHOLD = 4096;
    private static final ThreadLocal<byte[]> XOR_SCRATCH         = ThreadLocal.withInitial(() -> new byte[XOR_SCRATCH_SIZE]);

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<WebSocketFrame> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
        while (src.hasMore()) {
            WebSocketFrame frame = src.takeMessage();
            if (frame != null) {
                dst.offerMessage(encodeFrame(context, frame));
            }
        }
        return ProtoStatus.Next;
    }

    private ByteBuf encodeFrame(ProtoContext context, WebSocketFrame frame) {
        ByteBuf content = frame.content();
        int payloadLen = (content != null) ? content.readableBytes() : 0;
        boolean masked = frame.isMasked() && frame.maskingKey() != null;
        byte[] maskKey = masked ? frame.maskingKey() : null;

        // Calculate header size
        int headerSize = 2;
        if (payloadLen >= 126 && payloadLen <= 65535) {
            headerSize += 2;
        } else if (payloadLen > 65535) {
            headerSize += 8;
        }
        if (masked) {
            headerSize += 4;
        }

        boolean useComposite = !masked && payloadLen >= COMPOSITE_THRESHOLD;
        int allocSize = useComposite ? headerSize : (headerSize + payloadLen);
        ByteBuf out = context.byteBufAllocator().buffer(allocSize, Integer.MAX_VALUE);

        // Byte 0: FIN + opcode
        byte byte0 = (byte) ((frame.isFinalFragment() ? 0x80 : 0x00) | (frame.opcode().code() & 0x0F));
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
            // 8-byte big-endian length
            out.writeByte((byte) 0);
            out.writeByte((byte) 0);
            out.writeByte((byte) 0);
            out.writeByte((byte) 0);
            out.writeByte((byte) ((payloadLen >> 24) & 0xFF));
            out.writeByte((byte) ((payloadLen >> 16) & 0xFF));
            out.writeByte((byte) ((payloadLen >> 8) & 0xFF));
            out.writeByte((byte) (payloadLen & 0xFF));
        }

        // Masking key
        if (masked) {
            out.writeBytes(maskKey, 0, 4);
        }

        // Payload
        if (payloadLen > 0) {
            if (masked) {
                // Read payload in fixed-size chunks, apply XOR mask, write into output ByteBuf
                // This avoids allocating a payload-sized byte[] for large frames
                byte[] scratch = XOR_SCRATCH.get();
                int remaining = payloadLen;
                int srcOff = 0;
                while (remaining > 0) {
                    int chunk = Math.min(remaining, XOR_SCRATCH_SIZE);
                    content.getBytes(srcOff, scratch, 0, chunk);
                    // Unrolled XOR mask (XOR_SCRATCH_SIZE is multiple of 4, alignment guaranteed per chunk)
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
            } else if (useComposite) {
                // Zero-copy: compose header + content without copying payload data
                out.markWriter();
                CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
                composite.addComponent(out);
                composite.addComponent(content);
                content.release(); // transfer ownership to composite
                return composite;
            } else {
                // Direct copy for small payloads (cheaper than composite overhead)
                content.getBuffer(content.readerIndex(), out, payloadLen);
            }
        }

        out.markWriter();
        return out;
    }
}
