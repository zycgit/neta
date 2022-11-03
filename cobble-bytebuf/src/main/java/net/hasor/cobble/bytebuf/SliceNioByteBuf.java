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

public class SliceNioByteBuf extends AbstractByteBuf {
    protected     NioChunk          data;
    private final NioChunkAllocator chunkAllocator;
    private       boolean           isFree;

    protected SliceNioByteBuf(int capacity, int maxCapacity, NioChunkAllocator chunkAllocator) {
        super(maxCapacity);
        this.data = chunkAllocator.allocateBuffer(capacity);
        this.chunkAllocator = chunkAllocator;
        this.isFree = false;
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
            int baseOffset = (markedReaderIndex + offset) % this.data.capacity();
            this.data.put(baseOffset, b);
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
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    protected byte _getByte(int offset) {
        checkFree();

        try {
            lock.readLock().lock();
            int markedReaderIndex = this.getMarkedReaderIndex();
            int baseOffset = (markedReaderIndex + offset) % this.data.capacity();
            return this.data.get(baseOffset);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    protected int _getBytes(int offset, byte[] b, int off, int len) {
        checkFree();

        try {
            lock.readLock().lock();
            int markedReaderIndex = this.getMarkedReaderIndex();
            int baseOffset = markedReaderIndex % this.data.capacity();

            if ((baseOffset + offset + len) > this.data.capacity()) {
                int partA = this.data.capacity() - (baseOffset + offset);
                int partB = len - partA;

                this.data.clearLimit(this.data.capacity());
                this.data.position(baseOffset);
                this.data.get(b, off, partA);

                this.data.position(offset);
                this.data.get(b, partA, partB);
                return partA + partB;
            } else {
                this.data.clearLimit(this.data.capacity());
                this.data.get(b, off, len);
                return len;
            }
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

            NioChunk newChunk = this.chunkAllocator.allocateBuffer(targetCapacity);
            this.data.deepCopy(newChunk);

            this.data = newChunk;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int capacity() {
        checkFree();

        return this.data.capacity();
    }

    @Override
    public ByteBuf copy() {
        checkFree();

        try {
            lock.writeLock().lock();

            SliceNioByteBuf copy = new SliceNioByteBuf(this.capacity(), this.getMaxCapacity(), this.chunkAllocator);
            this.data.deepCopy(copy.data);

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
            this.data.freeBuffer();
            this.isFree = true;
        } finally {
            lock.writeLock().unlock();
        }
    }
}