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

final class ArraySliceByteBuf extends AbstractByteBuf {
    private static final RecycleObjectPool<ArraySliceByteBuf> RECYCLER = new RecycleObjectPool<>(//
            ArraySliceByteBuf::new, ArraySliceByteBuf::resetState, ArraySliceByteBuf::onRecycle);
    private              ByteBuf                              source;
    private              byte[]                               target;
    private              int                                  startOffset;

    private ArraySliceByteBuf() {
    }

    private void resetState() {
        this.source = null;
        this.target = null;
        this.startOffset = 0;
    }

    private void onRecycle() {
        ByteBuf current = this.source;
        this.source = null;
        this.target = null;
        this.startOffset = 0;
        if (current != null && !current.isFree()) {
            current.release();
        }
    }

    private void initSlice(ByteBuf source, byte[] target, int startOffset, int length) {
        ByteBufAllocator allocator = source != null && source.alloc() != null ? source.alloc() : ByteBufAllocator.DEFAULT;
        super.initByteBuf(allocator, length);
        this.source = source != null ? source.retain() : null;
        this.target = target;
        this.startOffset = startOffset;
        this.writerIndex = length;
        this.markedWriterIndex = length;
    }

    static ArraySliceByteBuf newSlice(ByteBuf source, byte[] target, int startOffset, int length) {
        ArraySliceByteBuf slice = RECYCLER.get();
        slice.initSlice(source, target, startOffset, length);
        return slice;
    }

    static ArraySliceByteBuf newSlice(ByteBuf source, int startOffset, int length) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        return newSlice(source, null, startOffset, length);
    }

    @Override
    public int capacity() {
        return this.getMaxCapacity();
    }

    @Override
    public ByteBuf copy() {
        int readable = this.readableBytes();
        if (readable == 0) {
            return ByteBuf.EMPTY;
        }
        byte[] copy = new byte[readable];
        this._getBytes(this.readerIndex, copy, 0, readable);
        return ByteBuf.wrap(copy);
    }

    @Override
    public ByteBuf slice(int offset, int length) {
        if (length <= 0) {
            return ByteBuf.EMPTY;
        }
        int baseOffset = offsetReadable(offset, length);
        if (this.target == null) {
            return ArraySliceByteBuf.newSlice(this, baseOffset, length);
        }
        return ArraySliceByteBuf.newSlice(this, this.target, this.startOffset + baseOffset, length);
    }

    @Override
    public boolean isDirect() {
        return this.target == null && this.source != null && this.source.isDirect();
    }

    @Override
    public void discardReadBytes() {
        if (this.readerIndex == 0) {
            return;
        }
        int consumed = this.readerIndex;
        this.startOffset += consumed;
        this.writerIndex -= consumed;
        this.markedWriterIndex = Math.max(0, this.markedWriterIndex - consumed);
        this.readerIndex = 0;
        this.markedReaderIndex = 0;
    }

    @Override
    public ByteBuf sliceOff(int splitOffset) {
        if (splitOffset <= 0) {
            return ByteBuf.EMPTY;
        }

        int readable = this.readableBytes();
        int len = Math.min(splitOffset, readable);
        byte[] sliceData = new byte[len];
        this._getBytes(this.readerIndex, sliceData, 0, len);
        this.readerIndex += len;
        this.markedReaderIndex = this.readerIndex;
        return ByteBuf.wrap(sliceData);
    }

    @Override
    public String getString(int offset, int len, Charset charset) {
        if (this.target == null || len <= 0 || offset < 0 || offset > this.readableBytes() - len) {
            return super.getString(offset, len, charset);
        }

        checkFree();
        return ByteBufUtils.decodeString(this.target, this.startOffset + this.readerIndex + offset, len, charset);
    }

    @Override
    public int expect(byte expected, int maxScanBytes) {
        checkFree();

        int scanLength = Math.min(this.readableBytes(), Math.max(0, maxScanBytes));
        if (scanLength <= 0) {
            return -1;
        }

        int start = this.startOffset + this.readerIndex;
        int end = start + scanLength;
        byte[] array = this.target;
        if (array != null) {
            int index = ByteBufUtils.indexOf(array, start, end, expected);
            return index < 0 ? -1 : index - start;
        } else {
            ByteBuf source = this.source;
            for (int i = start; i < end; i++) {
                if (((AbstractByteBuf) source)._getByte(i) == expected) {
                    return i - start;
                }
            }
        }
        return -1;
    }

    @Override
    public int expectLast(byte expected, int maxScanBytes) {
        checkFree();

        int scanLength = Math.min(this.readableBytes(), Math.max(0, maxScanBytes));
        if (scanLength <= 0) {
            return -1;
        }

        int start = this.startOffset + this.readerIndex;
        byte[] array = this.target;
        if (array != null) {
            for (int i = start + scanLength - 1; i >= start; i--) {
                if (array[i] == expected) {
                    return i - start;
                }
            }
        } else {
            ByteBuf source = this.source;
            for (int i = start + scanLength - 1; i >= start; i--) {
                if (((AbstractByteBuf) source)._getByte(i) == expected) {
                    return i - start;
                }
            }
        }
        return -1;
    }

    @Override
    public int writableBytes() {
        return 0;
    }

    @Override
    protected void _putByte(int offset, byte b) {
        throw new UnsupportedOperationException("ArraySliceByteBuf does not support direct writes.");
    }

    @Override
    protected int _putBytes(int offset, byte[] src, int srcOffset, int srcLen) {
        throw new UnsupportedOperationException("ArraySliceByteBuf does not support direct writes.");
    }

    @Override
    protected int _putBytes(int offset, ByteBuffer src, int srcLen) {
        throw new UnsupportedOperationException("ArraySliceByteBuf does not support direct writes.");
    }

    @Override
    protected int _putBytes(int offset, ByteBuf src, int srcLen) {
        throw new UnsupportedOperationException("ArraySliceByteBuf does not support direct writes.");
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();
        byte[] array = this.target;
        return array != null ? array[this.startOffset + offset] : ((AbstractByteBuf) this.source)._getByte(this.startOffset + offset);
    }

    @Override
    protected int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        checkFree();
        byte[] array = this.target;
        if (array != null) {
            System.arraycopy(array, this.startOffset + offset, dst, dstOffset, dstLen);
        } else {
            ((AbstractByteBuf) this.source)._getBytes(this.startOffset + offset, dst, dstOffset, dstLen);
        }
        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuffer dst, int dstLen) {
        checkFree();
        byte[] array = this.target;
        if (array != null) {
            dst.put(array, this.startOffset + offset, dstLen);
        } else {
            ((AbstractByteBuf) this.source)._getBytes(this.startOffset + offset, dst, dstLen);
        }
        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuf dst, int dstLen) {
        checkFree();
        byte[] array = this.target;
        if (array != null) {
            return dst.writeBytes(array, this.startOffset + offset, dstLen);
        }
        return ((AbstractByteBuf) this.source)._getBytes(this.startOffset + offset, dst, dstLen);
    }

    @Override
    protected void _free() {
        RECYCLER.recycle(this);
    }

    @Override
    protected String getSimpleName() {
        return "ArraySliceByteBuf";
    }
}
