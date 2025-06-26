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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.handler.ProtoHandler;
import net.hasor.neta.handler.ProtoRcvQueue;
import net.hasor.neta.handler.ProtoSndQueue;
import net.hasor.neta.handler.ProtoStatus;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s dynamically by the
 * value of the length field in the message.
 * <pre>
 * <b>Case 1</b>
 * <b>lengthFieldOffset</b>   = <b>0</b>
 * <b>lengthFieldLength</b>   = <b>2</b> (0~8)
 * initialBytesToStrip = 0 (= do not strip header)
 *
 * BEFORE (14 bytes)                  AFTER (14 bytes)
 * +--------+----------------+        +--------+----------------+
 * | Length | Actual Content | -----> | Length | Actual Content |
 * | 0x000C | "HELLO, WORLD" |        | 0x000C | "HELLO, WORLD" |
 * +--------+----------------+        +--------+----------------+
 * </pre>
 * <pre>
 * <b>Case 2</b>
 * lengthFieldOffset   = 0
 * lengthFieldLength   = 2 (0~8)
 * <b>initialBytesToStrip</b> = <b>2</b> (= the length of the Length field)
 *
 * BEFORE (14 bytes)                  AFTER (12 bytes)
 * +--------+----------------+        +----------------+
 * | Length | Actual Content | -----> | Actual Content |
 * | 0x000C | "HELLO, WORLD" |        | "HELLO, WORLD" |
 * +--------+----------------+        +----------------+
 * </pre>
 * <pre>
 * <b>Case 3</b>
 * lengthFieldOffset   =  1
 * lengthFieldLength   =  2 (0~8)
 * <b>initialBytesToStrip</b> =  <b>3</b>
 *
 * BEFORE (16 bytes)                                AFTER (13 bytes)
 * +------+--------+------+----------------+        +------+----------------+
 * | HDR1 | Length | HDR2 | Actual Content | -----> | HDR2 | Actual Content |
 * | 0xCA | 0x0010 | 0xFE | "HELLO, WORLD" |        | 0xFE | "HELLO, WORLD" |
 * +------+--------+------+----------------+        +------+----------------+
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2025-06-26
 */
public class LengthFieldBasedFrameHandler implements ProtoHandler<ByteBuf, ByteBuf> {
    private final ByteOrder        byteOrder;
    private final int              lengthFieldOffset;
    private final int              lengthFieldLength;
    private final int              initialBytesToStrip;
    private final int              lengthAdjustment;
    private final int              frameMaxSize;
    private       ByteBufAllocator bufAllocator;

    /**
     * Creates a new decoder.
     *
     * @param byteOrder the {@link ByteOrder} of the length field
     * @param lengthFieldLength the length of the length field
     */
    public LengthFieldBasedFrameHandler(ByteOrder byteOrder, int lengthFieldLength) {
        this(0, byteOrder, lengthFieldLength, 0);
    }

    /**
     * Creates a new decoder.
     * @param lengthFieldOffset the offset of the length field
     * @param byteOrder the {@link ByteOrder} of the length field
     * @param lengthFieldLength the length of the length field
     */
    public LengthFieldBasedFrameHandler(int lengthFieldOffset, ByteOrder byteOrder, int lengthFieldLength) {
        this(lengthFieldOffset, byteOrder, lengthFieldLength, 0);
    }

    /**
     * Creates a new decoder.
     * @param lengthFieldOffset the offset of the length field
     * @param byteOrder the {@link ByteOrder} of the length field
     * @param lengthFieldLength the length of the length field
     * @param initialBytesToStrip the number of first bytes to strip out from the decoded frame
     */
    public LengthFieldBasedFrameHandler(int lengthFieldOffset, ByteOrder byteOrder, int lengthFieldLength, int initialBytesToStrip) {
        this(lengthFieldOffset, byteOrder, lengthFieldLength, initialBytesToStrip, 0, -1);
    }

    /**
     * Creates a new decoder.
     * @param lengthFieldOffset the offset of the length field
     * @param byteOrder the {@link ByteOrder} of the length field
     * @param lengthFieldLength the length of the length field
     * @param initialBytesToStrip the number of first bytes to strip out from the decoded frame
     * @param lengthAdjustment the compensation value to add to the value of the length field
     */
    public LengthFieldBasedFrameHandler(int lengthFieldOffset, ByteOrder byteOrder, int lengthFieldLength, int initialBytesToStrip, int lengthAdjustment) {
        this(lengthFieldOffset, byteOrder, lengthFieldLength, initialBytesToStrip, lengthAdjustment, -1);
    }

    /**
     * Creates a new decoder.
     * @param lengthFieldOffset the offset of the length field
     * @param byteOrder the {@link ByteOrder} of the length field
     * @param lengthFieldLength the length of the length field
     * @param initialBytesToStrip the number of first bytes to strip out from the decoded frame
     * @param lengthAdjustment the compensation value to add to the value of the length field
     * @param frameMaxSize less than 1, no limit on maximum length(Integer.MAX_VALUE). An error will be reported when the maximum frame length is exceeded.
     */
    public LengthFieldBasedFrameHandler(int lengthFieldOffset, ByteOrder byteOrder, int lengthFieldLength, int initialBytesToStrip, int lengthAdjustment, int frameMaxSize) {
        this.byteOrder = ObjectUtils.checkNotNull(byteOrder, "byteOrder");
        this.lengthFieldOffset = ObjectUtils.checkPositiveOrZero(lengthFieldOffset, "lengthFieldOffset");
        this.lengthFieldLength = ObjectUtils.checkPositive(lengthFieldLength, "lengthFieldLength");
        this.initialBytesToStrip = ObjectUtils.checkPositiveOrZero(initialBytesToStrip, "initialBytesToStrip");
        this.lengthAdjustment = lengthAdjustment;
        this.frameMaxSize = frameMaxSize < 1 ? Integer.MAX_VALUE : frameMaxSize;
    }

    @Override
    public void onInit(ProtoContext context) {
        this.bufAllocator = context.getSoContext().getByteBufAllocator();
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) throws IOException {
        // test min len
        int minLength = this.lengthFieldOffset + Math.max(this.lengthFieldLength, this.initialBytesToStrip - this.lengthFieldOffset);
        List<ByteBuf> peekAll = src.peekMessage(src.queueSize());
        if (!testMinLen(peekAll, minLength)) {
            return ProtoStatus.Next;
        }

        // decode
        if (src.hasMore() && dst.hasSlot()) {
            ByteBuf haderBuf = this.bufAllocator.buffer(minLength);
            int offerDataSize = 0;

            for (int i = 0; i < peekAll.size(); i++) {
                ByteBuf buf = peekAll.get(i);

                // fill haderBuf
                if (offerDataSize < minLength) {
                    offerDataSize += buf.readBuffer(haderBuf, minLength - offerDataSize);
                    haderBuf.flush();
                    if (haderBuf.readableBytes() < minLength && buf.readableBytes() == 0) {
                        continue;
                    }
                }

                // readLength
                long fieldLength = this.readFrameLength(haderBuf, this.lengthFieldOffset, this.lengthFieldLength, this.byteOrder) + this.lengthAdjustment;
                if (fieldLength < 0) {
                    throw new BadFrameException("frame length less than 0, " + fieldLength);
                }
                if (fieldLength > this.frameMaxSize) {
                    throw new TooLongFrameException("frame length " + fieldLength + ", max limit " + this.frameMaxSize);
                }

                // test readMore
                if (!readMore(i, fieldLength, peekAll)) {
                    this.resetBuffers(peekAll);
                    return ProtoStatus.Next;
                } else {
                    int len = haderBuf.readableBytes() - this.initialBytesToStrip + (int) fieldLength;
                    ByteBuf byteBuf = this.bufAllocator.buffer(len <= 0 ? 1 : len);

                    offerDataSize += this.readFrame(i, peekAll, haderBuf, byteBuf, this.initialBytesToStrip, (int) fieldLength);
                    dst.offerMessage(byteBuf);

                    this.flashRead(src, offerDataSize, peekAll);
                    return ProtoStatus.Retry;
                }
            }
        }

        this.resetBuffers(peekAll);
        return ProtoStatus.Next;
    }

    protected int readFrame(int formIdx, List<ByteBuf> all, ByteBuf hader, ByteBuf dstByteBuf, int initialBytesToStrip, int readLength) throws IOException {
        int offset = readLength;

        if (initialBytesToStrip > 0) {
            hader.skipReadableBytes(initialBytesToStrip);
            hader.readBuffer(dstByteBuf);
        } else {
            hader.readBuffer(dstByteBuf);
        }

        for (int i = formIdx; i < all.size(); i++) {
            ByteBuf buf = all.get(i);
            if (offset <= 0) {
                break;
            }

            offset -= buf.readBuffer(dstByteBuf, offset);
        }

        dstByteBuf.flush();
        return readLength;
    }

    private void flashRead(ProtoRcvQueue<ByteBuf> src, int offerSize, List<ByteBuf> peekAll) {
        int offerSizeCopy = offerSize;
        for (ByteBuf buf : peekAll) {
            buf.resetReader();
            int bufSize = buf.readableBytes();
            if (bufSize < offerSizeCopy) {
                offerSizeCopy -= bufSize;
                buf.skipReadableBytes(bufSize);
                buf.markReader();
                src.skipMessage(1);
            } else if (bufSize > offerSizeCopy) {
                buf.skipReadableBytes(offerSizeCopy);
                buf.markReader();
                break;
            } else {
                buf.skipReadableBytes(offerSizeCopy);
                buf.markReader();
                src.skipMessage(1);
                break;
            }
        }
    }

    private boolean testMinLen(List<ByteBuf> buffers, int minimumLength) {
        int minReadableBytes = 0;
        for (ByteBuf peek : buffers) {
            minReadableBytes += peek.readableBytes();
            if (minReadableBytes >= minimumLength) {
                break;
            }
        }
        return minReadableBytes >= minimumLength;
    }

    private void resetBuffers(List<ByteBuf> peekAll) {
        for (ByteBuf peek : peekAll) {
            peek.resetReader();
        }
    }

    private boolean readMore(int formIdx, long readLength, List<ByteBuf> peekAll) {
        long readableBytes = 0;
        for (int i = formIdx; i < peekAll.size(); i++) {
            ByteBuf buf = peekAll.get(i);
            readableBytes += buf.readableBytes();
            if (readableBytes >= readLength) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decodes the specified region of the buffer into an unadjusted frame length.  The default implementation is
     * capable of decoding the specified region into an unsigned 8/16/24/32/64 bit integer.  Override this method to
     * decode the length field encoded differently.  Note that this method must not modify the state of the specified
     * buffer (e.g. {@code readerIndex}, {@code writerIndex}, and the content of the buffer.)
     */
    protected long readFrameLength(ByteBuf buf, int offset, int length, ByteOrder order) {
        buf = buf.order(order);
        switch (length) {
            case 1:
                return buf.getUInt8(offset);
            case 2:
                return buf.getUInt16(offset);
            case 3:
                return buf.getUInt24(offset);
            case 4:
                return buf.getUInt32(offset);
            case 8:
                return buf.getInt64(offset);
            default:
                throw new CodecException("unsupported lengthFieldLength: " + lengthFieldLength + " (expected: 1, 2, 3, 4, or 8)");
        }
    }
}