/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import net.hasor.cobble.ref.RecycleObjectPool;
/**
 * Fixed-capacity circular {@link ByteBuf} backed by a {@link java.nio.ByteBuffer}.
 * <p>Its semantics match {@link RingArrayByteBuf}: logical read/write indexes
 * grow normally, while physical storage wraps through a power-of-two capacity
 * mask. The ring layout changes only how bytes are stored internally; it does
 * not bypass the ordinary readable/writable bounds enforced by
 * {@link AbstractByteBuf}.
 * <pre>
 * ByteBuffer-backed ring
 *   ByteBuffer target
 *   +---------------------------------------------------------------+
 *   | 0 | 1 | 2 | 3 | ... | capacity-2 | capacity-1 |
 *   +---------------------------------------------------------------+
 *      ^                                            |
 *      |____________ circular address space ________|
 *   logicalIndex --(logicalIndex & capacityMask)--> physical slot in target
 * </pre>
 * <p>Instances created from a requested capacity are rounded up to the next
 * power of two so the wrap calculation can use a cheap bit mask. Instances
 * built around an existing {@link ByteBuffer} assume that the supplied capacity
 * already matches the expected ring layout.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 * @see RingArrayByteBuf
 * @see AutoByteBuffer
 */
final class RingByteBuffer extends AbstractByteBuf {
    static final RecycleObjectPool.Recycler<RingByteBuffer> RECYCLER = RecycleObjectPool.recycler(//
            RingByteBuffer::new, RingByteBuffer::resetState, RingByteBuffer::onRecycle);
    ByteBuffer                                          target;
    private int                                         capacityMask;

    private RingByteBuffer() {
    }

    private void resetState() {
        this.target = null;
        this.capacityMask = 0;
    }

    private void onRecycle() {
        ByteBuffer oldTarget = this.target;
        this.target = null;
        this.capacityMask = 0;
        if (oldTarget != null && !SmallBufferCache.freeDirect(oldTarget) && ByteBufUtils.CLEANER != null) {
            ByteBufUtils.CLEANER.freeDirectBuffer(oldTarget);
        }
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

    void initBuffer(ByteBufAllocator alloc, int capacity) {
        int roundedCapacity = capacity > 0 ? nextPowerOf2(capacity) : capacity;
        super.initByteBuf(alloc, roundedCapacity);
        this.target = alloc.jvmBuffer(roundedCapacity);
        this.capacityMask = roundedCapacity > 0 ? roundedCapacity - 1 : 0;
    }

    // ------------------------------------------------------------------------

    void initBuffer(ByteBufAllocator alloc, ByteBuffer initData) {
        super.initByteBuf(alloc, initData.capacity());
        this.target = initData;
        this.capacityMask = initData.capacity() > 0 ? initData.capacity() - 1 : 0;
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
        int capacity = this.target.capacity();
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
        this.target.put(offsetSize, b);
    }

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        checkFree();

        int maxCap = this.getMaxCapacity();
        int offsetSize = offset & this.capacityMask;

        if ((offsetSize + srcLen) <= maxCap) {
            ((Buffer) this.target).clear();
            ((Buffer) this.target).position(offsetSize);
            this.target.put(src, srcOffset, srcLen);
            return srcLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = srcLen - partA;

            ((Buffer) this.target).clear();
            ((Buffer) this.target).position(offsetSize);
            this.target.put(src, srcOffset, partA);

            if (partB > 0) {
                ((Buffer) this.target).clear();
                this.target.put(src, srcOffset + partA, partB);
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
            ((Buffer) this.target).clear();
            ((Buffer) this.target).position(offsetSize);
            ByteBuffer slice = src.duplicate();
            ((Buffer) slice).limit(src.position() + srcLen);
            this.target.put(slice);
            ((Buffer) src).position(src.position() + srcLen);
            return srcLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = srcLen - partA;

            ((Buffer) this.target).clear();
            ((Buffer) this.target).position(offsetSize);
            ByteBuffer sliceA = src.duplicate();
            ((Buffer) sliceA).limit(src.position() + partA);
            this.target.put(sliceA);
            ((Buffer) src).position(src.position() + partA);

            if (partB > 0) {
                ((Buffer) this.target).clear();
                ByteBuffer sliceB = src.duplicate();
                ((Buffer) sliceB).limit(src.position() + partB);
                this.target.put(sliceB);
                ((Buffer) src).position(src.position() + partB);
            }

            return partA + partB;
        }
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        checkFree();

        int maxCap = this.getMaxCapacity();
        int offsetSize = offset & this.capacityMask;
        srcLen = Math.min(src.readableBytes(), srcLen);

        if ((offsetSize + srcLen) <= maxCap) {
            ((Buffer) this.target).clear();
            ((Buffer) this.target).position(offsetSize);
            src.readBuffer(this.target, srcLen);
            return srcLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = srcLen - partA;

            ((Buffer) this.target).clear();
            ((Buffer) this.target).position(offsetSize);
            src.readBuffer(this.target, partA);

            if (partB > 0) {
                ((Buffer) this.target).clear();
                src.readBuffer(this.target, partB);
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
        return this.target.get(offsetSize);
    }

    @Override
    protected int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        checkFree();

        int maxCap = this.getMaxCapacity();
        int offsetSize = offset & this.capacityMask;

        if ((offsetSize + dstLen) <= maxCap) {
            ((Buffer) this.target).clear();
            ((Buffer) this.target).position(offsetSize);
            this.target.get(dst, dstOffset, dstLen);
            return dstLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = dstLen - partA;

            ((Buffer) this.target).clear();
            ((Buffer) this.target).position(offsetSize);
            ((Buffer) this.target).limit(offsetSize + partA);
            this.target.get(dst, dstOffset, partA);

            if (partB > 0) {
                ((Buffer) this.target).clear();
                ((Buffer) this.target).limit(partB);
                this.target.get(dst, dstOffset + partA, partB);
                ((Buffer) this.target).clear();
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
            ((Buffer) this.target).clear();
            ((Buffer) this.target).position(offsetSize);
            ((Buffer) this.target).limit(offsetSize + dstLen);
            dst.put(this.target);
            ((Buffer) this.target).clear();
            return dstLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = dstLen - partA;

            ((Buffer) this.target).clear();
            ((Buffer) this.target).position(offsetSize);
            ((Buffer) this.target).limit(offsetSize + partA);
            dst.put(this.target);

            if (partB > 0) {
                ((Buffer) this.target).clear();
                ((Buffer) this.target).limit(partB);
                dst.put(this.target);
                ((Buffer) this.target).clear();
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
            ((Buffer) this.target).clear();
            ((Buffer) this.target).position(offsetSize);
            dst.writeBuffer(this.target, dstLen);
            return dstLen;
        } else {
            int partA = maxCap - offsetSize;
            int partB = dstLen - partA;

            ((Buffer) this.target).clear();
            ((Buffer) this.target).position(offsetSize);
            dst.writeBuffer(this.target, partA);

            if (partB > 0) {
                ((Buffer) this.target).clear();
                dst.writeBuffer(this.target, partB);
                return partA + partB;
            } else {
                return partA;
            }
        }
    }

    @Override
    public void discardReadBytes() {
        throw new UnsupportedOperationException("RingByteBuffer can not discardReadBytes");
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

        ByteBuffer newBuf;
        if (this.isDirect()) {
            newBuf = ByteBuffer.allocateDirect(splitOffset);
            newBuf.put(sliceData);
            ((Buffer) newBuf).flip();
        } else {
            newBuf = ByteBuffer.wrap(sliceData);
        }

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
        return this.target.isDirect();
    }

    @Override
    public RingByteBuffer copy() {
        checkFree();

        int capacity = this.getMaxCapacity();
        ByteBuffer copyBuffer = this.alloc.jvmBuffer(capacity);
        this._getBytes(this.markedReaderIndex, copyBuffer, copyBuffer.capacity());
        RingByteBuffer byteBuf = RingByteBuffer.RECYCLER.get();
        byteBuf.initBuffer(this.alloc, copyBuffer);

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
        return "RingByteBuffer";
    }
}
