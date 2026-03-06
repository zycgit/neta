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
import java.nio.ByteBuffer;
import net.hasor.cobble.ref.RecycleObjectPool;

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
final class WrapArrayBuffer extends AbstractByteBuf {
    static final int                                           RECYCLE_INDEX   = RecycleObjectPool.registerType();
    static       RecycleObjectPool.ObjHandler<WrapArrayBuffer> RECYCLE_HANDLER = new RecycleObjectPool.ObjHandler<WrapArrayBuffer>() {
        public WrapArrayBuffer create() {
            return new WrapArrayBuffer();
        }

        @Override
        public void free(WrapArrayBuffer tar) {
            RecycleObjectPool.free(RECYCLE_INDEX, tar);
        }
    };
    byte[] target;

    private WrapArrayBuffer() {
    }

    // ------------------------------------------------------------------------

    void initBuffer(byte[] initData, boolean asWrite) {
        super.initByteBuf(null, initData.length);
        this.target = initData;
        if (!asWrite) {
            this.writerIndex = initData.length;
            this.markedWriterIndex = initData.length;
        }
    }

    @Override
    public void discardReadBytes() {
        if (this.readerIndex == 0) {
            return;
        }

        if (this.readerIndex != this.writerIndex) {
            System.arraycopy(this.target, this.readerIndex, this.target, 0, this.readableBytes());
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

        byte[] sliceData = new byte[splitOffset];
        System.arraycopy(this.target, 0, sliceData, 0, splitOffset);

        int remaining = this.capacity() - splitOffset;
        if (remaining > 0) {
            System.arraycopy(this.target, splitOffset, this.target, 0, remaining);
        }

        this.writerIndex = Math.max(0, this.writerIndex - splitOffset);
        this.readerIndex = Math.max(0, this.readerIndex - splitOffset);
        this.markedReaderIndex = Math.max(0, this.markedReaderIndex - splitOffset);
        this.markedWriterIndex = Math.max(0, this.markedWriterIndex - splitOffset);
        return ByteBuf.wrap(sliceData);
    }

    @Override
    public int writableBytes() {
        return this.getMaxCapacity() - this.writerIndex;
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();

        this.target[offset] = b;
    }

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        checkFree();

        System.arraycopy(src, srcOffset, this.target, offset, srcLen);
        return srcLen;
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcLen) {
        checkFree();

        srcLen = Math.min(src.remaining(), srcLen);
        src.get(this.target, offset, srcLen);
        return srcLen;
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        checkFree();

        srcLen = Math.min(src.readableBytes(), srcLen);
        src.readBytes(this.target, offset, srcLen);
        return srcLen;
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        return this.target[offset];
    }

    @Override
    protected int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        checkFree();

        System.arraycopy(this.target, offset, dst, dstOffset, dstLen);
        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuffer dst, int dstLen) {
        checkFree();

        dst.put(this.target, offset, dstLen);
        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuf dst, int dstLen) {
        checkFree();

        dst.writeBytes(this.target, offset, dstLen);
        return dstLen;
    }

    @Override
    protected void _free() {
        this.target = null;
        RECYCLE_HANDLER.free(this);
    }

    @Override
    public int capacity() {
        return this.getMaxCapacity();
    }

    @Override
    public boolean isDirect() {
        return false;
    }

    @Override
    public WrapArrayBuffer copy() {
        checkFree();

        byte[] copyArray = this.target.clone();
        WrapArrayBuffer byteBuf = RecycleObjectPool.get(WrapArrayBuffer.RECYCLE_INDEX, WrapArrayBuffer.RECYCLE_HANDLER);
        byteBuf.initBuffer(copyArray, true);

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
        return "WrapArrayBuffer";
    }
}