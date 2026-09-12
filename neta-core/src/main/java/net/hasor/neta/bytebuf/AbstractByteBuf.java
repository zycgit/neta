/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.hasor.cobble.ObjectUtils;
import static net.hasor.neta.bytebuf.Bits.*;

/**
 * Base implementation of {@link ByteBuf} index management.
 * <p>
 * The abstract class does not mandate a specific backing store, but every
 * concrete implementation follows the same logical index layout shown below.
 * <pre>
 * logical index layout
 *   0                markedReaderIndex   readerIndex   markedWriterIndex   writerIndex   capacity
 *   |----------------------|-----------------|------------------|---------------|
 *   |       ancient        |   discardable   |     readable     |  overlayable  | writable |
 *   |&lt;----- already dropped by markReader() ----&gt;|&lt;-- visible --&gt;|&lt;-- rewritable --&gt;|
 *
 * invariants
 *   0 &lt;= markedReaderIndex &lt;= readerIndex &lt;= markedWriterIndex &lt;= writerIndex &lt;= capacity
 * </pre>
 * Concrete subclasses differ only in how a logical offset is mapped to their
 * physical storage.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public abstract class AbstractByteBuf extends AbstractReferenceHolder implements ByteBuf, AutoCloseable {
    protected ByteBufAllocator                  alloc;
    protected int                               markedReaderIndex;
    protected int                               markedWriterIndex;
    protected int                               readerIndex;
    protected int                               writerIndex;
    protected ByteOrder                         byteOrder = ByteOrder.BIG_ENDIAN;
    protected boolean                           bigEndian = true;
    protected boolean                           freed     = false;
    private   int                               maxCapacity;
    private   ResourceLeakDetector.ResourceLeak leak;
    private   ByteBufAllocatorMetric            metric;
    private   boolean                           metricDirect;
    private   int                               metricCapacity;

    protected void initByteBuf(ByteBufAllocator alloc, int maxCapacity) {
        this.alloc = alloc;
        this.maxCapacity = maxCapacity == -1 ? Integer.MAX_VALUE : maxCapacity;
        this.markedReaderIndex = 0;
        this.markedWriterIndex = 0;
        this.readerIndex = 0;
        this.writerIndex = 0;
        this.resetRefCnt();
        this.freed = false;
        this.leak = LeakDetectorHolder.leakDetector.open(this);
        this.byteOrder = ByteOrder.BIG_ENDIAN;
        this.bigEndian = true;
        this.metric = null;
        this.metricDirect = false;
        this.metricCapacity = 0;
    }

    final void initMetricTracking(ByteBufAllocatorMetric metric, boolean direct, int capacity) {
        if (metric == null) {
            this.metric = null;
            this.metricDirect = false;
            this.metricCapacity = 0;
            return;
        }

        int normalizedCapacity = Math.max(capacity, 0);
        this.metric = metric;
        this.metricDirect = direct;
        this.metricCapacity = normalizedCapacity;
        metric.recordAllocation(direct, normalizedCapacity);
    }

    protected final void updateMetricCapacity(int capacity) {
        if (this.metric == null) {
            return;
        }

        int normalizedCapacity = Math.max(capacity, 0);
        if (normalizedCapacity == this.metricCapacity) {
            return;
        }

        this.metric.recordCapacityChange(this.metricDirect, normalizedCapacity - this.metricCapacity);
        this.metricCapacity = normalizedCapacity;
    }

    @Override
    public ByteBufAllocator alloc() {
        return this.alloc;
    }

    public int getMaxCapacity() {
        return this.maxCapacity;
    }

    protected final void checkFree() {
        if (this.freed) {
            throw new IllegalStateException("has been released.");
        }
    }

    @Override
    public final boolean isFree() {
        return this.isReleased();
    }

    @Override
    public ByteBuf retain() {
        super.retain();
        return this;
    }

    @Override
    public ByteBuf retain(int increment) {
        super.retain(increment);
        return this;
    }

    private void closeLeak() {
        if (this.leak != null) {
            this.leak.close();
            this.leak = null;
        }
    }

    private void releaseMetricTracking() {
        if (this.metric != null) {
            this.metric.recordRelease(this.metricDirect, this.metricCapacity);
            this.metric = null;
            this.metricDirect = false;
            this.metricCapacity = 0;
        }
    }

    @Override
    public void free() {
        release();
    }

    @Override
    protected final void deallocate() {
        this.freed = true;
        closeLeak();
        try {
            this._free();
        } finally {
            this.releaseMetricTracking();
        }
    }

    @Override
    public ByteOrder order() {
        return this.byteOrder;
    }

    @Override
    public ByteBuf order(ByteOrder newOrder) {
        this.byteOrder = newOrder;
        this.bigEndian = (newOrder == ByteOrder.BIG_ENDIAN);
        return this;
    }

    @Override
    public abstract void discardReadBytes();

    @Override
    public abstract ByteBuf sliceOff(int splitOffset);

    private boolean isBig() {
        return this.bigEndian;
    }

    protected abstract void _putByte(int offset, byte b);

    protected abstract int _putBytes(int offset, byte[] src, int srcOffset, int srcLen);

    protected abstract int _putBytes(int offset, ByteBuffer src, int srcLen);

    protected abstract int _putBytes(int offset, ByteBuf src, int srcLen);

    protected abstract byte _getByte(int offset);

    protected abstract int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen);

    protected abstract int _getBytes(int offset, ByteBuffer dst, int dstLen);

    protected abstract int _getBytes(int offset, ByteBuf dst, int dstLen);

    protected abstract void _free();

    @Override
    public ByteBuf copy(int offset, int length) {
        if (length <= 0) {
            return ByteBuf.EMPTY;
        }
        int baseOffset = offsetReadable(offset, length);
        byte[] copy = new byte[length];
        this._getBytes(baseOffset, copy, 0, length);
        return ByteBuf.wrap(copy);
    }

    @Override
    public ByteBuf slice(int offset, int length) {
        if (length <= 0) {
            return ByteBuf.EMPTY;
        }
        int baseOffset = offsetReadable(offset, length);
        return ArraySliceByteBuf.newSlice(this, baseOffset, length);
    }

    @Override
    public int readableBytes() {
        return this.markedWriterIndex - this.readerIndex;
    }

    @Override
    public int readBytes() {
        return this.readerIndex - this.markedReaderIndex;
    }

    @Override
    public int writableBytes() {
        return this.maxCapacity - (this.writerIndex - this.markedReaderIndex);
    }

    @Override
    public int writtenBytes() {
        return this.writerIndex - this.markedWriterIndex;
    }

    /** move writerIndex and returns the writerIndex before changed. */
    protected int nextWritable(int writableBytes) {
        int ori = this.writerIndex;
        // Fast path: when markedReaderIndex == 0 (common during writes), avoid reading it
        if (ori + writableBytes <= this.maxCapacity) {
            this.writerIndex = ori + writableBytes;
            return ori;
        }
        // Slow path: markedReaderIndex > 0 provides extra writable capacity
        if (writableBytes > this.maxCapacity - (ori - this.markedReaderIndex)) {
            throw new BufferOverflowException();
        }
        this.writerIndex = ori + writableBytes;
        return ori;
    }

    /**
     * Optimized final variant of nextWritable — avoids virtual call to writableBytes().
     * Fast path skips markedReaderIndex read (saves 1 field read per write in common case).
     */
    protected final int nextWritableN(int n) {
        int ori = this.writerIndex;
        // Fast path: writerIndex + n within maxCapacity (true when markedReaderIndex == 0)
        if (ori + n <= this.maxCapacity) {
            this.writerIndex = ori + n;
            return ori;
        }
        // Slow path: markedReaderIndex > 0 may allow more writes
        if (n > this.maxCapacity - (ori - this.markedReaderIndex)) {
            throw new BufferOverflowException();
        }
        this.writerIndex = ori + n;
        return ori;
    }

    /**
     * Optimized final variant of nextReadable — avoids String.format and virtual calls.
     */
    protected final int nextReadableN(int n) {
        int ri = this.readerIndex;
        if ((ri + n) > this.markedWriterIndex) {
            throw new IndexOutOfBoundsException("read out of range. length: " + n + " (expected: 0 ~ " + (this.markedWriterIndex - ri) + ")");
        }
        this.readerIndex = ri + n;
        return ri;
    }

    /** move readerIndex and returns the readerIndex before changed. */
    protected int nextReadable(int readableBytes) {
        if ((this.readerIndex + readableBytes) > this.markedWriterIndex) {
            throw new IndexOutOfBoundsException(String.format("read out of range. length: %d (expected: 0 ~ %d)", readableBytes, this.readableBytes()));
        }

        int oriReadIndex = this.readerIndex;
        this.readerIndex += readableBytes;
        return oriReadIndex;
    }

    protected int offsetWritable(int offset, int writableBytes) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");
        int offerWritableBytes = this.maxCapacity - (this.markedWriterIndex - this.markedReaderIndex);
        if ((offset + writableBytes) > offerWritableBytes) {
            throw new IndexOutOfBoundsException(String.format("write out of range. index: %d, length: %d (expected: 0 ~ %d)", offset, writableBytes, offerWritableBytes));
        }

        return this.markedWriterIndex + offset;
    }

    protected int offsetReadable(int offset, int readableBytes) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");
        if ((this.readerIndex + offset + readableBytes) > this.markedWriterIndex) {
            int expectLimit = this.markedWriterIndex - this.readerIndex;
            throw new IndexOutOfBoundsException(String.format("read out of range. index: %d, length: %d (expected: 0 ~ %d)", offset, readableBytes, expectLimit));
        }

        return this.readerIndex + offset;
    }

    @Override
    public ByteBuf skipReadableBytes(int length) {
        nextReadable(length);
        return this;
    }

    @Override
    public ByteBuf skipWritableBytes(int length) {
        nextWritable(length);
        return this;
    }

    @Override
    public ByteBuf markReader() {
        if (this.markedReaderIndex != this.readerIndex) {
            this.markedReaderIndex = this.readerIndex;
        }
        return this;
    }

    @Override
    public ByteBuf resetReader() {
        this.readerIndex = this.markedReaderIndex;
        return this;
    }

    @Override
    public int readerIndex() {
        return this.readerIndex;
    }

    @Override
    public ByteBuf markWriter() {
        if (this.markedWriterIndex != this.writerIndex) {
            this.markedWriterIndex = this.writerIndex;
        }
        return this;
    }

    @Override
    public ByteBuf resetWriter() {
        this.writerIndex = this.markedWriterIndex;
        return this;
    }

    @Override
    public int writerIndex() {
        return this.writerIndex;
    }

    @Override
    public void writeByte(byte n) {
        this._putByte(nextWritable(1), n);
    }

    @Override
    public ByteBuf asReadOnly() {
        return new ReadOnlyByteBuf(this);
    }

    @Override
    public int writeBytes(byte[] src, int off, int len) {
        ObjectUtils.checkPositiveOrZero(len, "len");

        int minLen = Math.min(len, this.writableBytes());
        return this._putBytes(nextWritable(minLen), src, off, minLen);
    }

    @Override
    public void writeInt16(short n) {
        encodeInt16(this, nextWritable(2), n, isBig());
    }

    @Override
    public void writeInt24(int n) {
        encodeInt24(this, nextWritable(3), n, isBig());
    }

    @Override
    public void writeInt32(int n) {
        encodeInt32(this, nextWritable(4), n, isBig());
    }

    @Override
    public void writeUInt32(long n) {
        encodeInt32(this, nextWritable(4), n, isBig());
    }

    @Override
    public void writeInt64(long n) {
        encodeInt64(this, nextWritable(8), n, isBig());
    }

    @Override
    public void writeFloat32(float n) {
        this.writeInt32(Float.floatToRawIntBits(n));
    }

    @Override
    public void writeFloat64(double n) {
        this.writeInt64(Double.doubleToRawLongBits(n));
    }

    @Override
    public int writeBuffer(ByteBuffer src, int len) {
        ObjectUtils.checkPositiveOrZero(len, "len");

        int minLen = Math.min(len, this.writableBytes());
        return this._putBytes(nextWritable(minLen), src, minLen);
    }

    @Override
    public int writeBuffer(ByteBuf src, int len) {
        ObjectUtils.checkPositiveOrZero(len, "len");

        int minLen = Math.min(len, this.writableBytes());
        return this._putBytes(nextWritable(minLen), src, minLen);
    }

    @Override
    public void setByte(int offset, byte n) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        this._putByte(offsetWritable(offset, 1), n);
    }

    @Override
    public void setBytes(int offset, byte[] src) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        this._putBytes(offsetWritable(offset, src.length), src, 0, src.length);
    }

    @Override
    public void setBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");
        ObjectUtils.checkPositiveOrZero(srcOffset, "srcOffset");
        ObjectUtils.checkPositiveOrZero(srcLen, "srcLen");

        this._putBytes(offsetWritable(offset, srcLen), src, srcOffset, srcLen);
    }

    @Override
    public void setInt16(int offset, short n) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        encodeInt16(this, offsetWritable(offset, 2), n, isBig());
    }

    @Override
    public void setInt24(int offset, int n) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        encodeInt24(this, offsetWritable(offset, 3), n, isBig());
    }

    @Override
    public void setInt32(int offset, int n) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        encodeInt32(this, offsetWritable(offset, 4), n, isBig());
    }

    @Override
    public void setInt64(int offset, long n) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        encodeInt64(this, offsetWritable(offset, 8), n, isBig());
    }

    @Override
    public void setFloat32(int offset, float n) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        this.setInt32(offset, Float.floatToRawIntBits(n));
    }

    @Override
    public void setFloat64(int offset, double n) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        this.setInt64(offset, Double.doubleToRawLongBits(n));
    }

    @Override
    public int setBuffer(int offset, ByteBuffer src, int srcLen) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");
        ObjectUtils.checkPositiveOrZero(srcLen, "srcLen");

        return this._putBytes(offsetWritable(offset, srcLen), src, srcLen);
    }

    @Override
    public int setBuffer(int offset, ByteBuf src, int srcLen) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");
        ObjectUtils.checkPositiveOrZero(srcLen, "srcLen");

        return this._putBytes(offsetWritable(offset, srcLen), src, srcLen);
    }

    @Override
    public byte readByte() {
        return this._getByte(nextReadable(1));
    }

    @Override
    public int readBytes(byte[] dst, int off, int len) {
        ObjectUtils.checkPositiveOrZero(off, "off");
        ObjectUtils.checkPositiveOrZero(len, "len");

        int minLen = Math.min(len, this.readableBytes());
        return this._getBytes(nextReadable(minLen), dst, off, minLen);
    }

    @Override
    public short readInt16() {
        return decodeInt16(this, nextReadable(2), isBig());
    }

    @Override
    public int readInt24() {
        return decodeInt24(this, nextReadable(3), isBig());
    }

    @Override
    public int readInt32() {
        return decodeInt32(this, nextReadable(4), isBig());
    }

    @Override
    public long readInt64() {
        return decodeInt64(this, nextReadable(8), isBig());
    }

    @Override
    public float readFloat32() {
        return Float.intBitsToFloat(readInt32());
    }

    @Override
    public double readFloat64() {
        return Double.longBitsToDouble(readInt64());
    }

    @Override
    public int readBuffer(ByteBuffer dst, int len) {
        ObjectUtils.checkPositiveOrZero(len, "len");

        int minLen = Math.min(len, this.readableBytes());
        return this._getBytes(nextReadable(minLen), dst, minLen);
    }

    @Override
    public int readBuffer(ByteBuf dst, int len) {
        ObjectUtils.checkPositiveOrZero(len, "len");

        int minLen = Math.min(len, this.readableBytes());
        return this._getBytes(nextReadable(minLen), dst, minLen);
    }

    @Override
    public byte getByte(int offset) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        return this._getByte(offsetReadable(offset, 1));
    }

    @Override
    public int getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");
        ObjectUtils.checkPositiveOrZero(dstOffset, "dstOffset");
        ObjectUtils.checkPositiveOrZero(dstLen, "dstLen");

        return this._getBytes(offsetReadable(offset, dstLen), dst, dstOffset, dstLen);
    }

    @Override
    public short getInt16(int offset) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        return decodeInt16(this, offsetReadable(offset, 2), isBig());
    }

    @Override
    public int getInt24(int offset) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        return decodeInt24(this, offsetReadable(offset, 3), isBig());
    }

    @Override
    public int getInt32(int offset) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        return decodeInt32(this, offsetReadable(offset, 4), isBig());
    }

    @Override
    public long getInt64(int offset) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        return decodeInt64(this, offsetReadable(offset, 8), isBig());
    }

    @Override
    public float getFloat32(int offset) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        return Float.intBitsToFloat(getInt32(offset));
    }

    @Override
    public double getFloat64(int offset) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        return Double.longBitsToDouble(getInt64(offset));
    }

    @Override
    public int getBuffer(int offset, ByteBuffer dst, int dstLen) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");
        ObjectUtils.checkPositiveOrZero(dstLen, "dstLen");

        return this._getBytes(offsetReadable(offset, dstLen), dst, dstLen);
    }

    @Override
    public int getBuffer(int offset, ByteBuf dst, int dstLen) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");
        ObjectUtils.checkPositiveOrZero(dstLen, "dstLen");

        return this._getBytes(offsetReadable(offset, dstLen), dst, dstLen);
    }

    @Override
    public short readUInt8() {
        return decodeUInt8(this, nextReadable(1));
    }

    @Override
    public int readUInt16() {
        return decodeUInt16(this, nextReadable(2), isBig());
    }

    @Override
    public int readUInt24() {
        return decodeUInt24(this, nextReadable(3), isBig());
    }

    @Override
    public long readUInt32() {
        return decodeUInt32(this, nextReadable(4), isBig());
    }

    @Override
    public short getUInt8(int offset) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        return decodeUInt8(this, offsetReadable(offset, 1));
    }

    @Override
    public int getUInt16(int offset) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        return decodeUInt16(this, offsetReadable(offset, 2), isBig());
    }

    @Override
    public int getUInt24(int offset) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        return decodeUInt24(this, offsetReadable(offset, 3), isBig());
    }

    @Override
    public long getUInt32(int offset) {
        ObjectUtils.checkPositiveOrZero(offset, "offset");

        return decodeUInt32(this, offsetReadable(offset, 4), isBig());
    }

    @Override
    public byte[] asByteArray() {
        checkFree();

        byte[] copyArray = new byte[this.markedWriterIndex - this.markedReaderIndex];
        this._getBytes(this.markedReaderIndex, copyArray, 0, copyArray.length);
        return copyArray;
    }

    @Override
    public String toString() {
        return getSimpleName() + "[rMark=" + this.markedReaderIndex //
                + " -> rIndex=" + this.readerIndex //
                + " -> wMark=" + this.markedWriterIndex //
                + " -> wIndex=" + this.writerIndex //
                + ", capacity=" + this.capacity() + "]";
    }

    protected abstract String getSimpleName();

    private static class LeakDetectorHolder {
        static final ResourceLeakDetector<ByteBuf> leakDetector = new ResourceLeakDetector<>(ByteBuf.class);
    }
}
