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
import net.hasor.neta.bytebuf.ByteBufAllocator;
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
        // Read without advancing the reader index of the original buffer
        for (int i = 0; i < len; i++) {
            bytes[i] = content.getByte(content.readerIndex() + i);
        }
        return bytes;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<WebSocketFrame> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
        while (src.hasMore()) {
            WebSocketFrame frame = src.takeMessage();
            if (frame != null) {
                dst.offerMessage(encodeFrame(frame));
            }
        }
        return ProtoStatus.Next;
    }

    private ByteBuf encodeFrame(WebSocketFrame frame) {
        byte[] payload = readPayload(frame.content());
        int payloadLen = payload.length;
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
        ByteBuf out = ByteBufAllocator.DEFAULT.buffer(totalSize, Integer.MAX_VALUE);

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

        // Payload (apply mask if required)
        if (masked) {
            byte[] maskedPayload = new byte[payloadLen];
            for (int i = 0; i < payloadLen; i++) {
                maskedPayload[i] = (byte) (payload[i] ^ maskKey[i % 4]);
            }
            out.writeBytes(maskedPayload, 0, maskedPayload.length);
        } else {
            out.writeBytes(payload, 0, payloadLen);
        }

        out.markWriter();
        return out;
    }
}
