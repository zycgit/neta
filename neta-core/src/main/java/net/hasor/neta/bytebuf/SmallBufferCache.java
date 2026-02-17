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
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two-level cache for small buffer allocations, similar to Netty's PoolThreadCache.
 * <p>
 * Provides fast allocation and deallocation for small buffers organized
 * by power-of-2 size classes: 4, 8, 16, 32, 64, 128, 256, 512 bytes.
 * <p>
 * Strategy (2-Level Cache):
 * <ul>
 *   <li><b>L1 (ThreadLocal)</b>: Fast, lock-free, per-thread cache — primary choice for same-thread alloc/free</li>
 *   <li><b>L2 (Global Shared)</b>: Thread-safe (ConcurrentLinkedQueue), handles cross-thread recycling.
 *       When a buffer is freed on a different thread than it was allocated on, L2 ensures the buffer
 *       can still be reused by the allocating thread or any other thread.</li>
 *   <li>Allocations &le; {@link #MAX_SMALL_SIZE} first try L1, then L2, then allocate new</li>
 *   <li>Freed buffers first try L1, overflow goes to L2, excess is discarded for GC</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-02-16
 */
class SmallBufferCache {
    /** Maximum buffer size eligible for small buffer caching. */
    static final int MAX_SMALL_SIZE = 512;

    /** Minimum size class. */
    private static final int MIN_SIZE_CLASS = 4;

    /** Number of size classes: 4, 8, 16, 32, 64, 128, 256, 512 */
    private static final int NUM_SIZE_CLASSES = 8;

    /** Maximum number of cached buffers per size class per thread (L1). */
    private static final int L1_CAPACITY = 256;

    /** Maximum number of cached buffers per size class in global shared cache (L2). */
    private static final int L2_CAPACITY = 64;

    // L1: Thread-local caches for heap byte arrays
    private static final ThreadLocal<ArrayDeque<byte[]>[]> HEAP_CACHES = ThreadLocal.withInitial(SmallBufferCache::createHeapCaches);

    // L1: Thread-local caches for direct ByteBuffer
    private static final ThreadLocal<ArrayDeque<ByteBuffer>[]> BUFFER_DIRECT_CACHES = ThreadLocal.withInitial(SmallBufferCache::createBufferCaches);

    // L2: Global shared caches for heap byte arrays (cross-thread recycling)
    private static final SharedQueue<byte[]>[] GLOBAL_HEAP_CACHES = createGlobalCaches();

    // L2: Global shared caches for direct ByteBuffer (cross-thread recycling)
    private static final SharedQueue<ByteBuffer>[] GLOBAL_DIRECT_CACHES = createGlobalCaches();

    /**
     * Check if a capacity qualifies as a small allocation.
     * @param capacity the requested capacity
     * @return true if the capacity is eligible for small buffer caching
     */
    static boolean isSmallSize(int capacity) {
        return capacity > 0 && capacity <= MAX_SMALL_SIZE;
    }

    /**
     * Normalize capacity to the next power-of-2 size class (minimum {@link #MIN_SIZE_CLASS}).
     * @param capacity the requested capacity
     * @return normalized size class, or -1 if capacity exceeds {@link #MAX_SMALL_SIZE}, or 0 if capacity &le; 0
     */
    static int normalizeCapacity(int capacity) {
        if (capacity <= 0) {
            return 0;
        }
        if (capacity > MAX_SMALL_SIZE) {
            return -1;
        }
        if (capacity <= MIN_SIZE_CLASS) {
            return MIN_SIZE_CLASS;
        }
        // Round up to the next power of 2
        int n = capacity - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return n + 1;
    }

    /**
     * Compute the cache index for a normalized (power-of-2) size.
     * 4 → 0, 8 → 1, 16 → 2, 32 → 3, 64 → 4, 128 → 5, 256 → 6, 512 → 7
     */
    private static int index(int normalizedSize) {
        return Integer.numberOfTrailingZeros(normalizedSize) - 2;
    }

    /**
     * Check if a capacity is a valid cacheable size class.
     * Must be a power-of-2, between {@link #MIN_SIZE_CLASS} and {@link #MAX_SMALL_SIZE}.
     */
    private static boolean isSizeClass(int capacity) {
        return capacity >= MIN_SIZE_CLASS && capacity <= MAX_SMALL_SIZE && (capacity & (capacity - 1)) == 0;
    }

    // ==================== Heap byte[] ====================

    /**
     * Allocate a heap byte array. If the capacity matches a size class,
     * attempts to return a cached array from L1 (thread-local) then L2 (global shared);
     * otherwise allocates a new one.
     * <p>The returned array length is always exactly equal to the requested capacity.
     * @param capacity the required capacity
     * @return a byte array with length == capacity
     */
    static byte[] allocHeap(int capacity) {
        if (!isSizeClass(capacity)) {
            return new byte[capacity];
        }
        int idx = index(capacity);
        // L1: try thread-local cache first (fast path)
        ArrayDeque<byte[]> l1 = HEAP_CACHES.get()[idx];
        byte[] buf = l1.pollFirst();
        if (buf != null) {
            return buf;
        }
        // L2: try global shared cache (cross-thread reuse)
        SharedQueue<byte[]> l2 = GLOBAL_HEAP_CACHES[idx];
        buf = l2.queue.poll();
        if (buf != null) {
            l2.size.decrementAndGet();
            return buf;
        }
        return new byte[capacity];
    }

    /**
     * Return a heap byte array to cache for reuse.
     * First tries L1 (thread-local), overflow goes to L2 (global shared).
     * The array is only cached if its length matches a valid size class.
     * @param buf the byte array to return (may be null, silently ignored)
     */
    static void freeHeap(byte[] buf) {
        if (buf == null) {
            return;
        }
        int len = buf.length;
        if (!isSizeClass(len)) {
            return;
        }
        int idx = index(len);
        // L1: try thread-local cache first (fast path)
        ArrayDeque<byte[]> l1 = HEAP_CACHES.get()[idx];
        if (l1.size() < L1_CAPACITY) {
            l1.offerFirst(buf);
            return;
        }
        // L2: overflow to global shared cache (enables cross-thread reuse)
        SharedQueue<byte[]> l2 = GLOBAL_HEAP_CACHES[idx];
        if (l2.size.get() < L2_CAPACITY) {
            if (l2.queue.offer(buf)) {
                l2.size.incrementAndGet();
            }
        }
        // else: discard, let GC handle it
    }

    // ==================== Direct ByteBuffer ====================

    /**
     * Allocate a direct ByteBuffer. If the capacity matches a size class,
     * attempts to return a cached buffer from L1 (thread-local) then L2 (global shared);
     * otherwise allocates a new one.
     * <p>The returned buffer's capacity is always exactly equal to the requested capacity.
     * The buffer is returned in a cleared state (position=0, limit=capacity).
     * @param capacity the required capacity
     * @return a direct ByteBuffer with capacity == requested
     */
    static ByteBuffer allocDirect(int capacity) {
        if (!isSizeClass(capacity)) {
            return ByteBuffer.allocateDirect(capacity);
        }
        int idx = index(capacity);
        // L1: try thread-local cache first (fast path)
        ArrayDeque<ByteBuffer> l1 = BUFFER_DIRECT_CACHES.get()[idx];
        ByteBuffer buf = l1.pollFirst();
        if (buf != null) {
            ((Buffer) buf).clear();
            return buf;
        }
        // L2: try global shared cache (cross-thread reuse)
        SharedQueue<ByteBuffer> l2 = GLOBAL_DIRECT_CACHES[idx];
        buf = l2.queue.poll();
        if (buf != null) {
            l2.size.decrementAndGet();
            ((Buffer) buf).clear();
            return buf;
        }
        return ByteBuffer.allocateDirect(capacity);
    }

    /**
     * Return a direct ByteBuffer to cache for reuse.
     * First tries L1 (thread-local), overflow goes to L2 (global shared).
     * The buffer is only cached if its capacity matches a valid size class and is direct.
     * @param buf the ByteBuffer to return (may be null, silently ignored)
     * @return true if the buffer was cached, false otherwise (caller should clean up if needed)
     */
    static boolean freeDirect(ByteBuffer buf) {
        if (buf == null || !buf.isDirect()) {
            return false;
        }
        int len = buf.capacity();
        if (!isSizeClass(len)) {
            return false;
        }
        int idx = index(len);
        // L1: try thread-local cache first (fast path)
        ArrayDeque<ByteBuffer> l1 = BUFFER_DIRECT_CACHES.get()[idx];
        if (l1.size() < L1_CAPACITY) {
            l1.offerFirst(buf);
            return true;
        }
        // L2: overflow to global shared cache (enables cross-thread reuse)
        SharedQueue<ByteBuffer> l2 = GLOBAL_DIRECT_CACHES[idx];
        if (l2.size.get() < L2_CAPACITY) {
            if (l2.queue.offer(buf)) {
                l2.size.incrementAndGet();
                return true;
            }
        }
        return false;
    }

    // ==================== Pooled small buffer factory ====================

    /**
     * Allocate a small {@link Buffer} wrapped in {@link BufferWrap}, for use with {@link PooledByteBuf}.
     * The buffer is backed by the thread-local SmallBufferCache and will be returned to cache on free.
     * @param isDirect whether to allocate a direct or heap buffer
     * @param capacity the required capacity
     * @return a Buffer backed by a small cached buffer
     */
    static net.hasor.neta.bytebuf.Buffer allocSmallBuffer(boolean isDirect, int capacity) {
        BufferWrap wrap = RecycleObjectPool.get(BufferWrap.RECYCLE_INDEX, BufferWrap.RECYCLE_HANDLER);
        if (isDirect) {
            wrap.initSmallBuffer(allocDirect(capacity));
        } else {
            wrap.initSmallHeapBuffer(allocHeap(capacity));
        }
        return wrap;
    }

    // ==================== L1 Cache Trimming ====================

    /**
     * Clear all L1 (thread-local) caches for the calling thread.
     * Cached buffers are moved to L2 (global shared) if there is room; otherwise discarded for GC.
     * <p>Call this when a thread is about to be retired, or periodically
     * to keep per-thread memory usage bounded.
     */
    static void trimCurrentThread() {
        ArrayDeque<byte[]>[] heapCaches = HEAP_CACHES.get();
        for (int i = 0; i < NUM_SIZE_CLASSES; i++) {
            drainToGlobal(heapCaches[i], GLOBAL_HEAP_CACHES[i]);
        }
        ArrayDeque<ByteBuffer>[] directCaches = BUFFER_DIRECT_CACHES.get();
        for (int i = 0; i < NUM_SIZE_CLASSES; i++) {
            drainToGlobal(directCaches[i], GLOBAL_DIRECT_CACHES[i]);
        }
    }

    /**
     * Return the total number of cached objects held by L1 (current thread).
     * Useful for monitoring and diagnostics.
     */
    static int currentThreadCacheSize() {
        int total = 0;
        ArrayDeque<byte[]>[] heapCaches = HEAP_CACHES.get();
        for (ArrayDeque<byte[]> cache : heapCaches) {
            total += cache.size();
        }
        ArrayDeque<ByteBuffer>[] directCaches = BUFFER_DIRECT_CACHES.get();
        for (ArrayDeque<ByteBuffer> cache : directCaches) {
            total += cache.size();
        }
        return total;
    }

    private static <T> void drainToGlobal(ArrayDeque<T> l1, SharedQueue<T> l2) {
        T item;
        while ((item = l1.pollFirst()) != null) {
            if (l2.size.get() < L2_CAPACITY) {
                if (l2.queue.offer(item)) {
                    l2.size.incrementAndGet();
                    continue;
                }
            }
            // discard, let GC handle
        }
    }

    // ==================== Factory methods ====================

    @SuppressWarnings("unchecked")
    private static ArrayDeque<byte[]>[] createHeapCaches() {
        ArrayDeque<byte[]>[] caches = new ArrayDeque[NUM_SIZE_CLASSES];
        for (int i = 0; i < NUM_SIZE_CLASSES; i++) {
            caches[i] = new ArrayDeque<>();
        }
        return caches;
    }

    @SuppressWarnings("unchecked")
    private static ArrayDeque<ByteBuffer>[] createBufferCaches() {
        ArrayDeque<ByteBuffer>[] caches = new ArrayDeque[NUM_SIZE_CLASSES];
        for (int i = 0; i < NUM_SIZE_CLASSES; i++) {
            caches[i] = new ArrayDeque<>();
        }
        return caches;
    }

    @SuppressWarnings("unchecked")
    private static <T> SharedQueue<T>[] createGlobalCaches() {
        SharedQueue<T>[] caches = new SharedQueue[NUM_SIZE_CLASSES];
        for (int i = 0; i < NUM_SIZE_CLASSES; i++) {
            caches[i] = new SharedQueue<>();
        }
        return caches;
    }

    /** Lock-free shared queue with bounded size for L2 cache. */
    static final class SharedQueue<T> {
        final Queue<T>      queue = new ConcurrentLinkedQueue<>();
        final AtomicInteger size  = new AtomicInteger(0);
    }
}
