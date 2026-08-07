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
import net.hasor.cobble.ref.RecycleObjectPool;
/**
 * Fixed-size {@link ByteBuf} view over an existing {@link ByteBuffer}.
 * <p>
 * Like {@link WrapArrayBuffer}, this class keeps the wrapped storage shape as
 * is and only overlays Neta's logical index model. The underlying
 * {@link ByteBuffer} may be heap or direct, but the logical read/write rules
 * are identical.
 * <pre>
 * physical storage
 *   target ByteBuffer
 *   +---------------------------------------------------+
 *   | 0 | 1 | 2 | ... | capacity - 1 |
 *   +---------------------------------------------------+
 * 
 * logical layout on top of the ByteBuffer
 *   0      markedReaderIndex   readerIndex   markedWriterIndex   writerIndex   capacity
 *   |-------------|---------------|------------------|---------------|
 *   | ancient     | discardable   | readable         | overlayable   | writable |
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class WrapByteBuffer extends AbstractByteBuf {
    static final RecycleObjectPool.Recycler<WrapByteBuffer> RECYCLER = RecycleObjectPool.recycler(//
            WrapByteBuffer::new, WrapByteBuffer::resetState, WrapByteBuffer::onRecycle);
    protected ByteBuffer                                target;

    private WrapByteBuffer() {
    }

    private void resetState() {
        this.target = null;
    }

    private void onRecycle() {
        ByteBuffer buf = this.target;
        this.target = null;
        if (buf != null) {
            if (buf.isDirect()) {
                if (!SmallBufferCache.freeDirect(buf) && ByteBufUtils.CLEANER != null) {
                    ByteBufUtils.CLEANER.freeDirectBuffer(buf);
                }
            } else if (buf.hasArray()) {
                SmallBufferCache.freeHeap(buf.array());
            }
        }
    }

    // ------------------------------------------------------------------------

    void initBuffer(ByteBuffer initData, boolean asWrite) {
        super.initByteBuf(null, initData.limit());
        this.target = initData;
        if (initData.limit() > 0) {
            this.initMetricTracking(ByteBufAllocator.DEFAULT.metric(), initData.isDirect(), initData.limit());
        }
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

        WrapByteBuffer slicedBuf = WrapByteBuffer.RECYCLER.get();
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

        ByteBuffer slice = src.duplicate();
        ((Buffer) slice).limit(src.position() + srcLen);
        this.target.put(slice);
        ((Buffer) src).position(src.position() + srcLen);
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
        RECYCLER.recycle(this);
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
        WrapByteBuffer byteBuf = WrapByteBuffer.RECYCLER.get();
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
