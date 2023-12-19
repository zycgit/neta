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
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.handler.PipeHandler;
import net.hasor.neta.handler.PipeRcvQueue;
import net.hasor.neta.handler.PipeSndQueue;
import net.hasor.neta.handler.PipeStatus;

import java.util.Objects;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class LimitFramePipeHandler implements PipeHandler<ByteBuf, ByteBuf> {
    private final int              maxLength;
    private final ByteBufAllocator bufAllocator;

    /**
     * Creates a new decoder.
     * @param maxLength the maximum length of the decoded frame.
     */
    public LimitFramePipeHandler(final int maxLength) {
        this(maxLength, ByteBufAllocator.DEFAULT);
    }

    /**
     * Creates a new decoder.
     * @param maxLength the maximum length of the decoded frame.
     */
    public LimitFramePipeHandler(final int maxLength, ByteBufAllocator bufAllocator) {
        this.maxLength = maxLength;
        this.bufAllocator = Objects.requireNonNull(bufAllocator);
    }

    @Override
    public PipeStatus doHandler(PipeContext context, PipeRcvQueue<ByteBuf> src, PipeSndQueue<ByteBuf> dst) {
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

        return PipeStatus.Next;
    }

    private int fillLimitFrame(ByteBuf src, ByteBuf dst) {
        int wlen = Math.min(src.readableBytes(), this.maxLength - dst.writerIndex());
        int len = src.read(dst, wlen);

        src.markReader();
        return len;
    }
}