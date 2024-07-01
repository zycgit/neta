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
import net.hasor.cobble.io.IOUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.handler.ProtoHandler;
import net.hasor.neta.handler.ProtoRcvQueue;
import net.hasor.neta.handler.ProtoSndQueue;
import net.hasor.neta.handler.ProtoStatus;

import java.util.List;

/**
 * in {@link ByteBuf} is split into multiple or merge {@link ByteBuf} using a fixed length
 * <pre>
 * <b>Case 1</b>
 * <b>maxLength</b>   = <b>10</b>
 * BEFORE (26 bytes)    AFTER (20 bytes)
 * +----------+        +----------+----------+
 * | 26 bytes | -----> | 10 bytes | 10 bytes |
 * +----------+        +----------+----------+
 * </pre>
 * <pre>
 * <b>Case 2</b>
 * <b>maxLength</b>   = <b>10</b>
 * BEFORE (16 bytes)                     AFTER (10 bytes)
 * +------------------------------+      +------------+
 * | 4 bytes | 10 bytes | 2 bytes | ---> | (10 bytes) |
 * +------------------------------+      +------------+
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-20
 */
public class FixedLengthFrameHandler implements ProtoHandler<ByteBuf, ByteBuf> {
    private final int              fixedLength;
    private       ByteBufAllocator bufAllocator;

    /**
     * Creates a new decoder.
     * @param fixedLength the minimum/maximum length of the decoded frame.
     */
    public FixedLengthFrameHandler(int fixedLength) {
        this.fixedLength = fixedLength;
    }

    @Override
    public void onInit(ProtoContext context) {
        this.bufAllocator = context.getSoContext().getByteBufAllocator();
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
                        dstBuf = this.bufAllocator.buffer(this.fixedLength);
                    }

                    int read = this.fillLimitFrame(buf, dstBuf);
                    if (dstBuf.writerIndex() == this.fixedLength) {
                        dstBuf.markWriter();
                        dst.offerMessage(dstBuf);
                        offerDataSize += dstBuf.readableBytes();
                        hasSlot = dst.hasSlot();
                        dstBuf = null;
                    }
                }
            }

            // flash last
            IOUtils.closeQuietly(dstBuf);
            if (offerDataSize < this.fixedLength) {
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

    private int fillLimitFrame(ByteBuf src, ByteBuf dst) {
        int wlen = Math.min(src.readableBytes(), this.fixedLength - dst.writerIndex());
        return src.readBuffer(dst, wlen);
    }
}