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
import net.hasor.cobble.ObjectUtils;

import java.nio.ByteBuffer;

/**
 * readMark <= readIndex <= writerMark <= writerIndex <= capacity
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public abstract class AbstractByteBufAllocator implements ByteBufAllocator {
    protected final int initCapacityByDefault;
    protected final int sliceSizeByDefault;

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
    protected AbstractByteBufAllocator(int initialCapacityByDefault, int sliceSizeByDefault, int recycleSizeByDefault) {
        this.initCapacityByDefault = initialCapacityByDefault;
        this.sliceSizeByDefault = sliceSizeByDefault;
    }

    @Override
    public ByteBuf buffer() {
        return this.buffer(this.initCapacityByDefault, Integer.MAX_VALUE);
    }

    @Override
    public ByteBuf buffer(int initCapacity) {
        return this.buffer(initCapacity, initCapacity);
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
    public ByteBuf recycleBuffer(int capacity) {
        ObjectUtils.checkPositiveOrZero(capacity, "capacity");
        return this.recycleBufferByAllocator(this, capacity);
    }

    @Override
    public ByteBuf recycleHeapBuffer(int capacity) {
        ObjectUtils.checkPositiveOrZero(capacity, "capacity");
        return this.recycleBufferByAllocator(ByteBufUtils.DEFAULT_HEAP_ALLOCATOR, capacity);
    }

    @Override
    public ByteBuf recycleDirectBuffer(int capacity) {
        ObjectUtils.checkPositiveOrZero(capacity, "capacity");
        return this.recycleBufferByAllocator(ByteBufUtils.DEFAULT_DIRECT_ALLOCATOR, capacity);
    }

    private ByteBuf recycleBufferByAllocator(ByteBufAllocator alloc, int capacity) {
        if (alloc.isDirect()) {
            return new RecycleByteBuffer(alloc, capacity);
        } else {
            return new RecycleArrayByteBuf(alloc, capacity);
        }
    }

    @Override
    public ByteBuf heapBuffer() {
        return this.bufferByAllocator(ByteBufUtils.DEFAULT_HEAP_ALLOCATOR, this.initCapacityByDefault, Integer.MAX_VALUE);
    }

    @Override
    public ByteBuf heapBuffer(int capacity) {
        ObjectUtils.checkPositiveOrZero(capacity, "capacity");
        return this.bufferByAllocator(ByteBufUtils.DEFAULT_HEAP_ALLOCATOR, capacity, capacity);
    }

    @Override
    public ByteBuf heapBuffer(int initCapacity, int maxCapacity) {
        ObjectUtils.checkPositiveOrZero(initCapacity, "initCapacity");
        ObjectUtils.checkPositiveOrZero(maxCapacity, "maxCapacity");
        return this.bufferByAllocator(ByteBufUtils.DEFAULT_HEAP_ALLOCATOR, initCapacity, maxCapacity);
    }

    @Override
    public ByteBuf directBuffer() {
        return this.bufferByAllocator(ByteBufUtils.DEFAULT_DIRECT_ALLOCATOR, this.initCapacityByDefault, Integer.MAX_VALUE);
    }

    @Override
    public ByteBuf directBuffer(int capacity) {
        ObjectUtils.checkPositiveOrZero(capacity, "capacity");
        return this.bufferByAllocator(ByteBufUtils.DEFAULT_DIRECT_ALLOCATOR, capacity, capacity);
    }

    @Override
    public ByteBuf directBuffer(int initCapacity, int maxCapacity) {
        ObjectUtils.checkPositiveOrZero(initCapacity, "initCapacity");
        ObjectUtils.checkPositiveOrZero(maxCapacity, "maxCapacity");
        return this.bufferByAllocator(ByteBufUtils.DEFAULT_DIRECT_ALLOCATOR, initCapacity, maxCapacity);
    }

    private ByteBuf bufferByAllocator(ByteBufAllocator alloc, int initCapacity, int maxCapacity) {
        if (alloc.isDirect()) {
            return new ElasticByteBuffer(alloc, initCapacity, maxCapacity, this.sliceSizeByDefault);
        } else {
            return new ElasticArrayByteBuf(initCapacity, maxCapacity, this.sliceSizeByDefault);
        }
    }

    @Override
    public ByteBuf pooledBuffer() {
        return this.pooledBuffer(this.initCapacityByDefault, Integer.MAX_VALUE, this.sliceSizeByDefault);
    }

    @Override
    public ByteBuf pooledBuffer(int maxCapacity, int sliceSize) {
        return this.pooledBuffer(this.initCapacityByDefault, maxCapacity, sliceSize);
    }

    @Override
    public ByteBuf pooledHeapBuffer() {
        return this.pooledHeapBuffer(this.initCapacityByDefault, Integer.MAX_VALUE, this.sliceSizeByDefault);
    }

    @Override
    public ByteBuf pooledHeapBuffer(int maxCapacity, int sliceSize) {
        return this.pooledHeapBuffer(this.initCapacityByDefault, maxCapacity, sliceSize);
    }

    @Override
    public ByteBuf pooledHeapBuffer(int initCapacity, int maxCapacity, int sliceSize) {
        if (maxCapacity < 0) {
            return new PooledNioByteBuf(this, initCapacity, maxCapacity, sliceSize, this.heapNioChunkAllocator);
        } else {
            return new PooledNioByteBuf(this, Math.min(initCapacity, maxCapacity), maxCapacity, sliceSize, this.heapNioChunkAllocator);
        }
    }

    @Override
    public ByteBuf pooledDirectBuffer() {
        return this.pooledDirectBuffer(this.initCapacityByDefault, -1, this.sliceSizeByDefault);
    }

    @Override
    public ByteBuf pooledDirectBuffer(int maxCapacity, int sliceSize) {
        return this.pooledDirectBuffer(maxCapacity, maxCapacity, sliceSize);
    }

    @Override
    public ByteBuf pooledDirectBuffer(int initCapacity, int maxCapacity, int sliceSize) {
        return new PooledNioByteBuf(this, initCapacity, maxCapacity, sliceSize, this.directNioChunkAllocator);
    }
}