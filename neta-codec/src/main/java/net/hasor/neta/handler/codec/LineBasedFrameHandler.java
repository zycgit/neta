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
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.handler.ProtoHandler;
import net.hasor.neta.handler.ProtoRcvQueue;
import net.hasor.neta.handler.ProtoSndQueue;
import net.hasor.neta.handler.ProtoStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s on line endings.
 * <p>
 * Both {@code "\n"} and {@code "\r\n"} are handled.
 * <p>
 * The byte stream is expected to be in UTF-8 character encoding or ASCII. The current implementation
 * uses direct {@code byte} to {@code char} cast and then compares that {@code char} to a few low range
 * ASCII characters like {@code '\n'} or {@code '\r'}. UTF-8 is not using low range [0..0x7F]
 * byte values for multibyte codepoint representations therefore fully supported by this implementation.
 * <p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-20
 */
public class LineBasedFrameHandler implements ProtoHandler<ByteBuf, ByteBuf> {
    /** Maximum length of a frame we're willing to decode, Throws an exception when maxLength is exceeded */
    private final int     maxLength;
    private final boolean stripDelimiter;

    /**
     * Creates a new decoder/encoder.
     * the maximum length is Integer.MAX_VALUE
     */
    public LineBasedFrameHandler() {
        this(Integer.MAX_VALUE, true);
    }

    /**
     * Creates a new decoder/encoder.
     * @param maxLength the maximum length of the decoded frame.
     * A {@link TooLongFrameException} is thrown if the length of the frame exceeds this value.
     */
    public LineBasedFrameHandler(final int maxLength) {
        this(maxLength, true);
    }

    /**
     * Creates a new decoder/encoder.
     * @param maxLength the maximum length of the decoded frame.
     * A {@link TooLongFrameException} is thrown if the length of the frame exceeds this value.
     * @param stripDelimiter whether the decoded frame should strip out the delimiter or not
     */
    public LineBasedFrameHandler(int maxLength, boolean stripDelimiter) {
        this.maxLength = maxLength;
        this.stripDelimiter = stripDelimiter;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
        if (src.hasMore()) {
            List<ByteBuf> peekArray = src.peekMessage(src.queueSize());
            while (src.hasMore()) {
                ByteBuf line = this.expectLine(context, src, peekArray);
                if (line != null) {
                    dst.offerMessage(line);
                } else {
                    break;
                }
            }
        }
        return ProtoStatus.Next;
    }

    private ByteBuf expectLine(ProtoContext ctx, ProtoRcvQueue<ByteBuf> src, List<ByteBuf> peekArray) {
        List<ByteBuf> temp = new ArrayList<>();
        boolean hasLine = false;
        int consumedBytes = 0;

        for (ByteBuf buf : peekArray) {
            consumedBytes += buf.readableBytes();
            temp.add(buf);

            if (buf.hasLine()) {
                hasLine = true;
                break;
            }
        }
        if (!hasLine) {
            if (this.maxLength > 0 && consumedBytes > this.maxLength) {
                throw new TooLongFrameException("frame length " + consumedBytes + " exceeds " + this.maxLength);
            } else {
                return null;
            }
        }

        ByteBuf tmpBuf = ctx.byteBufAllocator().buffer(consumedBytes);

        int lastIndex = temp.size() - 1;
        for (int i = 0; i < temp.size(); i++) {
            ByteBuf buf = temp.get(i);

            if (i != lastIndex) {
                buf.readBuffer(tmpBuf);
                buf.markReader();
                src.skipMessage(1);
            } else {
                int readLen = buf.expectLine();
                int skipLen = 0;
                if (this.stripDelimiter) {
                    if (buf.getUInt8(readLen) == '\r') {
                        readLen += 2;
                    } else {
                        readLen += 1;
                    }
                } else {
                    if (buf.getUInt8(readLen) == '\r') {
                        skipLen = 2;
                    } else {
                        skipLen = 1;
                    }
                }

                buf.readBuffer(tmpBuf, readLen);
                buf.skipReadableBytes(skipLen);
                buf.markReader();
                if (buf.readableBytes() <= 0) {
                    src.skipMessage(1);
                }
            }
        }
        tmpBuf.markWriter();
        return tmpBuf;
    }
}