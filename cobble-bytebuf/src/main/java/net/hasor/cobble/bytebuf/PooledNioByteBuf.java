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
import java.util.LinkedList;

public class PooledNioByteBuf extends AbstractByteBuf {
    private final LinkedList<NioChunk> buffers = new LinkedList<>();
    private       int                  capacity;
    private final int                  sliceSize;
    private final NioChunkAllocator    chunkAllocator;
    private       boolean              isFree;

    protected PooledNioByteBuf(int capacity, int maxCapacity, int sliceSize, NioChunkAllocator chunkAllocator) {
        super(maxCapacity);
        this.capacity = capacity;
        this.sliceSize = sliceSize;
        this.chunkAllocator = chunkAllocator;
        this.isFree = false;

        int sliceCnt = (int) Math.ceil((capacity) / (double) sliceSize);
        for (int i = 0; i <= sliceCnt; i++) {
            this.buffers.add(this.extendByteBuffer(sliceSize));
        }
    }

    private void checkFree() {
        if (this.isFree) {
            throw new IllegalStateException("ByteBuf Has been released.");
        }
    }

    @Override
    protected void _putByte(int offset, byte b) {
        checkFree();

        try {
            lock.writeLock().lock();
            int markedReaderIndex = this.getMarkedReaderIndex();
            int targetOffset = markedReaderIndex + offset;
            int startBuf = targetOffset / this.sliceSize;
            int baseOffset = targetOffset % this.sliceSize;

            this.buffers.get(startBuf).put(baseOffset, b);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    protected void _putBytes(int offset, byte[] b, int off, int len) {
        checkFree();

        try {
            lock.writeLock().lock();
            int markedReaderIndex = this.getMarkedReaderIndex();
            int targetOffset = markedReaderIndex + offset;
            int startBuf = targetOffset / this.sliceSize;
            int baseOffset = targetOffset % this.sliceSize;

            do {
                int debris = this.sliceSize - baseOffset;
                NioChunk buffer = this.buffers.get(startBuf);

                buffer.clearPosition(baseOffset);
                buffer.put(b, off, Math.min(len, debris));

                off = off + debris;
                len = len - debris;
                baseOffset = 0;
                startBuf++;
            } while (len > 0);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        try {
            lock.writeLock().lock();
            int markedReaderIndex = this.getMarkedReaderIndex();
            int targetOffset = markedReaderIndex + offset;
            int startBuf = targetOffset / this.sliceSize;
            int baseOffset = targetOffset % this.sliceSize;

            return this.buffers.get(startBuf).get(baseOffset);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    protected int _getBytes(int offset, byte[] b, int off, int len) {
        checkFree();

        try {
            lock.readLock().lock();
            int markedReaderIndex = this.getMarkedReaderIndex();
            int targetOffset = markedReaderIndex + offset;
            int startBuf = targetOffset / this.sliceSize;
            int baseOffset = targetOffset % this.sliceSize;

            int readBytes = 0;
            do {
                int debris = this.sliceSize - baseOffset;
                int thisRead = Math.min(debris, len);
                NioChunk buffer = this.buffers.get(startBuf);

                buffer.clearLimit(this.sliceSize);
                buffer.get(b, off, thisRead);

                off += debris;
                len -= debris;
                baseOffset = 0;
                startBuf++;

                readBytes += thisRead;
            } while (len > 0);
            return readBytes;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    protected void extendByteBuf(int targetCapacity) {
        checkFree();

        if (this.getMaxCapacity() > 0 && targetCapacity > this.getMaxCapacity()) {
            throw new BufferOverflowException();
        }

        try {
            lock.writeLock().lock();
            int sliceCnt = (int) Math.ceil((this.capacity) / (double) this.sliceSize) - this.buffers.size();

            for (int i = 0; i < sliceCnt; i++) {
                this.buffers.add(this.extendByteBuffer(this.sliceSize));
            }

            this.capacity = this.buffers.size() * this.sliceSize;
        } finally {
            lock.writeLock().unlock();
        }
    }

    protected NioChunk extendByteBuffer(int capacity) {
        return this.chunkAllocator.allocateBuffer(capacity);
    }

    protected void recycleByteBuf() {
        checkFree();

        try {
            lock.writeLock().lock();
            int markedReaderIndex = this.getMarkedReaderIndex();
            int startBuf = markedReaderIndex / this.sliceSize;
            if (startBuf > 0) {
                for (int i = 0; i <= startBuf; i++) {
                    this.buffers.remove(0).freeBuffer();
                }
            }

            if (this.buffers.isEmpty()) {
                this.buffers.add(this.extendByteBuffer(this.sliceSize));
            }

            int cut = this.markedReaderIndex;
            this.markedReaderIndex = 0;
            this.markedWriterIndex = this.markedWriterIndex - cut;
            this.readerIndex = this.readerIndex - cut;
            this.writerIndex = this.writerIndex - cut;
            this.capacity = this.buffers.size() * this.sliceSize;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int capacity() {
        return this.capacity;
    }

    @Override
    public ByteBuf copy() {
        checkFree();

        try {
            lock.writeLock().lock();

            PooledNioByteBuf copy = new PooledNioByteBuf(this.capacity(), this.getMaxCapacity(), this.sliceSize, this.chunkAllocator);
            for (int i = 0; i < this.buffers.size(); i++) {
                NioChunk form = this.buffers.get(i);
                NioChunk to = copy.buffers.get(i);
                form.deepCopy(to);
            }
            return copy;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void free() {
        if (this.isFree) {
            return;
        }

        try {
            lock.writeLock().lock();
            for (NioChunk chunk : this.buffers) {
                chunk.freeBuffer();
            }
            this.buffers.clear();
            this.isFree = true;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
