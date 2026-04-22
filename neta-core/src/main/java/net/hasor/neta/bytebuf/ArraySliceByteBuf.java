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

final class ArraySliceByteBuf extends AbstractByteBuf {
    private ByteBuf source;
    private byte[]  target;
    private int     startOffset;

    ArraySliceByteBuf(ByteBuf source, byte[] target, int startOffset, int length) {
        ByteBufAllocator allocator = source != null && source.alloc() != null ? source.alloc() : ByteBufAllocator.DEFAULT;
        super.initByteBuf(allocator, length);
        this.source = source != null ? source.retain() : null;
        this.target = target;
        this.startOffset = startOffset;
        this.writerIndex = length;
        this.markedWriterIndex = length;
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
    public boolean isDirect() {
        return false;
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
    public int expect(byte expected, int maxScanBytes) {
        checkFree();

        int scanLength = Math.min(this.readableBytes(), Math.max(0, maxScanBytes));
        if (scanLength <= 0) {
            return -1;
        }

        int start = this.startOffset + this.readerIndex;
        int end = start + scanLength;
        for (int i = start; i < end; i++) {
            if (this.target[i] == expected) {
                return i - start;
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
        for (int i = start + scanLength - 1; i >= start; i--) {
            if (this.target[i] == expected) {
                return i - start;
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
        return this.target[this.startOffset + offset];
    }

    @Override
    protected int _getBytes(int offset, byte[] dst, int dstOffset, int dstLen) {
        checkFree();
        System.arraycopy(this.target, this.startOffset + offset, dst, dstOffset, dstLen);
        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuffer dst, int dstLen) {
        checkFree();
        dst.put(this.target, this.startOffset + offset, dstLen);
        return dstLen;
    }

    @Override
    protected int _getBytes(int offset, ByteBuf dst, int dstLen) {
        checkFree();
        return dst.writeBytes(this.target, this.startOffset + offset, dstLen);
    }

    @Override
    protected void _free() {
        ByteBuf current = this.source;
        this.source = null;
        this.target = null;
        this.startOffset = 0;
        if (current != null && !current.isFree()) {
            current.release();
        }
    }

    @Override
    protected String getSimpleName() {
        return "ArraySliceByteBuf";
    }
}