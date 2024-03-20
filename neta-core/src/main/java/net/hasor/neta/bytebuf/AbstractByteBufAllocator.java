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

/**
 * readMark <= readIndex <= writerMark <= writerIndex <= capacity
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public abstract class AbstractByteBufAllocator implements ByteBufAllocator {
    protected final int             initialCapacityByDefault;
    protected final int             sliceSizeByDefault;
    protected final BufferAllocator heapBufferAllocator   = cap -> new BufferWrap(ByteBuffer.allocate(cap));
    protected final BufferAllocator directBufferAllocator = cap -> new BufferWrap(ByteBuffer.allocateDirect(cap));

    protected final NioChunkAllocator heapNioChunkAllocator   = new NioChunkAllocator() {
        public NioChunk allocateBuffer(int capacity) {
            return new NioChunk(ByteBuffer.allocate(capacity));
        }

        public boolean isDirect() {
            return false;
        }
    };
    protected final NioChunkAllocator directNioChunkAllocator = new NioChunkAllocator() {
        public NioChunk allocateBuffer(int capacity) {
            return new NioChunk(ByteBuffer.allocateDirect(capacity));
        }

        public boolean isDirect() {
            return true;
        }
    };

    /** Create new instance */
    protected AbstractByteBufAllocator(int initialCapacityByDefault, int sliceSizeByDefault) {
        this.initialCapacityByDefault = initialCapacityByDefault;
        this.sliceSizeByDefault = sliceSizeByDefault;
    }

    @Override
    public ByteBuf buffer() {
        return this.buffer(this.initialCapacityByDefault, Integer.MAX_VALUE);
    }

    @Override
    public ByteBuf buffer(int capacity) {
        return this.buffer(capacity, capacity);
    }

    @Override
    @Deprecated
    public ByteBuf wrap(byte[] bytes) {
        return ByteBuf.wrap(bytes);
    }

    @Override
    @Deprecated
    public ByteBuf wrap(ByteBuffer buffer) {
        return new WrapByteBuffer(buffer, false);
    }

    @Deprecated
    @Override
    public ByteBuf arrayBuffer(int capacity) {
        return ByteBuf.wrap(new byte[capacity]);
    }

    @Override
    public ByteBuf recycleHeapBuffer(int capacity) {
        return new RecycleArrayByteBuf(this, capacity);
    }

    @Override
    public ByteBuf recycleDirectBuffer(int capacity) {
        return new RecycleSliceByteBuf(this, ByteBuffer.allocateDirect(capacity));
    }

    @Override
    public ByteBuf heapBuffer() {
        return this.heapBuffer(this.initialCapacityByDefault, Integer.MAX_VALUE);
    }

    @Override
    public ByteBuf heapBuffer(int capacity) {
        return this.heapBuffer(capacity, capacity);
    }

    @Override
    public ByteBuf heapBuffer(int initialCapacity, int maxCapacity) {
        return null;// TODO new SliceByteBuf(this, initialCapacity, maxCapacity, this.heapNioChunkAllocator);
    }

    @Override
    public ByteBuf directBuffer() {
        return this.directBuffer(this.initialCapacityByDefault, Integer.MAX_VALUE);
    }

    @Override
    public ByteBuf directBuffer(int capacity) {
        return this.directBuffer(capacity, capacity);
    }

    @Override
    public ByteBuf directBuffer(int initialCapacity, int maxCapacity) {
        return null;// TODO new SliceByteBuf(this, initialCapacity, maxCapacity, this.directNioChunkAllocator);
    }

    @Override
    public ByteBuf pooledBuffer() {
        return this.pooledBuffer(this.initialCapacityByDefault, Integer.MAX_VALUE, this.sliceSizeByDefault);
    }

    @Override
    public ByteBuf pooledBuffer(int maxCapacity, int sliceSize) {
        return this.pooledBuffer(this.initialCapacityByDefault, maxCapacity, sliceSize);
    }

    @Override
    public ByteBuf pooledHeapBuffer() {
        return this.pooledHeapBuffer(this.initialCapacityByDefault, Integer.MAX_VALUE, this.sliceSizeByDefault);
    }

    @Override
    public ByteBuf pooledHeapBuffer(int maxCapacity, int sliceSize) {
        return this.pooledHeapBuffer(this.initialCapacityByDefault, maxCapacity, sliceSize);
    }

    @Override
    public ByteBuf pooledHeapBuffer(int initialCapacity, int maxCapacity, int sliceSize) {
        if (maxCapacity < 0) {
            return new PooledNioByteBuf(this, initialCapacity, maxCapacity, sliceSize, this.heapNioChunkAllocator);
        } else {
            return new PooledNioByteBuf(this, Math.min(initialCapacity, maxCapacity), maxCapacity, sliceSize, this.heapNioChunkAllocator);
        }
    }

    @Override
    public ByteBuf pooledDirectBuffer() {
        return this.pooledDirectBuffer(this.initialCapacityByDefault, -1, this.sliceSizeByDefault);
    }

    @Override
    public ByteBuf pooledDirectBuffer(int maxCapacity, int sliceSize) {
        return this.pooledDirectBuffer(maxCapacity, maxCapacity, sliceSize);
    }

    @Override
    public ByteBuf pooledDirectBuffer(int initialCapacity, int maxCapacity, int sliceSize) {
        return new PooledNioByteBuf(this, initialCapacity, maxCapacity, sliceSize, this.directNioChunkAllocator);
    }
}