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
import net.hasor.cobble.ref.RecycleObjectPool;
/**
 * Fixed-capacity circular {@link ByteBuf} backed by a heap {@code byte[]}.
 * <p>The physical storage is indexed as a ring, while the logical read/write
 * indexes continue to move forward and are masked into the backing array.
 * Capacity is rounded up to a power of two so wrapping can be implemented with
 * a cheap bit mask.
 * <pre>
 * physical array (power-of-two sized)
 *   target[0]  target[1]  target[2]  ...  target[capacity-1]
 *      ^                                           |
 *      |___________________________________________|
 * logical to physical mapping
 *   physicalIndex = logicalIndex & capacityMask
 * example after wrap-around
 *   logical indexes:  ... 14 15 16 17 18 19
 *   mask (capacity=16):    14 15  0  1  2  3
 *                         [---- tail ----][-- head --]
 * </pre>
 * <p>Unlike auto-expanding buffers, this implementation never grows. Writes are
 * still bounded by the current logical writable space defined by the base
 * {@link AbstractByteBuf} contract; the ring layout only changes how bytes are
 * stored internally.
 * <p>When initial data is supplied, the buffer starts with its readable region
 * already populated; otherwise it starts empty.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 * @see RingByteBuffer
 * @see AutoArrayByteBuf
 */
final class RingArrayByteBuf extends AbstractByteBuf {
    static final RecycleObjectPool.Recycler<RingArrayByteBuf> RECYCLER = RecycleObjectPool.recycler(//
            RingArrayByteBuf::new, RingArrayByteBuf::resetState, RingArrayByteBuf::onRecycle);
    byte[]                                                target;
    private int                                           capacityMask;

    private RingArrayByteBuf() {
    }

    private void resetState() {
        this.target = null;
        this.capacityMask = 0;
    }

    private void onRecycle() {
        this.target = null;
        this.capacityMask = 0;
    }

    /** Round up to the next power of 2 (for bitwise index masking). */
    private static int nextPowerOf2(int val) {
        if (val <= 1) {
            return val;
        }
        int n = val - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return n + 1;
    }

    void initBuffer(ByteBufAllocator alloc, byte[] initData) {
        super.initByteBuf(alloc, initData.length);
        this.target = initData;
        this.capacityMask = initData.length > 0 ? initData.length - 1 : 0;
        this.writerIndex = initData.length;
        this.markedWriterIndex = initData.length;
    }

    // ------------------------------------------------------------------------

    void initBuffer(ByteBufAllocator alloc, int capacity) {
        int roundedCapacity = capacity > 0 ? nextPowerOf2(capacity) : capacity;
        super.initByteBuf(alloc, ObjectUtils.checkPositiveOrZero(roundedCapacity, "capacity"));
        this.target = new byte[roundedCapacity];
        this.capacityMask = roundedCapacity > 0 ? roundedCapacity - 1 : 0;
        this.writerIndex = 0;
        this.markedWriterIndex = 0;
    }

    @Override
    public ByteBuf markReader() {
        if (this.markedReaderIndex != this.readerIndex) {
            this.markedReaderIndex = this.readerIndex;
            this.updateIndex();
        }
        return this;
    }

    private void updateIndex() {
        int capacity = this.target.length;
        if (this.markedReaderIndex >= capacity) {
            this.markedReaderIndex = this.markedReaderIndex - capacity;
            this.markedWriterIndex = this.markedWriterIndex - capacity;
            this.readerIndex = this.readerIndex - capacity;
            this.writerIndex = this.writerIndex - capacity;
        }
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();

        int offsetSize = offset & this.capacityMask;
        this.target[offsetSize] = b;
    }

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        checkFree();

        int maxCap = this.getMaxCapacity();
        int offsetSize = offset & this.capacityMask;

        if ((offsetSize + srcLen) <= maxCap) {
            System.arraycopy(src, srcOffset, this.target, offsetSize, srcLen);
            return srcLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = srcLen - partA;

            System.arraycopy(src, srcOffset, this.target, offsetSize, partA);

            if (partB > 0) {
                System.arraycopy(src, srcOffset + partA, this.target, 0, partB);
                return partA + partB;
            } else {
                return partA;
            }
        }
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcLen) {
        checkFree();

        int maxCap = this.getMaxCapacity();
        int offsetSize = offset & this.capacityMask;
        srcLen = Math.min(src.remaining(), srcLen);

        if ((offsetSize + srcLen) <= maxCap) {
            src.get(this.target, offsetSize, srcLen);
            return srcLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = srcLen - partA;

            src.get(this.target, offsetSize, partA);

            if (partB > 0) {
                src.get(this.target, 0, partB);
                return partA + partB;
            } else {
                return partA;
            }
        }
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        checkFree();

        int maxCap = this.getMaxCapacity();
        int offsetSize = offset & this.capacityMask;
        srcLen = Math.min(src.readableBytes(), srcLen);

        if ((offsetSize + srcLen) <= maxCap) {
            src.readBytes(this.target, offsetSize, srcLen);
            return srcLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = srcLen - partA;

            src.readBytes(this.target, offsetSize, partA);

            if (partB > 0) {
                src.readBytes(this.target, 0, partB);
                return partA + partB;
            } else {
                return partA;
            }
        }
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        int offsetSize = offset & this.capacityMask;
        return this.target[offsetSize];
    }

    @Override
    protected int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        checkFree();

        int maxCap = this.getMaxCapacity();
        int offsetSize = offset & this.capacityMask;

        if ((offsetSize + dstLen) <= maxCap) {
            System.arraycopy(this.target, offsetSize, dst, dstOffset, dstLen);
            return dstLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = dstLen - partA;

            System.arraycopy(this.target, offsetSize, dst, dstOffset, partA);

            if (partB > 0) {
                System.arraycopy(this.target, 0, dst, dstOffset + partA, partB);
                return partA + partB;
            } else {
                return partA;
            }
        }
    }

    @Override
    protected int _getBytes(int offset, ByteBuffer dst, int dstLen) {
        checkFree();

        int maxCap = this.getMaxCapacity();
        int offsetSize = offset & this.capacityMask;

        if ((offsetSize + dstLen) <= maxCap) {
            dst.put(this.target, offsetSize, dstLen);
            return dstLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = dstLen - partA;

            dst.put(this.target, offsetSize, partA);

            if (partB > 0) {
                dst.put(this.target, 0, partB);
                return partA + partB;
            } else {
                return partA;
            }
        }
    }

    @Override
    protected int _getBytes(int offset, ByteBuf dst, int dstLen) {
        checkFree();

        int maxCap = this.getMaxCapacity();
        int offsetSize = offset & this.capacityMask;

        if ((offsetSize + dstLen) <= maxCap) {
            dst.writeBytes(this.target, offsetSize, dstLen);
            return dstLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = dstLen - partA;

            dst.writeBytes(this.target, offsetSize, partA);
            if (partB > 0) {
                dst.writeBytes(this.target, 0, partB);
                return partA + partB;
            } else {
                return partA;
            }
        }
    }

    // ==========================================================================
    // Optimized hot-path overrides: eliminate checkFree (AtomicInteger.get),
    // ThreadLocal.get for TMP8, intermediate byte[] copy, and virtual dispatch.
    // Ring buffer uses (offset & capacityMask) for index wrapping.
    // ==========================================================================

    @Override
    public byte readByte() {
        checkFree();
        return this.target[nextReadableN(1) & this.capacityMask];
    }

    @Override
    public void writeByte(byte n) {
        checkFree();
        this.target[nextWritableN(1) & this.capacityMask] = n;
    }

    @Override
    public short readInt16() {
        checkFree();
        int idx = nextReadableN(2);
        byte[] t = this.target;
        int mask = this.capacityMask;
        int maskedIdx = idx & mask;
        // Fast path: data doesn't cross ring boundary
        if (maskedIdx + 2 <= t.length && UnsafeMemory.HAS_FAST_ARRAY_ACCESS) {
            return UnsafeMemory.getInt16(t, maskedIdx, bigEndian);
        }
        byte b0 = t[maskedIdx];
        byte b1 = t[(idx + 1) & mask];
        if (bigEndian) {
            return (short) ((b0 << 8) | (b1 & 0xff));
        } else {
            return (short) ((b1 << 8) | (b0 & 0xff));
        }
    }

    @Override
    public void writeInt16(short n) {
        checkFree();
        int idx = nextWritableN(2);
        byte[] t = this.target;
        int mask = this.capacityMask;
        int maskedIdx = idx & mask;
        // Fast path: data doesn't cross ring boundary
        if (maskedIdx + 2 <= t.length && UnsafeMemory.HAS_FAST_ARRAY_ACCESS) {
            UnsafeMemory.putInt16(t, maskedIdx, n, bigEndian);
            return;
        }
        if (bigEndian) {
            t[idx & mask] = (byte) (n >> 8);
            t[(idx + 1) & mask] = (byte) n;
        } else {
            t[idx & mask] = (byte) n;
            t[(idx + 1) & mask] = (byte) (n >> 8);
        }
    }

    @Override
    public int readInt32() {
        checkFree();
        int idx = nextReadableN(4);
        byte[] t = this.target;
        int mask = this.capacityMask;
        int maskedIdx = idx & mask;
        // Fast path: data doesn't cross ring boundary
        if (maskedIdx + 4 <= t.length && UnsafeMemory.HAS_FAST_ARRAY_ACCESS) {
            return UnsafeMemory.getInt32(t, maskedIdx, bigEndian);
        }
        byte b0 = t[maskedIdx];
        byte b1 = t[(idx + 1) & mask];
        byte b2 = t[(idx + 2) & mask];
        byte b3 = t[(idx + 3) & mask];
        if (bigEndian) {
            return (b0 << 24) | ((b1 & 0xff) << 16) | ((b2 & 0xff) << 8) | (b3 & 0xff);
        } else {
            return (b3 << 24) | ((b2 & 0xff) << 16) | ((b1 & 0xff) << 8) | (b0 & 0xff);
        }
    }

    @Override
    public void writeInt32(int n) {
        checkFree();
        int idx = nextWritableN(4);
        byte[] t = this.target;
        int mask = this.capacityMask;
        int maskedIdx = idx & mask;
        // Fast path: data doesn't cross ring boundary
        if (maskedIdx + 4 <= t.length && UnsafeMemory.HAS_FAST_ARRAY_ACCESS) {
            UnsafeMemory.putInt32(t, maskedIdx, n, bigEndian);
            return;
        }
        if (bigEndian) {
            t[idx & mask] = (byte) (n >> 24);
            t[(idx + 1) & mask] = (byte) (n >> 16);
            t[(idx + 2) & mask] = (byte) (n >> 8);
            t[(idx + 3) & mask] = (byte) n;
        } else {
            t[idx & mask] = (byte) n;
            t[(idx + 1) & mask] = (byte) (n >> 8);
            t[(idx + 2) & mask] = (byte) (n >> 16);
            t[(idx + 3) & mask] = (byte) (n >> 24);
        }
    }

    @Override
    public long readInt64() {
        checkFree();
        int idx = nextReadableN(8);
        byte[] t = this.target;
        int mask = this.capacityMask;
        int maskedIdx = idx & mask;
        // Fast path: data doesn't cross ring boundary
        if (maskedIdx + 8 <= t.length && UnsafeMemory.HAS_FAST_ARRAY_ACCESS) {
            return UnsafeMemory.getInt64(t, maskedIdx, bigEndian);
        }
        byte b0 = t[maskedIdx];
        byte b1 = t[(idx + 1) & mask];
        byte b2 = t[(idx + 2) & mask];
        byte b3 = t[(idx + 3) & mask];
        byte b4 = t[(idx + 4) & mask];
        byte b5 = t[(idx + 5) & mask];
        byte b6 = t[(idx + 6) & mask];
        byte b7 = t[(idx + 7) & mask];
        if (bigEndian) {
            return ((long) b0 << 56) | ((long) (b1 & 0xff) << 48) | ((long) (b2 & 0xff) << 40) | ((long) (b3 & 0xff) << 32) | ((long) (b4 & 0xff) << 24) | ((long) (b5 & 0xff) << 16) | ((long) (b6 & 0xff) << 8) | ((long) (b7 & 0xff));
        } else {
            return ((long) b7 << 56) | ((long) (b6 & 0xff) << 48) | ((long) (b5 & 0xff) << 40) | ((long) (b4 & 0xff) << 32) | ((long) (b3 & 0xff) << 24) | ((long) (b2 & 0xff) << 16) | ((long) (b1 & 0xff) << 8) | ((long) (b0 & 0xff));
        }
    }

    @Override
    public void writeInt64(long n) {
        checkFree();
        int idx = nextWritableN(8);
        byte[] t = this.target;
        int mask = this.capacityMask;
        int maskedIdx = idx & mask;
        // Fast path: data doesn't cross ring boundary
        if (maskedIdx + 8 <= t.length && UnsafeMemory.HAS_FAST_ARRAY_ACCESS) {
            UnsafeMemory.putInt64(t, maskedIdx, n, bigEndian);
            return;
        }
        if (bigEndian) {
            t[idx & mask] = (byte) (n >> 56);
            t[(idx + 1) & mask] = (byte) (n >> 48);
            t[(idx + 2) & mask] = (byte) (n >> 40);
            t[(idx + 3) & mask] = (byte) (n >> 32);
            t[(idx + 4) & mask] = (byte) (n >> 24);
            t[(idx + 5) & mask] = (byte) (n >> 16);
            t[(idx + 6) & mask] = (byte) (n >> 8);
            t[(idx + 7) & mask] = (byte) n;
        } else {
            t[idx & mask] = (byte) n;
            t[(idx + 1) & mask] = (byte) (n >> 8);
            t[(idx + 2) & mask] = (byte) (n >> 16);
            t[(idx + 3) & mask] = (byte) (n >> 24);
            t[(idx + 4) & mask] = (byte) (n >> 32);
            t[(idx + 5) & mask] = (byte) (n >> 40);
            t[(idx + 6) & mask] = (byte) (n >> 48);
            t[(idx + 7) & mask] = (byte) (n >> 56);
        }
    }

    @Override
    public int readBytes(byte[] dst, int off, int len) {
        checkFree();
        ObjectUtils.checkPositiveOrZero(off, "off");
        ObjectUtils.checkPositiveOrZero(len, "len");
        int minLen = Math.min(len, this.markedWriterIndex - this.readerIndex);
        int idx = nextReadableN(minLen);
        // delegate to existing _getBytes which handles wrap-around
        _getBytes(idx, dst, off, minLen);
        return minLen;
    }

    @Override
    public int writeBytes(byte[] src, int off, int len) {
        checkFree();
        ObjectUtils.checkPositiveOrZero(len, "len");
        int minLen = Math.min(len, this.getMaxCapacity() - (this.writerIndex - this.markedReaderIndex));
        int idx = nextWritableN(minLen);
        // delegate to existing _putBytes which handles wrap-around
        _putBytes(idx, src, off, minLen);
        return minLen;
    }

    @Override
    public void discardReadBytes() {
        throw new UnsupportedOperationException("RingArrayByteBuf can not discardReadBytes");
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
        this._getBytes(this.readerIndex, sliceData, 0, splitOffset);

        this.readerIndex += splitOffset;
        this.markedReaderIndex = Math.min(this.markedReaderIndex, this.readerIndex);

        ByteBuffer newBuf = ByteBuffer.wrap(sliceData);
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
        return this.getMaxCapacity();
    }

    @Override
    public boolean isDirect() {
        return false;
    }

    @Override
    public RingArrayByteBuf copy() {
        checkFree();

        byte[] copyArray = new byte[this.getMaxCapacity()];
        this._getBytes(this.markedReaderIndex, copyArray, 0, copyArray.length);
        RingArrayByteBuf byteBuf = RingArrayByteBuf.RECYCLER.get();
        byteBuf.initBuffer(this.alloc, copyArray);

        int shift = this.markedReaderIndex;
        byteBuf.markedReaderIndex = 0;
        byteBuf.readerIndex = this.readerIndex - shift;
        byteBuf.markedWriterIndex = this.markedWriterIndex - shift;
        byteBuf.writerIndex = this.writerIndex - shift;
        byteBuf.byteOrder = this.byteOrder;
        byteBuf.bigEndian = this.bigEndian;
        return byteBuf;
    }

    @Override
    protected String getSimpleName() {
        return "RingArrayByteBuf";
    }
}
