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

/**
 * readMark &lt;= readIndex &lt;= writerMark &lt;= writerIndex &lt;= capacity
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
        if (initCapacity == 0) {
            return ByteBuf.EMPTY;
        } else {
            return this.buffer(initCapacity, initCapacity);
        }
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
    public ByteBuf ringBuffer(int capacity) {
        ObjectUtils.checkPositiveOrZero(capacity, "capacity");
        return this.ringByAllocator(this, capacity);
    }

    @Override
    public ByteBuf ringHeapBuffer(int capacity) {
        ObjectUtils.checkPositiveOrZero(capacity, "capacity");
        return this.ringByAllocator(ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR, capacity);
    }

    @Override
    public ByteBuf ringDirectBuffer(int capacity) {
        ObjectUtils.checkPositiveOrZero(capacity, "capacity");
        return this.ringByAllocator(ByteBufUtils.UNPOOLED_DIRECT_ALLOCATOR, capacity);
    }

    private ByteBuf ringByAllocator(ByteBufAllocator alloc, int capacity) {
        if (alloc.isDirect()) {
            RingByteBuffer byteBuf = RecycleObjectPool.get(RingByteBuffer.class, RingByteBuffer.RECYCLE_HANDLER);
            byteBuf.initBuffer(alloc, capacity);
            return byteBuf;
        } else {
            RingArrayByteBuf byteBuf = RecycleObjectPool.get(RingArrayByteBuf.class, RingArrayByteBuf.RECYCLE_HANDLER);
            byteBuf.initBuffer(alloc, capacity);
            return byteBuf;
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
            AutoByteBuffer byteBuf = RecycleObjectPool.get(AutoByteBuffer.class, AutoByteBuffer.RECYCLE_HANDLER);
            byteBuf.initBuffer(alloc, maxCapacity, this.sliceSizeByDefault, alloc.jvmBuffer(initCapacity));
            return byteBuf;
        } else {
            AutoArrayByteBuf byteBuf = RecycleObjectPool.get(AutoArrayByteBuf.class, AutoArrayByteBuf.RECYCLE_HANDLER);
            byteBuf.initBuffer(alloc, maxCapacity, this.sliceSizeByDefault, new byte[initCapacity]);
            return byteBuf;
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
        int fmtMaxCap = PageChunkPool.tableSizeFor(maxCapacity, Integer.MAX_VALUE);
        BufferPool pool = BufferPoolUtils.getPool(fmtMaxCap, alloc);
        Buffer target = pool.requestBuffer(initCapacity, this);

        PooledByteBuf byteBuf = RecycleObjectPool.get(PooledByteBuf.class, PooledByteBuf.RECYCLE_HANDLER);
        byteBuf.initBuffer(alloc, fmtMaxCap, this.sliceSizeByDefault, target, pool);
        return byteBuf;
    }
}