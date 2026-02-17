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
package net.hasor.neta.bytebuf;
import java.nio.Buffer;
import java.nio.ByteBuffer;

/**
 * <pre>
 * +-------------+------------+-------------+----------+
 * | discardable | readable   | overlayable | writable |
 * +-------------+------------+-------------+----------+
 * |             |            |             |          |
 * 0   ≤   readerIndex  ≤  marked  ≤  writerIndex ≤ capacity
 *                      writerIndex
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class WrapByteBuffer extends AbstractByteBuf {
    static final int                              RECYCLE_INDEX   = RecycleObjectPool.registerType();
    static       RecycleHandler<WrapByteBuffer> RECYCLE_HANDLER = new RecycleHandler<WrapByteBuffer>() {
        public WrapByteBuffer create() {
            return new WrapByteBuffer();
        }

        @Override
        public void free(WrapByteBuffer tar) {
            RecycleObjectPool.free(RECYCLE_INDEX, tar);
        }
    };
    protected ByteBuffer                     target;

    private WrapByteBuffer() {
    }

    // ------------------------------------------------------------------------

    void initBuffer(ByteBuffer initData, boolean asWrite) {
        super.initByteBuf(null, initData.limit());
        this.target = initData;
        if (!asWrite) {
            this.writerIndex = initData.limit();
            this.markedWriterIndex = initData.limit(); // initData.length -> initData.limit()
        }
    }

    @Override
    public void discardReadBytes() {
        if (this.readerIndex == 0) {
            return;
        }

        if (this.readerIndex != this.writerIndex) {
            ((Buffer) this.target).clear();
            ((Buffer) this.target).position(this.readerIndex);
            ((Buffer) this.target).limit(this.writerIndex);
            ByteBuffer slice = this.target.slice();

            ((Buffer) this.target).clear();
            ((Buffer) this.target).position(0);
            this.target.put(slice);

            this.writerIndex -= this.readerIndex;
            this.markedReaderIndex = Math.max(0, this.markedReaderIndex - this.readerIndex);
            this.markedWriterIndex = Math.max(0, this.markedWriterIndex - this.readerIndex);
            this.readerIndex = 0;
            return;
        }

        this.markedReaderIndex = 0;
        this.markedWriterIndex = 0;
        this.writerIndex = 0;
        this.readerIndex = 0;
    }

    @Override
    public ByteBuf sliceOff(int splitOffset) {
        if (splitOffset == 0) {
            return ByteBuf.EMPTY;
        }
        if (splitOffset < 0 || splitOffset > this.capacity()) {
            throw new IndexOutOfBoundsException();
        }

        ByteBuffer newBuf;
        if (this.target.isDirect()) {
            newBuf = ByteBuffer.allocateDirect(splitOffset);
        } else {
            newBuf = ByteBuffer.allocate(splitOffset);
        }

        ((Buffer) this.target).clear();
        ((Buffer) this.target).position(0);
        ((Buffer) this.target).limit(splitOffset);
        newBuf.put(this.target);
        ((Buffer) newBuf).flip();

        WrapByteBuffer slicedBuf = RecycleObjectPool.get(WrapByteBuffer.RECYCLE_INDEX, WrapByteBuffer.RECYCLE_HANDLER);
        slicedBuf.initBuffer(newBuf, false);

        int remaining = this.capacity() - splitOffset;
        if (remaining > 0) {
            ((Buffer) this.target).clear();
            ((Buffer) this.target).position(splitOffset);
            ((Buffer) this.target).limit(this.capacity());
            ByteBuffer remainingSlice = this.target.slice();

            ((Buffer) this.target).clear();
            ((Buffer) this.target).position(0);
            this.target.put(remainingSlice);
        }

        this.writerIndex = Math.max(0, this.writerIndex - splitOffset);
        this.readerIndex = Math.max(0, this.readerIndex - splitOffset);
        this.markedReaderIndex = Math.max(0, this.markedReaderIndex - splitOffset);
        this.markedWriterIndex = Math.max(0, this.markedWriterIndex - splitOffset);

        return slicedBuf;
    }

    @Override
    public int writableBytes() {
        return this.getMaxCapacity() - this.writerIndex;
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();

        this.target.put(offset, b);
    }

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        checkFree();

        ((Buffer) this.target).clear();
        ((Buffer) this.target).position(offset);
        this.target.put(src, srcOffset, srcLen);
        return srcLen;
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcLen) {
        checkFree();

        ((Buffer) this.target).clear();
        ((Buffer) this.target).position(offset);
        srcLen = Math.min(src.remaining(), srcLen);

        this.target.put((ByteBuffer) src.duplicate().limit(src.position() + srcLen));
        src.position(src.position() + srcLen);
        return srcLen;
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        checkFree();

        ((Buffer) this.target).clear();
        ((Buffer) this.target).position(offset);
        srcLen = Math.min(src.readableBytes(), srcLen);

        src.readBuffer(this.target, srcLen);
        return srcLen;
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        return this.target.get(offset);
    }

    @Override
    protected int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        checkFree();

        ((Buffer) this.target).clear();
        ((Buffer) this.target).position(offset);
        this.target.get(dst, dstOffset, dstLen);
        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuffer dst, int dstLen) {
        checkFree();

        ((Buffer) this.target).clear();
        ((Buffer) this.target).position(offset);
        ((Buffer) this.target).limit(offset + dstLen);
        dst.put(this.target);
        ((Buffer) this.target).clear();
        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuf dst, int dstLen) {
        checkFree();

        ((Buffer) this.target).clear();
        ((Buffer) this.target).position(offset);
        dst.writeBuffer(this.target, dstLen);
        return dstLen;
    }

    @Override
    protected void _free() {
        try {
            ByteBuffer buf = this.target;
            if (buf != null) {
                if (buf.isDirect()) {
                    // Try SmallBufferCache first; fall back to cleaner for non-small direct buffers
                    if (!SmallBufferCache.freeDirect(buf) && ByteBufUtils.CLEANER != null) {
                        ByteBufUtils.CLEANER.freeDirectBuffer(buf);
                    }
                } else if (buf.hasArray()) {
                    // Return heap byte[] to SmallBufferCache (no-op for non-size-class arrays)
                    SmallBufferCache.freeHeap(buf.array());
                }
            }
        } finally {
            this.target = null;
            RECYCLE_HANDLER.free(this);
        }
    }

    @Override
    public int capacity() {
        return this.getMaxCapacity();
    }

    @Override
    public boolean isDirect() {
        return this.target.isDirect();
    }

    @Override
    public WrapByteBuffer copy() {
        checkFree();

        ByteBuffer copyBuffer;
        if (this.target.isDirect()) {
            copyBuffer = ByteBuffer.allocateDirect(this.getMaxCapacity());
        } else {
            copyBuffer = ByteBuffer.allocate(this.getMaxCapacity());
        }

        ((Buffer) this.target).clear();
        copyBuffer.put(this.target);
        WrapByteBuffer byteBuf = RecycleObjectPool.get(WrapByteBuffer.RECYCLE_INDEX, WrapByteBuffer.RECYCLE_HANDLER);
        byteBuf.initBuffer(copyBuffer, true);

        byteBuf.writerIndex = this.writerIndex;
        byteBuf.markedWriterIndex = this.markedWriterIndex;
        byteBuf.readerIndex = this.readerIndex;
        byteBuf.markedReaderIndex = this.markedReaderIndex;
        byteBuf.byteOrder = this.byteOrder;
        byteBuf.bigEndian = this.bigEndian;
        return byteBuf;
    }

    @Override
    protected String getSimpleName() {
        return "WrapByteBuffer";
    }
}