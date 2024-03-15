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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.handler.ProtoHandler;
import net.hasor.neta.handler.ProtoRcvQueue;
import net.hasor.neta.handler.ProtoSndQueue;
import net.hasor.neta.handler.ProtoStatus;

import java.util.Objects;

/**
 * in {@link ByteBuf} is split into multiple {@link ByteBuf} using a fixed length
 * <pre>
 * <b>Case 1</b>
 * <b>maxLength</b>   = <b>10</b>
 * BEFORE (25 bytes)    AFTER (25 bytes)
 * +----------+        +----------+----------+---------+
 * | 25 bytes | -----> | 10 bytes | 10 bytes | 5 bytes |
 * +----------+        +----------+----------+---------+
 * </pre>
 * <pre>
 * <b>Case 2</b>
 * <b>maxLength</b>   = <b>10</b>
 * BEFORE (14 bytes)                     AFTER (14 bytes)
 * +------------------------------+      +------------+-----------+
 * | 3 bytes | 10 bytes | 1 bytes | ---> | (10 bytes) | (4 bytes) |
 * +------------------------------+      +------------+-----------+
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-20
 */
public class LimitFrameHandler implements ProtoHandler<ByteBuf, ByteBuf> {
    private final int              maxLength;
    private final ByteBufAllocator bufAllocator;

    /**
     * Creates a new decoder.
     * @param maxLength the maximum length of the decoded frame.
     */
    public LimitFrameHandler(final int maxLength) {
        this(maxLength, ByteBufAllocator.DEFAULT);
    }

    /**
     * Creates a new decoder.
     * @param maxLength the maximum length of the decoded frame.
     */
    public LimitFrameHandler(final int maxLength, ByteBufAllocator bufAllocator) {
        this.maxLength = maxLength;
        this.bufAllocator = Objects.requireNonNull(bufAllocator);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf dstBuf = null;

        while (src.hasMore() && dst.hasSlot()) {
            ByteBuf srcBuf = src.peekMessage();
            if (srcBuf == null) {
                break;
            }

            while (srcBuf.hasReadable() && dst.hasSlot()) {
                if (dstBuf == null) {
                    dstBuf = this.bufAllocator.pooledBuffer();
                }

                int read = fillLimitFrame(srcBuf, dstBuf);
                if (dstBuf.writerIndex() == this.maxLength) {
                    dstBuf.markWriter();
                    dst.offerMessage(dstBuf);
                    dstBuf = null;
                }
            }

            if (!srcBuf.hasReadable()) {
                src.skipMessage(1);
            }
        }

        if (dstBuf != null) {
            dstBuf.markWriter();
            dst.offerMessage(dstBuf);
        }

        return ProtoStatus.Next;
    }

    private int fillLimitFrame(ByteBuf src, ByteBuf dst) {
        int wlen = Math.min(src.readableBytes(), this.maxLength - dst.writerIndex());
        int len = src.read(dst, wlen);

        src.markReader();
        return len;
    }
}