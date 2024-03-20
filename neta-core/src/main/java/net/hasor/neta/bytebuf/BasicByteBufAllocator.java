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
public abstract class BasicByteBufAllocator implements ByteBufAllocator {
    protected final boolean defaultUsingPooled;
    protected final int     initCapacityByDefault;
    protected final int     sliceSizeByDefault;

    /** Create new instance */
    protected BasicByteBufAllocator(boolean defaultUsingPooled, int initialCapacityByDefault, int sliceSizeByDefault) {
        this.defaultUsingPooled = defaultUsingPooled;
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
    public ByteBuf buffer(int initCapacity, int maxCapacity) {
        if (this.defaultUsingPooled) {
            return this.pooledBuffer(initCapacity, maxCapacity);
        } else {
            if (this.isDirect()) {
                return this.directBuffer(initCapacity, maxCapacity);
            } else {
                return this.heapBuffer(initCapacity, maxCapacity);
            }
        }
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
        return this.recycleBufferByAllocator(ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR, capacity);
    }

    @Override
    public ByteBuf recycleDirectBuffer(int capacity) {
        ObjectUtils.checkPositiveOrZero(capacity, "capacity");
        return this.recycleBufferByAllocator(ByteBufUtils.UNPOOLED_DIRECT_ALLOCATOR, capacity);
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
        return this.bufferByAllocator(ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR, this.initCapacityByDefault, Integer.MAX_VALUE);
    }

    @Override
    public ByteBuf heapBuffer(int capacity) {
        ObjectUtils.checkPositiveOrZero(capacity, "capacity");
        return this.bufferByAllocator(ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR, capacity, capacity);
    }

    @Override
    public ByteBuf heapBuffer(int initCapacity, int maxCapacity) {
        ObjectUtils.checkPositiveOrZero(initCapacity, "initCapacity");
        ObjectUtils.checkPositiveOrZero(maxCapacity, "maxCapacity");
        return this.bufferByAllocator(ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR, initCapacity, maxCapacity);
    }

    @Override
    public ByteBuf directBuffer() {
        return this.bufferByAllocator(ByteBufUtils.UNPOOLED_DIRECT_ALLOCATOR, this.initCapacityByDefault, Integer.MAX_VALUE);
    }

    @Override
    public ByteBuf directBuffer(int capacity) {
        ObjectUtils.checkPositiveOrZero(capacity, "capacity");
        return this.bufferByAllocator(ByteBufUtils.UNPOOLED_DIRECT_ALLOCATOR, capacity, capacity);
    }

    @Override
    public ByteBuf directBuffer(int initCapacity, int maxCapacity) {
        ObjectUtils.checkPositiveOrZero(initCapacity, "initCapacity");
        ObjectUtils.checkPositiveOrZero(maxCapacity, "maxCapacity");
        return this.bufferByAllocator(ByteBufUtils.UNPOOLED_DIRECT_ALLOCATOR, initCapacity, maxCapacity);
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
        return this.pooledByAllocator(this, this.initCapacityByDefault, Integer.MAX_VALUE);
    }

    @Override
    public ByteBuf pooledBuffer(int capacity) {
        ObjectUtils.checkPositiveOrZero(capacity, "capacity");
        return this.pooledByAllocator(this, capacity, capacity);
    }

    @Override
    public ByteBuf pooledBuffer(int initCapacity, int maxCapacity) {
        ObjectUtils.checkPositiveOrZero(initCapacity, "initCapacity");
        ObjectUtils.checkPositiveOrZero(maxCapacity, "maxCapacity");
        return this.pooledByAllocator(this, initCapacity, maxCapacity);
    }

    private ByteBuf pooledByAllocator(ByteBufAllocator alloc, int initCapacity, int maxCapacity) {
        return new PooledByteBuf(alloc, initCapacity, maxCapacity, this.sliceSizeByDefault, new BufferPool(4096, this));
    }
}