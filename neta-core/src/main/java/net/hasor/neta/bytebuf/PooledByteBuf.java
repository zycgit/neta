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

/**
 * 基于 {@link Buffer} 池化的的窗口 {@link ByteBuf} 实现，同时如果容量不足它会自动扩缩容
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
final class PooledByteBuf extends AbstractByteBuf {
    static final int                          RECYCLE_INDEX   = RecycleObjectPool.registerType();
    static       RecycleHandler<PooledByteBuf> RECYCLE_HANDLER = new RecycleHandler<PooledByteBuf>() {
        public PooledByteBuf create() {
            return new PooledByteBuf();
        }

        @Override
        public void free(PooledByteBuf tar) {
            RecycleObjectPool.free(RECYCLE_INDEX, tar);
        }
    };

    /** Thread-local cache for recently freed pooled Buffers (pages stay allocated). */
    private static final int MAX_BUFFER_CACHE = 8;
    static final ThreadLocal<java.util.ArrayDeque<Buffer>> BUFFER_CACHE =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);
    protected Buffer                        target;
    private   BufferPool                    pool;
    // Cached heap array + offset for fast-path access (null for direct buffers)
    private   byte[]                        heapArray;
    private   int                           heapOffset;
    // Cached direct buffer base address for Unsafe off-heap access (0 for heap buffers)
    private   long                          directAddress;

    // ------------------------------------------------------------------------
    private int initSize;
    private int extensionSize;

    private PooledByteBuf() {
    }

    void initBuffer(ByteBufAllocator alloc, int maxCapacity, int extensionSize, Buffer target, BufferPool pool) {
        super.initByteBuf(alloc, maxCapacity);
        this.target = target;
        this.pool = pool;
        this.initSize = target.capacity();
        this.extensionSize = Math.min(extensionSize, maxCapacity);
        cacheHeapArray();
    }

    private void cacheHeapArray() {
        Buffer t = this.target;
        if (t != null) {
            // Fast path: use Buffer.heapArray() to avoid triggering lazy ByteBuffer creation
            byte[] ha = t.heapArray();
            if (ha != null) {
                this.heapArray = ha;
                this.heapOffset = t.heapArrayOffset() + t.getOffset();
                this.directAddress = 0;
                return;
            }
            // Direct buffer path: use Unsafe for direct address
            if (t.isDirect() && UnsafeMemory.HAS_UNSAFE) {
                java.nio.ByteBuffer bb = t.getTarget();
                this.heapArray = null;
                this.heapOffset = 0;
                this.directAddress = UnsafeMemory.getDirectAddress(bb) + t.getOffset();
                return;
            }
            // Fallback: use getTarget() for non-direct buffers that don't support heapArray()
            if (!t.isDirect()) {
                java.nio.ByteBuffer bb = t.getTarget();
                if (bb != null && bb.hasArray()) {
                    this.heapArray = bb.array();
                    this.heapOffset = bb.arrayOffset() + t.getOffset();
                    this.directAddress = 0;
                    return;
                }
            }
        }
        this.heapArray = null;
        this.heapOffset = 0;
        this.directAddress = 0;
    }

    @Override
    public ByteBuf markReader() {
        if (this.markedReaderIndex != this.readerIndex) {
            this.markedReaderIndex = this.readerIndex;
            this.recycle();
        }
        return this;
    }

    @Override
    public void discardReadBytes() {
        if (this.readerIndex == 0) {
            return;
        }

        if (this.target instanceof BufferTarget) {
            if (this.readerIndex > 0) {
                try {
                    Buffer separatedFn = ((BufferTarget) this.target).split(this.readerIndex - 1);
                    separatedFn.free(); // Free the discarded part (Zero-Copy release if page aligned)

                    int decrement = this.readerIndex;
                    this.writerIndex -= decrement;
                    this.readerIndex = 0;
                    this.markedReaderIndex = Math.max(0, this.markedReaderIndex - decrement);
                    this.markedWriterIndex = Math.max(0, this.markedWriterIndex - decrement);
                    return;
                } catch (Exception e) {
                    // Fallback to generic implementation
                }
            }
        }

        // Generic fallback implementation (zero-copy in-buffer move via ByteBuffer slice)
        if (this.readerIndex != this.writerIndex) {
            int moveSize = this.writerIndex - this.readerIndex;
            ByteBuffer buf = this.target.getTarget().duplicate();
            int base = this.target.getOffset();

            // Create a slice of the readable region
            ((java.nio.Buffer) buf).clear();
            ((java.nio.Buffer) buf).position(base + this.readerIndex);
            ((java.nio.Buffer) buf).limit(base + this.readerIndex + moveSize);
            ByteBuffer src = buf.slice();

            // Copy it back to the beginning
            ((java.nio.Buffer) buf).clear();
            ((java.nio.Buffer) buf).position(base);
            buf.put(src);

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

        // Read data from current buffer
        byte[] data = new byte[splitOffset];
        this._getBytes(0, data, 0, splitOffset);

        // Shift remaining data to the beginning
        int remaining = this.capacity() - splitOffset;
        if (remaining > 0) {
            byte[] remainingData = new byte[remaining];
            this._getBytes(splitOffset, remainingData, 0, remaining);
            this._putBytes(0, remainingData, 0, remaining);
        }

        // Adjust indices
        this.writerIndex = Math.max(0, this.writerIndex - splitOffset);
        this.readerIndex = Math.max(0, this.readerIndex - splitOffset);
        this.markedReaderIndex = Math.max(0, this.markedReaderIndex - splitOffset);
        this.markedWriterIndex = Math.max(0, this.markedWriterIndex - splitOffset);

        // Create WrapByteBuffer, heap/direct follows source
        ByteBuffer newBuf;
        if (this.isDirect()) {
            newBuf = SmallBufferCache.isSmallSize(splitOffset) ? SmallBufferCache.allocDirect(splitOffset) : ByteBuffer.allocateDirect(splitOffset);
            newBuf.put(data);
            ((java.nio.Buffer) newBuf).flip();
        } else {
            byte[] arr = SmallBufferCache.allocHeap(splitOffset);
            System.arraycopy(data, 0, arr, 0, splitOffset);
            newBuf = ByteBuffer.wrap(arr);
        }

        WrapByteBuffer slicedBuf = RecycleObjectPool.get(WrapByteBuffer.RECYCLE_INDEX, WrapByteBuffer.RECYCLE_HANDLER);
        slicedBuf.initBuffer(newBuf, false);
        return slicedBuf;
    }

    private void recycle() {
        Buffer toFreeTarget = null;
        try {
            int requestSize = this.writerIndex - this.markedReaderIndex;
            Buffer extTarget = allocateSmallOrPooled(evalSize(requestSize));
            toFreeTarget = extTarget;// when try failed, free requestBuffer.

            if (requestSize > 0) {
                ByteBuffer targetBuf = extTarget.getTarget().duplicate();
                ((java.nio.Buffer) targetBuf).clear();
                ((java.nio.Buffer) targetBuf).position(extTarget.getOffset());
                this.target.get(this.markedReaderIndex, targetBuf, requestSize);
            }
            toFreeTarget = this.target;
            this.target = extTarget;
            cacheHeapArray();

            int recyclePos = this.markedReaderIndex;
            this.writerIndex = this.writerIndex - recyclePos;
            this.markedWriterIndex = this.markedWriterIndex - recyclePos;
            this.readerIndex = this.readerIndex - recyclePos;
            this.markedReaderIndex = 0;
        } finally {
            if (toFreeTarget != null) {
                toFreeTarget.free();
            }
        }
    }

    private int evalSize(int requestSize) {
        int maxCap = this.getMaxCapacity();
        int newSize;
        if (requestSize == 0) {
            return this.initSize;
        } else if ((requestSize % this.extensionSize) > 0) {
            int rate = (requestSize / this.extensionSize) + 1;
            newSize = (int) Math.min((long) rate * this.extensionSize, maxCap);
        } else {
            newSize = (int) Math.min((long) requestSize + this.extensionSize, maxCap);
        }
        return Math.min(newSize, maxCap);
    }

    /**
     * Allocate buffer using SmallBufferCache for small sizes, or BufferPool for normal sizes.
     * This enables natural transition from small cached buffers to pooled buddy-algorithm buffers.
     */
    private Buffer allocateSmallOrPooled(int size) {
        if (SmallBufferCache.isSmallSize(size)) {
            return SmallBufferCache.allocSmallBuffer(this.alloc.isDirect(), size);
        } else {
            return this.pool.requestBuffer(size, this.alloc);
        }
    }

    private void checkExtension(int offset, int len) {
        int requestSize = offset + len;
        if (requestSize > this.capacity()) {
            Buffer toFreeTarget = null;
            try {
                int copyLen = Math.min(this.writerIndex, this.target.capacity());
                Buffer extTarget = allocateSmallOrPooled(evalSize(requestSize));
                toFreeTarget = extTarget;// when try failed, free requestBuffer.

                ByteBuffer targetBuf = extTarget.getTarget().duplicate();
                ((java.nio.Buffer) targetBuf).clear();
                ((java.nio.Buffer) targetBuf).position(extTarget.getOffset());
                if (copyLen > 0) {
                    this.target.get(0, targetBuf, copyLen);
                }
                toFreeTarget = this.target;
                this.target = extTarget;
                cacheHeapArray();
            } finally {
                if (toFreeTarget != null) {
                    toFreeTarget.free();
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

        this.target.put(offset, src, srcOffset, srcLen);
        return srcLen;
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcLen) {
        checkFree();

        srcLen = Math.min(src.remaining(), srcLen);
        checkExtension(offset, srcLen);

        this.target.put(offset, src, srcLen);
        return srcLen;
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        checkFree();

        srcLen = Math.min(src.readableBytes(), srcLen);
        checkExtension(offset, srcLen);

        ByteBuffer tarBuf = this.target.getTarget().duplicate();
        int tarOffset = this.target.getOffset() + offset;

        ((java.nio.Buffer) tarBuf).clear();
        ((java.nio.Buffer) tarBuf).position(tarOffset);
        return src.readBuffer(tarBuf, srcLen);
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        return this.target.get(offset);
    }

    @Override
    protected int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        checkFree();

        this.target.get(offset, dst, dstOffset, dstLen);
        return dstLen;

    }

    @Override
    protected int _getBytes(int offset, ByteBuffer dst, int dstLen) {
        checkFree();

        this.target.get(offset, dst, dstLen);
        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuf dst, int dstLen) {
        checkFree();

        ByteBuffer targetBuf = this.target.getTarget().duplicate();
        int tarOffset = this.target.getOffset() + offset;

        ((java.nio.Buffer) targetBuf).clear();
        ((java.nio.Buffer) targetBuf).position(tarOffset);
        dst.writeBuffer(targetBuf, dstLen);
        return dstLen;
    }

    // ==========================================================================
    // Optimized hot-path overrides: use cached heapArray for heap pooled buffers,
    // eliminating 4-5 layers of virtual dispatch + checkFree + checkOffset.
    // Falls back to standard path for direct buffers.
    // ==========================================================================

    @Override
    public byte readByte() {
        checkFree();
        int idx = nextReadableN(1);
        byte[] arr = this.heapArray;
        if (arr != null) {
            return arr[this.heapOffset + idx];
        }
        long addr = this.directAddress;
        if (addr != 0) {
            return UnsafeMemory.getByteDirect(addr + idx);
        }
        return this.target.get(idx);
    }

    @Override
    public void writeByte(byte n) {
        checkFree();
        int idx = nextWritableN(1);
        checkExtension(idx, 1);
        byte[] arr = this.heapArray;
        if (arr != null) {
            arr[this.heapOffset + idx] = n;
            return;
        }
        long addr = this.directAddress;
        if (addr != 0) {
            UnsafeMemory.putByteDirect(addr + idx, n);
            return;
        }
        this.target.put(idx, n);
    }

    @Override
    public short readInt16() {
        checkFree();
        int idx = nextReadableN(2);
        byte[] arr = this.heapArray;
        if (arr != null) {
            int i = this.heapOffset + idx;
            if (UnsafeMemory.HAS_UNSAFE) {
                return UnsafeMemory.getInt16(arr, i, bigEndian);
            }
            if (bigEndian) {
                return (short) ((arr[i] << 8) | (arr[i + 1] & 0xff));
            } else {
                return (short) ((arr[i + 1] << 8) | (arr[i] & 0xff));
            }
        }
        long addr = this.directAddress;
        if (addr != 0) {
            return UnsafeMemory.getInt16Direct(addr + idx, bigEndian);
        }
        return Bits.decodeInt16(this, idx, bigEndian);
    }

    @Override
    public void writeInt16(short n) {
        checkFree();
        int idx = nextWritableN(2);
        checkExtension(idx, 2);
        byte[] arr = this.heapArray;
        if (arr != null) {
            int i = this.heapOffset + idx;
            if (UnsafeMemory.HAS_UNSAFE) {
                UnsafeMemory.putInt16(arr, i, n, bigEndian);
                return;
            }
            if (bigEndian) {
                arr[i] = (byte) (n >> 8);
                arr[i + 1] = (byte) n;
            } else {
                arr[i] = (byte) n;
                arr[i + 1] = (byte) (n >> 8);
            }
            return;
        }
        long addr = this.directAddress;
        if (addr != 0) {
            UnsafeMemory.putInt16Direct(addr + idx, n, bigEndian);
            return;
        }
        Bits.encodeInt16(this, idx, n, bigEndian);
    }

    @Override
    public int readInt32() {
        checkFree();
        int idx = nextReadableN(4);
        byte[] arr = this.heapArray;
        if (arr != null) {
            int i = this.heapOffset + idx;
            if (UnsafeMemory.HAS_UNSAFE) {
                return UnsafeMemory.getInt32(arr, i, bigEndian);
            }
            if (bigEndian) {
                return (arr[i] << 24) | ((arr[i + 1] & 0xff) << 16) | ((arr[i + 2] & 0xff) << 8) | (arr[i + 3] & 0xff);
            } else {
                return (arr[i + 3] << 24) | ((arr[i + 2] & 0xff) << 16) | ((arr[i + 1] & 0xff) << 8) | (arr[i] & 0xff);
            }
        }
        long addr = this.directAddress;
        if (addr != 0) {
            return UnsafeMemory.getInt32Direct(addr + idx, bigEndian);
        }
        return Bits.decodeInt32(this, idx, bigEndian);
    }

    @Override
    public void writeInt32(int n) {
        checkFree();
        int idx = nextWritableN(4);
        checkExtension(idx, 4);
        byte[] arr = this.heapArray;
        if (arr != null) {
            int i = this.heapOffset + idx;
            if (UnsafeMemory.HAS_UNSAFE) {
                UnsafeMemory.putInt32(arr, i, n, bigEndian);
                return;
            }
            if (bigEndian) {
                arr[i] = (byte) (n >> 24);
                arr[i + 1] = (byte) (n >> 16);
                arr[i + 2] = (byte) (n >> 8);
                arr[i + 3] = (byte) n;
            } else {
                arr[i] = (byte) n;
                arr[i + 1] = (byte) (n >> 8);
                arr[i + 2] = (byte) (n >> 16);
                arr[i + 3] = (byte) (n >> 24);
            }
            return;
        }
        long addr = this.directAddress;
        if (addr != 0) {
            UnsafeMemory.putInt32Direct(addr + idx, n, bigEndian);
            return;
        }
        Bits.encodeInt32(this, idx, n, bigEndian);
    }

    @Override
    public long readInt64() {
        checkFree();
        int idx = nextReadableN(8);
        byte[] arr = this.heapArray;
        if (arr != null) {
            int i = this.heapOffset + idx;
            if (UnsafeMemory.HAS_UNSAFE) {
                return UnsafeMemory.getInt64(arr, i, bigEndian);
            }
            if (bigEndian) {
                return ((long) arr[i] << 56) | ((long) (arr[i + 1] & 0xff) << 48) | ((long) (arr[i + 2] & 0xff) << 40) | ((long) (arr[i + 3] & 0xff) << 32) | ((long) (arr[i + 4] & 0xff) << 24) | ((long) (arr[i + 5] & 0xff) << 16) | ((long) (arr[i + 6] & 0xff) << 8) | ((long) (arr[i + 7] & 0xff));
            } else {
                return ((long) arr[i + 7] << 56) | ((long) (arr[i + 6] & 0xff) << 48) | ((long) (arr[i + 5] & 0xff) << 40) | ((long) (arr[i + 4] & 0xff) << 32) | ((long) (arr[i + 3] & 0xff) << 24) | ((long) (arr[i + 2] & 0xff) << 16) | ((long) (arr[i + 1] & 0xff) << 8) | ((long) (arr[i] & 0xff));
            }
        }
        long addr = this.directAddress;
        if (addr != 0) {
            return UnsafeMemory.getInt64Direct(addr + idx, bigEndian);
        }
        return Bits.decodeInt64(this, idx, bigEndian);
    }

    @Override
    public void writeInt64(long n) {
        checkFree();
        int idx = nextWritableN(8);
        checkExtension(idx, 8);
        byte[] arr = this.heapArray;
        if (arr != null) {
            int i = this.heapOffset + idx;
            if (UnsafeMemory.HAS_UNSAFE) {
                UnsafeMemory.putInt64(arr, i, n, bigEndian);
                return;
            }
            if (bigEndian) {
                arr[i] = (byte) (n >> 56);
                arr[i + 1] = (byte) (n >> 48);
                arr[i + 2] = (byte) (n >> 40);
                arr[i + 3] = (byte) (n >> 32);
                arr[i + 4] = (byte) (n >> 24);
                arr[i + 5] = (byte) (n >> 16);
                arr[i + 6] = (byte) (n >> 8);
                arr[i + 7] = (byte) n;
            } else {
                arr[i] = (byte) n;
                arr[i + 1] = (byte) (n >> 8);
                arr[i + 2] = (byte) (n >> 16);
                arr[i + 3] = (byte) (n >> 24);
                arr[i + 4] = (byte) (n >> 32);
                arr[i + 5] = (byte) (n >> 40);
                arr[i + 6] = (byte) (n >> 48);
                arr[i + 7] = (byte) (n >> 56);
            }
            return;
        }
        long addr = this.directAddress;
        if (addr != 0) {
            UnsafeMemory.putInt64Direct(addr + idx, n, bigEndian);
            return;
        }
        Bits.encodeInt64(this, idx, n, bigEndian);
    }

    @Override
    public int readBytes(byte[] dst, int off, int len) {
        checkFree();
        net.hasor.cobble.ObjectUtils.checkPositiveOrZero(off, "off");
        net.hasor.cobble.ObjectUtils.checkPositiveOrZero(len, "len");
        int minLen = Math.min(len, this.markedWriterIndex - this.readerIndex);
        int idx = nextReadableN(minLen);
        byte[] arr = this.heapArray;
        if (arr != null) {
            System.arraycopy(arr, this.heapOffset + idx, dst, off, minLen);
            return minLen;
        }
        this.target.get(idx, dst, off, minLen);
        return minLen;
    }

    @Override
    public int writeBytes(byte[] src, int off, int len) {
        checkFree();
        net.hasor.cobble.ObjectUtils.checkPositiveOrZero(len, "len");
        int minLen = Math.min(len, this.getMaxCapacity() - (this.writerIndex - this.markedReaderIndex));
        int idx = nextWritableN(minLen);
        checkExtension(idx, minLen);
        byte[] arr = this.heapArray;
        if (arr != null) {
            System.arraycopy(src, off, arr, this.heapOffset + idx, minLen);
            return minLen;
        }
        this.target.put(idx, src, off, minLen);
        return minLen;
    }

    @Override
    protected void _free() {
        try {
            Buffer t = this.target;
            if (t != null) {
                java.util.ArrayDeque<Buffer> cache = BUFFER_CACHE.get();
                if (cache.size() < MAX_BUFFER_CACHE) {
                    cache.push(t); // Cache the memory, pages stay allocated
                } else {
                    t.free(); // Cache full, return pages to buddy tree
                }
            }
        } finally {
            this.target = null;
            this.heapArray = null;
            this.heapOffset = 0;
            this.directAddress = 0;
            this.pool = null;
            RECYCLE_HANDLER.free(this);
        }
    }

    @Override
    public int capacity() {
        return this.target.capacity();
    }

    @Override
    public boolean isDirect() {
        return this.target.isDirect();
    }

    @Override
    public PooledByteBuf copy() {
        checkFree();

        int copyLen = this.writerIndex;
        Buffer target = allocateSmallOrPooled(evalSize(copyLen));
        if (copyLen > 0) {
            ByteBuffer targetBuf = target.getTarget().duplicate();
            ((java.nio.Buffer) targetBuf).clear();
            ((java.nio.Buffer) targetBuf).position(target.getOffset());
            this._getBytes(0, targetBuf, copyLen);
        }

        PooledByteBuf byteBuf = RecycleObjectPool.get(PooledByteBuf.RECYCLE_INDEX, PooledByteBuf.RECYCLE_HANDLER);
        byteBuf.initBuffer(this.alloc, this.getMaxCapacity(), this.extensionSize, target, this.pool);

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
        return "PooledByteBuf";
    }
}
