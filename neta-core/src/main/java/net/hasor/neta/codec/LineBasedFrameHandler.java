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
package net.hasor.neta.codec;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
/**
 * Splits queued {@link ByteBuf} data into frames terminated by {@code '\n'} or {@code "\r\n"}.
 * <p>
 * The handler scans raw bytes, can join delimiters that span multiple queued buffers,
 * and optionally keeps or strips the line delimiter from the emitted frame.
 * <pre>
 * input queue:   ["abc\r"] ["\n123\n"]
 * strip=true  -> ["abc"] ["123"]
 * strip=false -> ["abc\r\n"] ["123\n"]
 * </pre>
 * <p>
 * {@code maxLength} limits the content length before the delimiter. If no delimiter
 * is found before that limit is exceeded, a {@link TooLongFrameException} is thrown.
 * <p><b>Ownership:</b> emitted frames are newly allocated buffers owned by downstream.
 * Source buffers remain managed by the pipeline queue; this handler only advances their
 * read state while assembling the replacement frame.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-20
 */
public class LineBasedFrameHandler implements ProtoHandler<ByteBuf, ByteBuf> {
    /** Maximum length of a frame we're willing to decode, Throws an exception when maxLength is exceeded */
    private final int     maxLength;
    private final boolean stripDelimiter;
    private ByteBuf       pendingLine;

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
        boolean hasAny = false;
        if (this.pendingLine != null) {
            if (!dst.offerMessage(this.pendingLine)) {
                return ProtoStatus.Stop;
            }
            this.pendingLine = null;
            hasAny = true;
        }

        while (src.hasMore()) {
            if (!dst.hasSlot()) {
                return hasAny ? ProtoStatus.Next : ProtoStatus.Stop;
            }
            List<ByteBuf> peekArray = src.peekMessage(src.queueSize());
            ByteBuf line = this.expectLine(context, src, peekArray);

            if (line != null) {
                if (!dst.offerMessage(line)) {
                    this.pendingLine = line;
                    return hasAny ? ProtoStatus.Next : ProtoStatus.Stop;
                }
                hasAny = true;
            } else {
                break;
            }
        }
        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        ByteBufUtils.releaseAll(this.pendingLine);
        this.pendingLine = null;
    }

    private ByteBuf expectLine(ProtoContext ctx, ProtoRcvQueue<ByteBuf> src, List<ByteBuf> peekArray) {
        int lineOffset = -1;
        int consumedBytes = 0;
        int lineBufferIndex = -1;

        for (int i = 0; i < peekArray.size(); i++) {
            ByteBuf buf = peekArray.get(i);
            consumedBytes += buf.readableBytes();

            lineOffset = buf.expectLine();
            if (lineOffset >= 0) {
                lineBufferIndex = i;
                break;
            }
        }

        if (lineOffset < 0) {
            if (this.maxLength > 0 && consumedBytes > this.maxLength) {
                throw new TooLongFrameException("frame length " + consumedBytes + " exceeds " + this.maxLength);
            }
            return null;
        }

        // Calculate content length (bytes before delimiter in the buffer that contains it)
        ByteBuf lastBuf = peekArray.get(lineBufferIndex);
        int contentLength = consumedBytes - lastBuf.readableBytes() + lineOffset;

        // Detect cross-buffer \r\n: \r at end of a preceding buffer, \n at position 0 of lastBuf
        int crBufferIndex = -1;
        if (lineBufferIndex > 0 && lineOffset == 0 && lastBuf.getUInt8(0) == '\n') {
            for (int j = lineBufferIndex - 1; j >= 0; j--) {
                ByteBuf prevBuf = peekArray.get(j);
                if (prevBuf.readableBytes() > 0) {
                    if (prevBuf.getUInt8(prevBuf.readableBytes() - 1) == '\r') {
                        crBufferIndex = j;
                    }
                    break;
                }
            }
        }
        boolean crossBufferCrLf = crBufferIndex >= 0;

        // Actual content length excluding all delimiter chars
        int actualContentLength = crossBufferCrLf ? contentLength - 1 : contentLength;
        if (this.maxLength > 0 && actualContentLength > this.maxLength) {
            throw new TooLongFrameException("frame length " + actualContentLength + " exceeds " + this.maxLength);
        }

        // Determine read/skip parameters for the last buffer
        int readLen = lineOffset;
        int skipLen = 0;
        boolean lastBufDelimiterIsCr = lastBuf.getUInt8(lineOffset) == '\r';
        if (this.stripDelimiter) {
            skipLen = lastBufDelimiterIsCr ? 2 : 1;
        } else {
            readLen += lastBufDelimiterIsCr ? 2 : 1;
        }

        // Calculate exact allocation size to avoid over-allocation
        int allocSize;
        if (this.stripDelimiter) {
            allocSize = actualContentLength;
        } else {
            allocSize = contentLength + (lastBufDelimiterIsCr ? 2 : 1);
        }

        ByteBuf tmpBuf = ctx.byteBufAllocator().buffer(Math.max(allocSize, 1));

        for (int i = 0; i <= lineBufferIndex; i++) {
            ByteBuf buf = peekArray.get(i);

            if (i != lineBufferIndex) {
                if (this.stripDelimiter && i == crBufferIndex) {
                    // Cross-buffer \r\n: read content but skip the trailing \r
                    buf.readBuffer(tmpBuf, buf.readableBytes() - 1);
                    buf.skipReadableBytes(1);
                } else {
                    buf.readBuffer(tmpBuf);
                }
                buf.markReader();
                src.skipMessage(1);
            } else {
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