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
 * Auto-resizing {@link ByteBuf} backed by a {@link java.nio.ByteBuffer}.
 * <p>Similar to {@link AutoArrayByteBuf} but uses a {@link java.nio.ByteBuffer}
 * as the underlying storage, which can be either a JVM heap buffer
 * ({@link java.nio.ByteBuffer#allocate}) or a JVM direct buffer
 * ({@link java.nio.ByteBuffer#allocateDirect}).  This allows the same
 * auto-grow semantics for scenarios that need direct memory access (e.g.,
 * AIO DMA reads/writes).
 * <pre>
 * current storage
 *   target : ByteBuffer
 *   +-----------------------------------------------------------+
 *   | discarded |         readable data         |   writable    |
 *   +-----------------------------------------------------------+
 *   0        markedReaderIndex               writerIndex      target.capacity()
 *
 * when resize or recycle happens
 *   old target  --copy readable window-->  new ByteBuffer
 *   +-----------+                         +---------------------+
 *   | readable  | --------------------->  | readable | writable |
 *   +-----------+                         +---------------------+
 * </pre>
 * <p><b>Growth strategy:</b> when a write exceeds the current capacity,
 * a new {@link java.nio.ByteBuffer} of size
 * {@code (current + extensionSize)} is allocated, the existing content is
 * copied into it, and the old buffer is released (or returned to the cleaner
 * if it is direct memory).
 * <p><b>Recycle:</b> on {@link #markReader()}, consumed bytes are dropped and
 * the buffer may shrink.  Recycled instances go back to the
 * its dedicated {@link RecycleObjectPool.Recycler}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 * @see AutoArrayByteBuf
 * @see RingByteBuffer
 */
final class AutoByteBuffer extends AbstractByteBuf {
    static final RecycleObjectPool.Recycler<AutoByteBuffer> RECYCLER = RecycleObjectPool.recycler(//
            AutoByteBuffer::new, AutoByteBuffer::resetState, AutoByteBuffer::onRecycle);
    ByteBuffer target;
    private int extensionSize;

    // ------------------------------------------------------------------------

    private AutoByteBuffer() {
    }

    private void resetState() {
        this.target = null;
        this.extensionSize = 0;
    }

    private void onRecycle() {
        ByteBuffer oldTarget = this.target;
        this.target = null;
        this.extensionSize = 0;
        if (oldTarget != null && !SmallBufferCache.freeDirect(oldTarget) && ByteBufUtils.CLEANER != null) {
            ByteBufUtils.CLEANER.freeDirectBuffer(oldTarget);
        }
    }

    void initBuffer(ByteBufAllocator alloc, int maxCapacity, int extensionSize, ByteBuffer initData) {
        super.initByteBuf(alloc, maxCapacity);
        this.extensionSize = Math.min(extensionSize, maxCapacity);
        this.target = initData;
    }

    @Override
    public ByteBuf markReader() {
        if (this.markedReaderIndex != this.readerIndex) {
            this.markedReaderIndex = this.readerIndex;
            this.recycle();
        }
        return this;
    }

    private void recycle() {
        int requestSize = this.writerIndex - this.markedReaderIndex;
        ByteBuffer oldTarget = this.target;
        int newSize = evalSize(requestSize);
        ByteBuffer recycle = SmallBufferCache.isSmallSize(newSize) ? SmallBufferCache.allocDirect(newSize) : this.alloc.jvmBuffer(newSize);
        try {
            ((Buffer) oldTarget).clear().position(this.markedReaderIndex).limit(this.markedReaderIndex + requestSize);
            recycle.put(oldTarget);

            int recyclePos = this.markedReaderIndex;
            this.target = recycle;
            this.updateMetricCapacity(recycle.capacity());
            recycle = null; // transfer ownership, don't free on exception
            this.writerIndex = this.writerIndex - recyclePos;
            this.markedWriterIndex = this.markedWriterIndex - recyclePos;
            this.readerIndex = this.readerIndex - recyclePos;
            this.markedReaderIndex = 0;
        } finally {
            // free the buffer that is no longer needed (oldTarget on success, recycle on failure)
            ByteBuffer toFree = (recycle != null) ? recycle : oldTarget;
            if (toFree != null && !SmallBufferCache.freeDirect(toFree)) {
                if (ByteBufUtils.CLEANER != null) {
                    ByteBufUtils.CLEANER.freeDirectBuffer(toFree);
                }
            }
        }
    }

    private int evalSize(int requestSize) {
        int maxCap = this.getMaxCapacity();
        int newSize;
        if ((requestSize % this.extensionSize) > 0) {
            int rate = (requestSize / this.extensionSize) + 1;
            newSize = (int) Math.min((long) rate * this.extensionSize, maxCap);
        } else {
            newSize = (int) Math.min((long) requestSize + this.extensionSize, maxCap);
        }
        return Math.min(newSize, maxCap);
    }

    private void checkExtension(int offset, int len) {
        int currentCap = this.capacity();
        int requestSize = offset + len;
        if (requestSize > currentCap) {
            ByteBuffer oldTarget = this.target;
            int newSize = evalSize(requestSize);
            ByteBuffer extension = SmallBufferCache.isSmallSize(newSize) ? SmallBufferCache.allocDirect(newSize) : this.alloc.jvmBuffer(newSize);
            try {
                ((Buffer) oldTarget).clear();
                extension.put(oldTarget);
                this.target = extension;
                this.updateMetricCapacity(extension.capacity());
                extension = null; // transfer ownership
            } finally {
                ByteBuffer toFree = (extension != null) ? extension : oldTarget;
                if (toFree != null && !SmallBufferCache.freeDirect(toFree)) {
                    if (ByteBufUtils.CLEANER != null) {
                        ByteBufUtils.CLEANER.freeDirectBuffer(toFree);
                    }
                }
            }
        }
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();
        checkExtension(offset, 1);

        this.target.put(offset, b);
    }

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        checkFree();
        checkExtension(offset, srcLen);

        ((Buffer) this.target).clear().position(offset);
        this.target.put(src, srcOffset, srcLen);
        return srcLen;
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcLen) {
        checkFree();

        srcLen = Math.min(src.remaining(), srcLen);
        checkExtension(offset, srcLen);

        ((Buffer) this.target).clear().position(offset);
        ByteBuffer slice = src.duplicate();
        ((Buffer) slice).limit(src.position() + srcLen);
        this.target.put(slice);
        ((Buffer) src).position(src.position() + srcLen);
        return srcLen;
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        checkFree();

        srcLen = Math.min(src.readableBytes(), srcLen);
        checkExtension(offset, srcLen);

        ((Buffer) this.target).clear().position(offset);
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

        ((Buffer) this.target).clear().position(offset);
        this.target.get(dst, dstOffset, dstLen);
        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuffer dst, int dstLen) {
        checkFree();

        ((Buffer) this.target).clear().position(offset).limit(offset + dstLen);
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
            newBuf = SmallBufferCache.isSmallSize(splitOffset) ? SmallBufferCache.allocDirect(splitOffset) : ByteBuffer.allocateDirect(splitOffset);
        } else {
            byte[] arr = SmallBufferCache.allocHeap(splitOffset);
            newBuf = ByteBuffer.wrap(arr);
        }

        ((Buffer) this.target).clear();
        ((Buffer) this.target).position(0);
        ((Buffer) this.target).limit(splitOffset);
        newBuf.put(this.target);
        ((Buffer) newBuf).flip();

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

        WrapByteBuffer slicedBuf = WrapByteBuffer.RECYCLER.get();
        slicedBuf.initBuffer(newBuf, false);
        return slicedBuf;
    }

    @Override
    protected void _free() {
        RECYCLER.recycle(this);
    }

    @Override
    public int capacity() {
        return Math.min(this.target.capacity(), this.getMaxCapacity());
    }

    @Override
    public boolean isDirect() {
        return this.target.isDirect();
    }

    @Override
    public AutoByteBuffer copy() {
        checkFree();

        ByteBuffer copyBuffer = this.alloc.jvmBuffer(this.target.capacity());
        ((Buffer) this.target).clear();
        copyBuffer.put(this.target);

        AutoByteBuffer byteBuf = AutoByteBuffer.RECYCLER.get();
        byteBuf.initBuffer(this.alloc, this.getMaxCapacity(), this.extensionSize, copyBuffer);
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
        return "AutoByteBuffer";
    }
}
