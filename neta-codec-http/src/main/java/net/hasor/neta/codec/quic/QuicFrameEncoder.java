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
package net.hasor.neta.codec.quic;

import java.util.concurrent.atomic.AtomicLong;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;

/**
 * QUIC stream frame encoder that wraps stream data into QUIC STREAM frames.
 * <p>
 * This encoder takes {@link ByteBuf} data intended for a specific QUIC stream
 * and encodes it into QUIC STREAM frames (RFC 9000, Section 19.8).
 * <p>
 * The input ByteBuf is expected to carry a 9-byte header:
 * <ul>
 *   <li>8 bytes: stream ID</li>
 *   <li>1 byte: FIN flag (0 or 1)</li>
 *   <li>remaining: stream data</li>
 * </ul>
 * This format matches the output of {@link QuicFrameDecoder}.
 */
public class QuicFrameEncoder implements ProtoHandler<ByteBuf, ByteBuf> {
    private final boolean    serverMode;
    private final AtomicLong nextBidiStreamId;
    private final AtomicLong nextUniStreamId;

    /**
     * Creates a new QUIC frame encoder.
     * @param serverMode true for server-side, false for client-side
     */
    public QuicFrameEncoder(boolean serverMode) {
        this.serverMode = serverMode;
        // Client-initiated bidi: 0, 4, 8, ... Server-initiated bidi: 1, 5, 9, ...
        this.nextBidiStreamId = new AtomicLong(serverMode ? 1 : 0);
        this.nextUniStreamId = new AtomicLong(serverMode ? 3 : 2);
    }

    /** Allocates the next bidirectional stream ID. */
    public long nextBidiStreamId() {
        return nextBidiStreamId.getAndAdd(4);
    }

    /** Allocates the next unidirectional stream ID. */
    public long nextUniStreamId() {
        return nextUniStreamId.getAndAdd(4);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
        while (src.hasMore()) {
            ByteBuf msg = src.takeMessage();
            if (msg == null || msg.readableBytes() < 9) {
                continue;
            }

            // Read stream metadata header: streamId(8) + fin(1)
            byte[] header = new byte[9];
            msg.getBytes(0, header, 0, 9);
            msg.skipReadableBytes(9);

            long streamId = 0;
            for (int i = 0; i < 8; i++) {
                streamId = (streamId << 8) | (header[i] & 0xFF);
            }
            boolean fin = header[8] != 0;

            int dataLen = msg.readableBytes();
            byte[] data = new byte[dataLen];
            if (dataLen > 0) {
                msg.getBytes(0, data, 0, dataLen);
            }

            // Build STREAM frame
            // Type byte: 0x08 | OFF(0x04) | LEN(0x02) | FIN(0x01)
            int frameType = QuicFrameType.STREAM | 0x02; // always include LEN
            if (fin) {
                frameType |= 0x01; // FIN bit
            }

            byte[] typeBytes = QuicVarInt.encode(frameType);
            byte[] sidBytes = QuicVarInt.encode(streamId);
            byte[] lenBytes = QuicVarInt.encode(dataLen);

            int totalSize = typeBytes.length + sidBytes.length + lenBytes.length + dataLen;
            ByteBuf frame = context.byteBufAllocator().buffer(totalSize);

            frame.writeBytes(typeBytes, 0, typeBytes.length);
            frame.writeBytes(sidBytes, 0, sidBytes.length);
            frame.writeBytes(lenBytes, 0, lenBytes.length);
            if (dataLen > 0) {
                frame.writeBytes(data, 0, dataLen);
            }
            frame.markWriter();
            dst.offerMessage(frame);
        }

        return ProtoStatus.Next;
    }
}
