/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import net.hasor.cobble.ref.RecycleObjectPool;

/**
 * Fixed-size {@link ByteBuf} view over an existing heap {@code byte[]}.
 * <p>
 * This implementation does not own expandable storage. It simply wraps a user
 * supplied array and exposes Neta's logical index model on top of it.
 * <pre>
 * physical storage
 *   target byte[]
 *   +---------------------------------------------------+
 *   | 0 | 1 | 2 | ... | capacity - 1 |
 *   +---------------------------------------------------+
 * logical layout on top of the array
 *   0      markedReaderIndex   readerIndex   markedWriterIndex   writerIndex   capacity
 *   |-------------|---------------|------------------|---------------|
 *   | ancient     | discardable   | readable         | overlayable   | writable |
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
final class WrapArrayBuffer extends AbstractByteBuf {
    static final RecycleObjectPool<WrapArrayBuffer> RECYCLER = new RecycleObjectPool<>(//
            WrapArrayBuffer::new, WrapArrayBuffer::resetState, WrapArrayBuffer::onRecycle);
    private      byte[]                             target;

    private WrapArrayBuffer() {
    }

    private void resetState() {
        this.target = null;
    }

    private void onRecycle() {
        this.target = null;
    }

    // Shared storage for package-local views; no copy or ownership transfer.
    byte[] backingArray() {
        return this.target;
    }

    // ------------------------------------------------------------------------

    void initBuffer(byte[] initData, boolean asWrite) {
        super.initByteBuf(null, initData.length);
        this.target = initData;
        if (initData.length > 0) {
            this.initMetricTracking(ByteBufAllocator.DEFAULT.metric(), false, initData.length);
        }
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
        RECYCLER.recycle(this);
    }

    @Override
    public int capacity() {
        return this.getMaxCapacity();
    }

    @Override
    public String getString(int offset, int len, Charset charset) {
        if (len <= 0 || offset < 0 || offset > this.readableBytes() - len) {
            return super.getString(offset, len, charset);
        }
        checkFree();
        return ByteBufUtils.decodeString(this.target, this.readerIndex + offset, len, charset);
    }

    @Override
    public int expect(byte expected, int maxScanBytes) {
        checkFree();
        int start = this.readerIndex;
        int end = start + Math.min(this.readableBytes(), Math.max(0, maxScanBytes));
        int index = ByteBufUtils.indexOf(this.target, start, end, expected);
        return index < 0 ? -1 : index - start;
    }

    @Override
    public boolean isDirect() {
        return false;
    }

    @Override
    public WrapArrayBuffer copy() {
        checkFree();

        byte[] copyArray = this.target.clone();
        WrapArrayBuffer byteBuf = WrapArrayBuffer.RECYCLER.get();
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
    public ByteBuf slice(int offset, int length) {
        if (length <= 0) {
            return ByteBuf.EMPTY;
        }
        int baseOffset = offsetReadable(offset, length);
        return ArraySliceByteBuf.newSlice(null, this.target, baseOffset, length);
    }

    @Override
    protected String getSimpleName() {
        return "WrapArrayBuffer";
    }
}
