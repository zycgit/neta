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
package net.hasor.cobble.bytebuf;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

/**
 * 基于 NioChunk 的 ByteBuf 接口实现。
 * @version : 2022-11-01
 * @author 赵永春 (zyc@hasor.net)
 */
public class SliceNioByteBuf extends AbstractByteBuf {
    protected     NioChunk          data;
    private final NioChunkAllocator chunkAllocator;

    protected SliceNioByteBuf(int capacity, int maxCapacity, NioChunkAllocator chunkAllocator) {
        super(maxCapacity);
        if (capacity < 0 || maxCapacity > 0) {
            if (!(0 < capacity && capacity <= maxCapacity)) {
                throw new IllegalArgumentException("0 > capacity > maxCapacity ( gt 0 or eq -1)");
            }
        }

        this.data = chunkAllocator.allocateBuffer(capacity);
        this.chunkAllocator = chunkAllocator;
    }

    protected SliceNioByteBuf(ByteBuffer byteBuffer) {
        super(byteBuffer.capacity());

        this.data = new NioChunk(byteBuffer);
        this.chunkAllocator = new NioChunkAllocator() {
            public NioChunk allocateBuffer(int capacity) {
                throw new UnsupportedOperationException();
            }

            public boolean isDirect() {
                return byteBuffer.isDirect();
            }
        };
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();

        int markedReaderIndex = this.getMarkedReaderIndex();
        int baseOffset = (markedReaderIndex + offset) % this.data.capacity();
        this.data.put(baseOffset, b);
    }

    @Override
    protected void _putBytes(int offset, byte[] b, int off, int len) {
        checkFree();

        int markedReaderIndex = this.getMarkedReaderIndex();
        int baseOffset = markedReaderIndex % this.data.capacity();

        if ((baseOffset + offset + len) > this.data.capacity()) {
            int partA = this.data.capacity() - (baseOffset + offset);
            int partB = len - partA;

            this.data.clearPosition(baseOffset);
            this.data.put(b, off, partA);

            this.data.clearPosition(offset);
            this.data.put(b, partA, partB);
        } else {
            this.data.clearPosition(baseOffset + offset);
            this.data.put(b, off, len);
        }
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        int markedReaderIndex = this.getMarkedReaderIndex();
        int baseOffset = (markedReaderIndex + offset) % this.data.capacity();
        return this.data.get(baseOffset);
    }

    @Override
    protected int _getBytes(int offset, byte[] b, int off, int len) {
        checkFree();

        int markedReaderIndex = this.getMarkedReaderIndex();
        int baseOffset = markedReaderIndex % this.data.capacity();

        if ((baseOffset + offset + len) > this.data.capacity()) {
            int partA = this.data.capacity() - (baseOffset + offset);
            int partB = len - partA;

            this.data.clearMaxLimit();
            this.data.position(baseOffset);
            this.data.get(b, off, partA);

            this.data.position(offset);
            this.data.get(b, partA, partB);
            return partA + partB;
        } else {
            this.data.clearMaxLimit();
            this.data.position(offset);
            this.data.get(b, off, len);
            return len;
        }
    }

    @Override
    protected void extendByteBuf(int targetCapacity) {
        checkFree();

        if (this.getMaxCapacity() > 0 && targetCapacity > this.getMaxCapacity()) {
            throw new BufferOverflowException();
        }

        NioChunk newChunk = this.chunkAllocator.allocateBuffer(targetCapacity);
        this.data.deepCopy(newChunk);

        this.data = newChunk;
    }

    @Override
    public int capacity() {
        checkFree();

        return this.data.capacity();
    }

    @Override
    public byte[] array() {
        if (this.isDirect()) {
            byte[] array = new byte[this.data.capacity()];
            this.data.clearMaxLimit();
            this.data.byteBuffer().get(array, 0, array.length);
            return array;
        } else {
            return this.data.byteBuffer().array();
        }
    }

    @Override
    public boolean isDirect() {
        return this.data.isDirect();
    }

    @Override
    public SliceNioByteBuf copy() {
        checkFree();

        SliceNioByteBuf copy = new SliceNioByteBuf(this.capacity(), this.getMaxCapacity(), this.chunkAllocator);
        copy.markedReaderIndex = this.markedReaderIndex;
        copy.markedWriterIndex = this.markedWriterIndex;
        copy.readerIndex = this.readerIndex;
        copy.writerIndex = this.writerIndex;

        this.data.deepCopy(copy.data);

        return copy;
    }

    @Override
    public void free() {
        if (this.isFree()) {
            return;
        }

        this.data.freeBuffer();
        this.data = null;
        super.free();
    }
}