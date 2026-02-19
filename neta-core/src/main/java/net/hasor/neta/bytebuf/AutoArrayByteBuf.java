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
import net.hasor.cobble.ObjectUtils;

/**
 * 数组自动扩缩容 {@link ByteBuf} 实现
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
final class AutoArrayByteBuf extends AbstractByteBuf {
    static final int                              RECYCLE_INDEX   = RecycleObjectPool.registerType();
    static       RecycleHandler<AutoArrayByteBuf> RECYCLE_HANDLER = new RecycleHandler<AutoArrayByteBuf>() {
        public AutoArrayByteBuf create() {
            return new AutoArrayByteBuf();
        }

        @Override
        public void free(AutoArrayByteBuf tar) {
            RecycleObjectPool.free(RECYCLE_INDEX, tar);
        }
    };
    byte[] target;
    private int extensionSize;
    /** Cached effective write limit = Math.min(target.length, maxCapacity). */
    private int writeLimit;

    // ------------------------------------------------------------------------

    private AutoArrayByteBuf() {
    }

    void initBuffer(ByteBufAllocator alloc, int maxCapacity, int extensionSize, byte[] initData) {
        super.initByteBuf(alloc, maxCapacity);
        this.extensionSize = Math.min(extensionSize, maxCapacity);
        this.target = initData;
        this.writeLimit = Math.min(initData.length, maxCapacity);
    }

    @Override
    public ByteBuf markReader() {
        if (this.markedReaderIndex != this.readerIndex) {
            this.markedReaderIndex = this.readerIndex;
            this.recycle();
        }
        return this;
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
            byte[] oldTarget = this.target;
            int newSize = evalSize(requestSize);
            byte[] extension = SmallBufferCache.allocHeap(newSize);
            try {
                System.arraycopy(oldTarget, 0, extension, 0, currentCap);
                this.target = extension;
                this.writeLimit = Math.min(extension.length, this.getMaxCapacity());
                extension = null; // transfer ownership
            } finally {
                if (extension != null) {
                    SmallBufferCache.freeHeap(extension);
                }
                SmallBufferCache.freeHeap(oldTarget);
            }
        }
    }

    private void recycle() {
        int requestSize = this.writerIndex - this.markedReaderIndex;
        byte[] oldTarget = this.target;
        int newSize = evalSize(requestSize);
        byte[] recycle = SmallBufferCache.allocHeap(newSize);
        try {
            System.arraycopy(oldTarget, this.markedReaderIndex, recycle, 0, requestSize);

            int recyclePos = this.markedReaderIndex;
            this.target = recycle;
            this.writeLimit = Math.min(recycle.length, this.getMaxCapacity());
            recycle = null; // transfer ownership
            this.writerIndex = this.writerIndex - recyclePos;
            this.markedWriterIndex = this.markedWriterIndex - recyclePos;
            this.readerIndex = this.readerIndex - recyclePos;
            this.markedReaderIndex = 0;
        } finally {
            if (recycle != null) {
                SmallBufferCache.freeHeap(recycle);
            }
            SmallBufferCache.freeHeap(oldTarget);
        }
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();
        checkExtension(offset, 1);

        this.target[offset] = b;
    }

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        checkFree();
        checkExtension(offset, srcLen);

        System.arraycopy(src, srcOffset, this.target, offset, srcLen);
        return srcLen;
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcLen) {
        checkFree();

        srcLen = Math.min(src.remaining(), srcLen);
        checkExtension(offset, srcLen);

        src.get(this.target, offset, srcLen);
        return srcLen;
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        checkFree();

        srcLen = Math.min(src.readableBytes(), srcLen);
        checkExtension(offset, srcLen);

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

    // ==========================================================================
    // Optimized hot-path overrides: eliminate checkFree (AtomicInteger.get),
    // ThreadLocal.get for TMP8, intermediate byte[] copy, and virtual dispatch.
    // ==========================================================================

    @Override
    public byte readByte() {
        checkFree();
        return this.target[nextReadableN(1)];
    }

    @Override
    public void writeByte(byte n) {
        int idx = this.writerIndex;
        if (idx < this.writeLimit) {
            this.writerIndex = idx + 1;
            this.target[idx] = n;
            return;
        }
        checkFree();
        idx = nextWritableN(1);
        checkExtension(idx, 1);
        this.target[idx] = n;
    }

    @Override
    public short readInt16() {
        checkFree();
        int idx = nextReadableN(2);
        if (UnsafeMemory.HAS_UNSAFE) {
            return UnsafeMemory.getInt16(this.target, idx, bigEndian);
        }
        byte[] t = this.target;
        if (bigEndian) {
            return (short) ((t[idx] << 8) | (t[idx + 1] & 0xff));
        } else {
            return (short) ((t[idx + 1] << 8) | (t[idx] & 0xff));
        }
    }

    @Override
    public void writeInt16(short n) {
        int idx = this.writerIndex;
        byte[] t = this.target;
        if (idx + 2 <= this.writeLimit) {
            this.writerIndex = idx + 2;
        } else {
            checkFree();
            idx = nextWritableN(2);
            if (idx + 2 > t.length) {
                checkExtension(idx, 2);
                t = this.target;
            }
        }
        if (UnsafeMemory.HAS_UNSAFE) {
            UnsafeMemory.putInt16(t, idx, n, bigEndian);
            return;
        }
        if (bigEndian) {
            t[idx] = (byte) (n >> 8);
            t[idx + 1] = (byte) n;
        } else {
            t[idx] = (byte) n;
            t[idx + 1] = (byte) (n >> 8);
        }
    }

    @Override
    public int readInt32() {
        checkFree();
        int idx = nextReadableN(4);
        if (UnsafeMemory.HAS_UNSAFE) {
            return UnsafeMemory.getInt32(this.target, idx, bigEndian);
        }
        byte[] t = this.target;
        if (bigEndian) {
            return (t[idx] << 24) | ((t[idx + 1] & 0xff) << 16) | ((t[idx + 2] & 0xff) << 8) | (t[idx + 3] & 0xff);
        } else {
            return (t[idx + 3] << 24) | ((t[idx + 2] & 0xff) << 16) | ((t[idx + 1] & 0xff) << 8) | (t[idx] & 0xff);
        }
    }

    @Override
    public void writeInt32(int n) {
        if (this.freed) {
            throw new IllegalStateException("has been released.");
        }
        int idx = this.writerIndex;
        byte[] t = this.target;
        if (idx + 4 <= this.writeLimit) {
            this.writerIndex = idx + 4;
        } else {
            idx = nextWritableN(4);
            if (idx + 4 > t.length) {
                checkExtension(idx, 4);
                t = this.target;
            }
        }
        if (UnsafeMemory.HAS_UNSAFE) {
            UnsafeMemory.putInt32(t, idx, n, bigEndian);
            return;
        }
        if (bigEndian) {
            t[idx] = (byte) (n >> 24);
            t[idx + 1] = (byte) (n >> 16);
            t[idx + 2] = (byte) (n >> 8);
            t[idx + 3] = (byte) n;
        } else {
            t[idx] = (byte) n;
            t[idx + 1] = (byte) (n >> 8);
            t[idx + 2] = (byte) (n >> 16);
            t[idx + 3] = (byte) (n >> 24);
        }
    }

    @Override
    public void writeUInt32(long n) {
        int idx = this.writerIndex;
        byte[] t = this.target;
        if (idx + 4 <= this.writeLimit) {
            this.writerIndex = idx + 4;
        } else {
            checkFree();
            idx = nextWritableN(4);
            if (idx + 4 > t.length) {
                checkExtension(idx, 4);
                t = this.target;
            }
        }
        if (UnsafeMemory.HAS_UNSAFE) {
            UnsafeMemory.putUInt32(t, idx, n, bigEndian);
            return;
        }
        if (bigEndian) {
            t[idx] = (byte) (n >> 24);
            t[idx + 1] = (byte) (n >> 16);
            t[idx + 2] = (byte) (n >> 8);
            t[idx + 3] = (byte) n;
        } else {
            t[idx] = (byte) n;
            t[idx + 1] = (byte) (n >> 8);
            t[idx + 2] = (byte) (n >> 16);
            t[idx + 3] = (byte) (n >> 24);
        }
    }

    @Override
    public long readInt64() {
        checkFree();
        int idx = nextReadableN(8);
        if (UnsafeMemory.HAS_UNSAFE) {
            return UnsafeMemory.getInt64(this.target, idx, bigEndian);
        }
        byte[] t = this.target;
        if (bigEndian) {
            return ((long) t[idx] << 56) | ((long) (t[idx + 1] & 0xff) << 48) | ((long) (t[idx + 2] & 0xff) << 40) | ((long) (t[idx + 3] & 0xff) << 32) | ((long) (t[idx + 4] & 0xff) << 24) | ((long) (t[idx + 5] & 0xff) << 16) | ((long) (t[idx + 6] & 0xff) << 8) | ((long) (t[idx + 7] & 0xff));
        } else {
            return ((long) t[idx + 7] << 56) | ((long) (t[idx + 6] & 0xff) << 48) | ((long) (t[idx + 5] & 0xff) << 40) | ((long) (t[idx + 4] & 0xff) << 32) | ((long) (t[idx + 3] & 0xff) << 24) | ((long) (t[idx + 2] & 0xff) << 16) | ((long) (t[idx + 1] & 0xff) << 8) | ((long) (t[idx] & 0xff));
        }
    }

    @Override
    public void writeInt64(long n) {
        int idx = this.writerIndex;
        byte[] t = this.target;
        if (idx + 8 <= this.writeLimit) {
            this.writerIndex = idx + 8;
        } else {
            checkFree();
            idx = nextWritableN(8);
            if (idx + 8 > t.length) {
                checkExtension(idx, 8);
                t = this.target;
            }
        }
        if (UnsafeMemory.HAS_UNSAFE) {
            UnsafeMemory.putInt64(t, idx, n, bigEndian);
            return;
        }
        if (bigEndian) {
            t[idx] = (byte) (n >> 56);
            t[idx + 1] = (byte) (n >> 48);
            t[idx + 2] = (byte) (n >> 40);
            t[idx + 3] = (byte) (n >> 32);
            t[idx + 4] = (byte) (n >> 24);
            t[idx + 5] = (byte) (n >> 16);
            t[idx + 6] = (byte) (n >> 8);
            t[idx + 7] = (byte) n;
        } else {
            t[idx] = (byte) n;
            t[idx + 1] = (byte) (n >> 8);
            t[idx + 2] = (byte) (n >> 16);
            t[idx + 3] = (byte) (n >> 24);
            t[idx + 4] = (byte) (n >> 32);
            t[idx + 5] = (byte) (n >> 40);
            t[idx + 6] = (byte) (n >> 48);
            t[idx + 7] = (byte) (n >> 56);
        }
    }

    @Override
    public int readBytes(byte[] dst, int off, int len) {
        checkFree();
        ObjectUtils.checkPositiveOrZero(off, "off");
        ObjectUtils.checkPositiveOrZero(len, "len");
        int minLen = Math.min(len, this.markedWriterIndex - this.readerIndex);
        int idx = nextReadableN(minLen);
        System.arraycopy(this.target, idx, dst, off, minLen);
        return minLen;
    }

    @Override
    public int writeBytes(byte[] src, int off, int len) {
        checkFree();
        ObjectUtils.checkPositiveOrZero(len, "len");
        int minLen = Math.min(len, this.getMaxCapacity() - (this.writerIndex - this.markedReaderIndex));
        int idx = this.writerIndex;
        if (idx + minLen <= this.writeLimit) {
            this.writerIndex = idx + minLen;
        } else {
            idx = nextWritableN(minLen);
            checkExtension(idx, minLen);
        }
        System.arraycopy(src, off, this.target, idx, minLen);
        return minLen;
    }

    @Override
    public byte getByte(int offset) {
        checkFree();
        ObjectUtils.checkPositiveOrZero(offset, "offset");
        return this.target[offsetReadable(offset, 1)];
    }

    @Override
    public void setByte(int offset, byte n) {
        checkFree();
        ObjectUtils.checkPositiveOrZero(offset, "offset");
        int idx = offsetWritable(offset, 1);
        byte[] t = this.target;
        if (idx >= t.length) {
            checkExtension(idx, 1);
            t = this.target;
        }
        t[idx] = n;
    }

    @Override
    public int readBuffer(java.nio.ByteBuffer dst, int len) {
        checkFree();
        ObjectUtils.checkPositiveOrZero(len, "len");
        int minLen = Math.min(len, this.markedWriterIndex - this.readerIndex);
        int idx = nextReadableN(minLen);
        dst.put(this.target, idx, minLen);
        return minLen;
    }

    @Override
    public int writeBuffer(java.nio.ByteBuffer src, int len) {
        checkFree();
        ObjectUtils.checkPositiveOrZero(len, "len");
        int srcLen = Math.min(src.remaining(), len);
        int minLen = Math.min(srcLen, this.getMaxCapacity() - (this.writerIndex - this.markedReaderIndex));
        int idx = this.writerIndex;
        if (idx + minLen <= this.writeLimit) {
            this.writerIndex = idx + minLen;
        } else {
            idx = nextWritableN(minLen);
            checkExtension(idx, minLen);
        }
        src.get(this.target, idx, minLen);
        return minLen;
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

        byte[] sliceData = SmallBufferCache.allocHeap(splitOffset);
        System.arraycopy(this.target, 0, sliceData, 0, splitOffset);

        int remaining = this.capacity() - splitOffset;
        if (remaining > 0) {
            System.arraycopy(this.target, splitOffset, this.target, 0, remaining);
        }

        this.writerIndex = Math.max(0, this.writerIndex - splitOffset);
        this.readerIndex = Math.max(0, this.readerIndex - splitOffset);
        this.markedReaderIndex = Math.max(0, this.markedReaderIndex - splitOffset);
        this.markedWriterIndex = Math.max(0, this.markedWriterIndex - splitOffset);

        // AutoArrayByteBuf is always heap-based, wrap directly
        ByteBuffer newBuf = ByteBuffer.wrap(sliceData);

        WrapByteBuffer slicedBuf = RecycleObjectPool.get(WrapByteBuffer.RECYCLE_INDEX, WrapByteBuffer.RECYCLE_HANDLER);
        slicedBuf.initBuffer(newBuf, false);
        return slicedBuf;
    }

    @Override
    protected void _free() {
        this.writeLimit = 0;
        try {
            byte[] oldTarget = this.target;
            if (oldTarget != null) {
                SmallBufferCache.freeHeap(oldTarget);
            }
        } finally {
            this.target = null;
            RECYCLE_HANDLER.free(this);
        }
    }

    @Override
    public int capacity() {
        return this.target.length;
    }

    @Override
    public boolean isDirect() {
        return false;
    }

    @Override
    public AutoArrayByteBuf copy() {
        checkFree();

        byte[] copyArray = this.target.clone();
        AutoArrayByteBuf byteBuf = RecycleObjectPool.get(AutoArrayByteBuf.RECYCLE_INDEX, AutoArrayByteBuf.RECYCLE_HANDLER);
        byteBuf.initBuffer(this.alloc, this.getMaxCapacity(), this.extensionSize, copyArray);

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
        return "AutoArrayByteBuf";
    }
}