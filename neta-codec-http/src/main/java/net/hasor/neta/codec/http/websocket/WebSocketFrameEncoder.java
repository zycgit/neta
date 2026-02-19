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

    private static byte[] readPayload(ByteBuf content) {
        if (content == null || content.readableBytes() == 0) {
            return new byte[0];
        }
        int len = content.readableBytes();
        byte[] bytes = new byte[len];
        // Bulk read without advancing the reader index of the original buffer
        content.getBytes(content.readerIndex(), bytes, 0, len);
        return bytes;
    }

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

        int totalSize = headerSize + payloadLen;
        ByteBuf out = context.byteBufAllocator().buffer(totalSize, Integer.MAX_VALUE);

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
                // For masked frames, we need a byte array for XOR
                byte[] payload = readPayload(content);
                int i = 0;
                int len4 = payloadLen & ~3;
                for (; i < len4; i += 4) {
                    payload[i] ^= maskKey[0];
                    payload[i + 1] ^= maskKey[1];
                    payload[i + 2] ^= maskKey[2];
                    payload[i + 3] ^= maskKey[3];
                }
                for (; i < payloadLen; i++) {
                    payload[i] ^= maskKey[i & 3];
                }
                out.writeBytes(payload, 0, payloadLen);
            } else {
                // Direct ByteBuf-to-ByteBuf copy (avoids intermediate byte array)
                content.getBuffer(0, out, payloadLen);
            }
        }

        out.markWriter();
        return out;
    }
}
