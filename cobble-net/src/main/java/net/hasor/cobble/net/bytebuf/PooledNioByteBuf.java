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
package net.hasor.cobble.net.bytebuf;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.LinkedList;

/**
 * 基于 NioChunk 池化的 ByteBuf 接口实现，提供了扩缩容零拷贝实现
 * @version : 2022-11-01
 * @author 赵永春 (zyc@hasor.net)
 */
public class PooledNioByteBuf extends AbstractByteBuf {
    protected final LinkedList<NioChunk> buffers = new LinkedList<>();
    private         int                  capacity;
    private final   int                  sliceSize;
    private final   NioChunkAllocator    chunkAllocator;

    protected PooledNioByteBuf(ByteBufAllocator alloc, int capacity, int maxCapacity, int sliceSize, NioChunkAllocator chunkAllocator) {
        super(alloc, maxCapacity);
        if (capacity < 0 || maxCapacity > 0) {
            if (!(0 < capacity && capacity <= maxCapacity)) {
                throw new IllegalArgumentException("0 > capacity > maxCapacity ( gt 0 or eq -1)");
            }
        }

        this.capacity = capacity;
        this.sliceSize = sliceSize;
        this.chunkAllocator = chunkAllocator;

        int sliceCnt = (int) Math.ceil((capacity) / (double) sliceSize);
        for (int i = 0; i <= sliceCnt; i++) {
            this.buffers.add(this.extendByteBuffer(sliceSize));
        }
    }

    private int checkOrCreate(int startBuf) {
        if (this.buffers.size() <= startBuf) {
            for (int i = this.buffers.size(); i <= startBuf; i++) {
                this.buffers.add(this.extendByteBuffer(this.sliceSize));
            }
        }

        return startBuf;
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();

        int startBuf = checkOrCreate(offset / this.sliceSize);
        int baseOffset = offset % this.sliceSize;

        this.buffers.get(startBuf).put(baseOffset, b);
    }

    @Override
    protected void _putBytes(int offset, byte[] b, int off, int len) {
        checkFree();

        int startBuf = checkOrCreate(offset / this.sliceSize);
        int baseOffset = offset % this.sliceSize;

        do {
            int debris = this.sliceSize - baseOffset;
            NioChunk buffer = this.buffers.get(startBuf);

            buffer.clearPosition(baseOffset);
            buffer.put(b, off, Math.min(len, debris));

            off = off + debris;
            len = len - debris;
            baseOffset = 0;
            checkOrCreate(++startBuf);
        } while (len > 0);
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        int startBuf = offset / this.sliceSize;
        int baseOffset = offset % this.sliceSize;

        if (startBuf > this.buffers.size()) {
            return 0;
        }

        return this.buffers.get(startBuf).get(baseOffset);
    }

    @Override
    protected int _getBytes(int offset, byte[] b, int off, int len) {
        checkFree();

        int startBuf = offset / this.sliceSize;
        int baseOffset = offset % this.sliceSize;

        int readBytes = 0;
        do {
            int debris = this.sliceSize - baseOffset;
            int thisRead = Math.min(debris, len);
            if (startBuf > this.buffers.size()) {
                break;
            }

            NioChunk buffer = this.buffers.get(startBuf);

            buffer.clearLimit(this.sliceSize);
            buffer.position(baseOffset);
            buffer.get(b, off, thisRead);

            off += debris;
            len -= debris;
            baseOffset = 0;
            startBuf++;

            readBytes += thisRead;
        } while (len > 0);
        return readBytes;
    }

    @Override
    protected void extendByteBuf(int targetCapacity) {
        checkFree();

        if (this.getMaxCapacity() > 0 && targetCapacity > this.getMaxCapacity()) {
            throw new BufferOverflowException();
        }

        int sliceCnt = (int) Math.ceil((this.capacity) / (double) this.sliceSize) - this.buffers.size();

        for (int i = 0; i < sliceCnt; i++) {
            this.buffers.add(this.extendByteBuffer(this.sliceSize));
        }

        this.capacity = this.buffers.size() * this.sliceSize;
    }

    protected NioChunk extendByteBuffer(int capacity) {
        return this.chunkAllocator.allocateBuffer(capacity);
    }

    @Override
    protected void recycleByteBuf() {
        checkFree();

        int startBuf = this.markedReaderIndex / this.sliceSize;

        if (startBuf > 0) {
            for (int i = 0; i < startBuf; i++) {
                this.buffers.remove(0).freeBuffer();
            }
        }

        if (this.buffers.isEmpty()) {
            this.buffers.add(this.extendByteBuffer(this.sliceSize));
        }

        int cut = startBuf * this.sliceSize;
        this.markedReaderIndex -= cut;
        this.markedWriterIndex -= cut;
        this.readerIndex -= cut;
        this.writerIndex -= cut;
        this.capacity = this.buffers.size() * this.sliceSize;
    }

    @Override
    public int capacity() {
        return this.capacity;
    }

    @Override
    public byte[] array() {
        byte[] array = new byte[this.buffers.size() * this.sliceSize];
        int off = 0;
        for (NioChunk chunk : this.buffers) {
            chunk.clearLimit(this.sliceSize);
            chunk.position(0);
            chunk.get(array, off, this.sliceSize);
            off += this.sliceSize;
        }
        return array;
    }

    @Override
    public boolean isDirect() {
        return this.chunkAllocator.isDirect();
    }

    @Override
    public PooledNioByteBuf copy() {
        checkFree();

        PooledNioByteBuf copy = new PooledNioByteBuf(this.alloc, this.capacity(), this.getMaxCapacity(), this.sliceSize, this.chunkAllocator);
        copy.markedReaderIndex = this.markedReaderIndex;
        copy.markedWriterIndex = this.markedWriterIndex;
        copy.readerIndex = this.readerIndex;
        copy.writerIndex = this.writerIndex;

        for (int i = 0; i < this.buffers.size(); i++) {
            NioChunk form = this.buffers.get(i);
            NioChunk to = copy.buffers.get(i);
            form.deepCopy(to);
        }
        return copy;
    }

    @Override
    public ByteBuffer asByteBuffer() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void free() {
        if (this.isFree()) {
            return;
        }

        for (NioChunk chunk : this.buffers) {
            chunk.freeBuffer();
        }
        this.buffers.clear();
        super.free();
    }

    @Override
    protected String getSimpleName() {
        return "PooledNioByteBuf";
    }
}
