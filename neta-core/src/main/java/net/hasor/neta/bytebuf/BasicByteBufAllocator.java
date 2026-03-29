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
import java.util.ArrayDeque;
import java.util.Iterator;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.ref.RecycleObjectPool;

/**
 * Shared allocation strategy for concrete {@link ByteBufAllocator} variants.
 * <p>This base class centralises the routing rules that decide which concrete
 * {@link ByteBuf} implementation should back a request:
 * <pre>
 *   buffer(initCapacity, maxCapacity)
 *       ├─ initCapacity == 0 → {@link ByteBuf#EMPTY}
 *       ├─ pooled enabled     → {@link PooledByteBuf} for pool-backed growth,
 *       │                      except very small requests which stay on the
 *       │                      lightweight small-buffer cache
 *       └─ unpooled           → {@link AutoArrayByteBuf} or {@link AutoByteBuffer}
 * </pre>
 * <p>Concrete subclasses only decide the raw JVM storage type exposed through
 * {@link #jvmBuffer(int)} and {@link #isDirect()}; all higher-level policies
 * such as pooled vs. unpooled growth, ring buffer creation, and swap-file
 * buffer creation are implemented here.
 * <p>The constructor parameters control the default initial capacity, the
 * default growth step used by auto-resizing buffers, and whether pooled
 * allocation should be preferred when {@link #buffer()} is called.
 * <p>{@link ByteBufAllocatorMetric} records cumulative allocation statistics,
 * but buffer index invariants belong to the individual {@link ByteBuf}
 * implementations rather than to this allocator.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 * @see ByteBufUtils
 * @see ByteBufAllocator
 */
public abstract class BasicByteBufAllocator implements ByteBufAllocator {
    protected final boolean                defaultUsingPooled;
    protected final int                    initCapacityByDefault;
    protected final int                    sliceSizeByDefault;
    private final   ByteBufAllocatorMetric metric = new ByteBufAllocatorMetric();

    /** Create new instance */
    protected BasicByteBufAllocator(boolean defaultUsingPooled, int initialCapacityByDefault, int sliceSizeByDefault) {
        this.defaultUsingPooled = defaultUsingPooled;
        this.initCapacityByDefault = initialCapacityByDefault;
        this.sliceSizeByDefault = sliceSizeByDefault;
    }

    @Override
    public ByteBufAllocatorMetric metric() {
        return this.metric;
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
        if (initCapacity > maxCapacity) {
            throw new IllegalArgumentException("initCapacity(" + initCapacity + ") > maxCapacity(" + maxCapacity + ")");
        }
        if (this.defaultUsingPooled) {
            // For small allocations, use unpooled with SmallBufferCache to avoid buddy algorithm overhead
            if (SmallBufferCache.isSmallSize(maxCapacity)) {
                return this.bufferByAllocator(this, initCapacity, maxCapacity);
            }
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
        this.metric.recordRingAllocation(capacity);
        if (alloc.isDirect()) {
            RingByteBuffer byteBuf = RecycleObjectPool.get(RingByteBuffer.RECYCLE_INDEX, RingByteBuffer.RECYCLE_HANDLER);
            byteBuf.initBuffer(alloc, capacity);
            return byteBuf;
        } else {
            RingArrayByteBuf byteBuf = RecycleObjectPool.get(RingArrayByteBuf.RECYCLE_INDEX, RingArrayByteBuf.RECYCLE_HANDLER);
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
            this.metric.recordDirectAllocation(initCapacity);
            AutoByteBuffer byteBuf = RecycleObjectPool.get(AutoByteBuffer.RECYCLE_INDEX, AutoByteBuffer.RECYCLE_HANDLER);
            ByteBuffer jvmBuf = SmallBufferCache.isSmallSize(initCapacity) ? SmallBufferCache.allocDirect(initCapacity) : alloc.jvmBuffer(initCapacity);
            byteBuf.initBuffer(alloc, maxCapacity, this.sliceSizeByDefault, jvmBuf);
            return byteBuf;
        } else {
            this.metric.recordHeapAllocation(initCapacity);
            AutoArrayByteBuf byteBuf = RecycleObjectPool.get(AutoArrayByteBuf.RECYCLE_INDEX, AutoArrayByteBuf.RECYCLE_HANDLER);
            byte[] data = SmallBufferCache.allocHeap(initCapacity);
            byteBuf.initBuffer(alloc, maxCapacity, this.sliceSizeByDefault, data);
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
        this.metric.recordPooledAllocation(initCapacity);
        int fmtMaxCap = PageChunkPool.tableSizeFor(maxCapacity, Integer.MAX_VALUE);
        BufferPool pool = BufferPoolUtils.getPool(fmtMaxCap, alloc);

        // For small initial capacity, use SmallBufferCache to avoid buddy algorithm overhead.
        // PooledByteBuf will naturally transition to BufferPool when the buffer grows beyond MAX_SMALL_SIZE.
        Buffer target;
        if (SmallBufferCache.isSmallSize(initCapacity)) {
            target = SmallBufferCache.allocSmallBuffer(alloc.isDirect(), initCapacity);
        } else {
            // Check thread-local pooled buffer cache first (skips buddy tree search)
            target = null;
            ArrayDeque<Buffer> cache = PooledByteBuf.BUFFER_CACHE.get();
            if (!cache.isEmpty()) {
                Iterator<Buffer> it = cache.iterator();
                while (it.hasNext()) {
                    Buffer candidate = it.next();
                    if (candidate.capacity() >= initCapacity && candidate.isDirect() == alloc.isDirect()) {
                        it.remove();
                        target = candidate;
                        break;
                    }
                }
            }
            if (target == null) {
                target = pool.requestBuffer(initCapacity, this);
            }
        }

        try {
            PooledByteBuf byteBuf = RecycleObjectPool.get(PooledByteBuf.RECYCLE_INDEX, PooledByteBuf.RECYCLE_HANDLER);
            byteBuf.initBuffer(alloc, fmtMaxCap, this.sliceSizeByDefault, target, pool);
            return byteBuf;
        } catch (Throwable e) {
            target.free();
            throw e;
        }
    }
}