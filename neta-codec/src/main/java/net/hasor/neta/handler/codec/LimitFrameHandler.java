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
package net.hasor.neta.handler.codec;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.io.IOUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoRcvQueue;
import net.hasor.neta.channel.ProtoSndQueue;
import net.hasor.neta.channel.ProtoStatus;

import java.util.List;

/**
 * in {@link ByteBuf} is split into multiple or merge {@link ByteBuf} using a fixed length
 * <pre>
 * <b>Case 1</b>
 * <b>minLength</b>   = <b>5</b>
 * <b>maxLength</b>   = <b>10</b>
 * BEFORE (26 bytes)    AFTER (26 bytes)
 * +----------+        +----------+----------+---------+
 * | 26 bytes | -----> | 10 bytes | 10 bytes | 6 bytes |
 * +----------+        +----------+----------+---------+
 * </pre>
 * <pre>
 * <b>Case 2</b>
 * <b>minLength</b>   = <b>10</b>
 * <b>maxLength</b>   = <b>10</b>
 * BEFORE (26 bytes)    AFTER (20 bytes)
 * +----------+        +----------+----------+
 * | 26 bytes | -----> | 10 bytes | 10 bytes |
 * +----------+        +----------+----------+
 * </pre>
 * <pre>
 * <b>Case 3</b>
 * <b>minLength</b>   = <b>1</b>
 * <b>maxLength</b>   = <b>10</b>
 * BEFORE (16 bytes)                     AFTER (16 bytes)
 * +------------------------------+      +----------+---------+
 * | 4 bytes | 10 bytes | 2 bytes | ---> | 10 bytes | 6 bytes |
 * +------------------------------+      +----------+---------+
 * </pre>
 * <pre>
 * <b>Case 4</b>
 * <b>minLength</b>   = <b>5</b>
 * <b>maxLength</b>   = <b>10</b>
 * BEFORE (16 bytes)                     AFTER (15 bytes)
 * +------------------------------+      +----------+---------+
 * | 4 bytes | 10 bytes | 2 bytes | ---> | 10 bytes | 5 bytes |
 * +------------------------------+      +----------+---------+
 * </pre>
 * <pre>
 * <b>Case 5</b>
 * <b>minLength</b>   = <b>10</b>
 * <b>maxLength</b>   = <b>10</b>
 * BEFORE (16 bytes)                     AFTER (15 bytes)
 * +------------------------------+      +----------+
 * | 4 bytes | 10 bytes | 2 bytes | ---> | 10 bytes |
 * +------------------------------+      +----------+
 * </pre>
 * <pre>
 * <b>Case 6</b>
 * <b>minLength</b>   = <b>4</b>
 * <b>maxLength</b>   = <b>10</b>
 * BEFORE (16 bytes)              AFTER (14 bytes)
 * +---+---+---+---+---+---+      +---+---+---+
 * | 2 | 2 | 2 | 4 | 4 | 2 | ---> | 4 | 6 | 4 |
 * +---+---+---+---+---+---+      +---+---+---+
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-20
 */
public class LimitFrameHandler implements ProtoHandler<ByteBuf, ByteBuf> {
    private final int minLength;
    private final int maxLength;

    /**
     * Creates a new decoder/encoder.
     * @param maxLength the maximum length of the decoded frame.
     */
    public LimitFrameHandler(int maxLength) {
        this(1, maxLength);
    }

    /**
     * Creates a new decoder/encoder.
     * @param minLength the minimum length of the decoded frame.
     * @param maxLength the maximum length of the decoded frame.
     */
    public LimitFrameHandler(int minLength, int maxLength) {
        this.minLength = ObjectUtils.checkPositive(minLength, "minLength");
        this.maxLength = maxLength;

        if (this.minLength > this.maxLength) {
            throw new IllegalArgumentException("maxLength: " + maxLength + " (expected: >= " + this.minLength + ")");
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
        if (src.hasMore() && dst.hasSlot()) {
            boolean hasSlot = true;
            List<ByteBuf> peekAll = src.peekMessage(src.queueSize());
            ByteBuf dstBuf = null;

            int offerDataSize = 0;
            for (ByteBuf buf : peekAll) {
                while (buf.readableBytes() > 0 && hasSlot) {
                    if (dstBuf == null) {
                        dstBuf = context.byteBufAllocator().buffer(this.minLength, this.maxLength);
                    }

                    int read = copy(buf, dstBuf, this.maxLength);
                    if (dstBuf.writerIndex() == this.maxLength) {
                        dstBuf.markWriter();
                        dst.offerMessage(dstBuf);
                        offerDataSize += dstBuf.readableBytes();
                        hasSlot = dst.hasSlot();
                        dstBuf = null;
                    }
                }
            }

            // flash last (minLength)
            if (dstBuf != null) {
                if (dstBuf.writerIndex() >= this.minLength && hasSlot) {
                    dstBuf.markWriter();
                    dst.offerMessage(dstBuf);
                    offerDataSize += dstBuf.readableBytes();
                    dstBuf = null;
                }
                IOUtils.closeQuietly(dstBuf);
            }

            // flash last
            if (offerDataSize < this.minLength) {
                for (ByteBuf buf : peekAll) {
                    buf.resetReader();
                }
            } else {
                for (ByteBuf buf : peekAll) {
                    buf.resetReader();
                    int bufSize = buf.readableBytes();
                    if (bufSize < offerDataSize) {
                        offerDataSize -= bufSize;
                        buf.skipReadableBytes(bufSize);
                        buf.markReader();
                        src.skipMessage(1);
                    } else if (bufSize > offerDataSize) {
                        buf.skipReadableBytes(offerDataSize);
                        buf.markReader();
                        break;
                    } else {
                        buf.skipReadableBytes(offerDataSize);
                        buf.markReader();
                        src.skipMessage(1);
                        break;
                    }
                }
            }
        }

        return ProtoStatus.Next;
    }

    int copy(ByteBuf src, ByteBuf dst, int maxLength) {
        int wlen = Math.min(src.readableBytes(), maxLength - dst.writerIndex());
        return src.readBuffer(dst, wlen);
    }
}