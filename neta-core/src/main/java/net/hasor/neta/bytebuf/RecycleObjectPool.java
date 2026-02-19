/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.bytebuf;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The object pool.
 * <p>
 * Strategy:
 * 1. L1 Cache (ThreadLocal): Fast, lock-free, primary choice.
 * 2. L2 Cache (Global Shared): Thread-safe, lock-free (ConcurrentLinkedQueue), handles cross-thread recycling.
 * <p>
 * Two APIs are provided:
 * - <b>Indexed API</b> (fast path): {@link #get(int, RecycleHandler)} / {@link #free(int, Object)}.
 * Uses a single ThreadLocal array indexed by type index (no ConcurrentHashMap lookup).
 * Production code should use {@link #registerType()} to obtain a type index.
 * - <b>Class-based API</b> (legacy): {@link #get(Class, RecycleHandler)} / {@link #free(Class, Object)}.
 * Uses ConcurrentHashMap per-type lookup. Retained for backward compatibility and tests.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class RecycleObjectPool {
    private static final int LOCAL_CAPACITY  = 1024;
    private static final int GLOBAL_CAPACITY = 4096;

    // ==================== Indexed API (Fast Path) ====================

    /** Maximum number of registered types for the indexed fast path. */
    private static final int                                                          MAX_INDEXED_TYPES    = 32;
    private static final AtomicInteger                                                TYPE_COUNTER         = new AtomicInteger(0);
    private static final RecyclerQueue[]                                              GLOBAL_INDEXED       = new RecyclerQueue[MAX_INDEXED_TYPES];
    private static final ThreadLocal<ArrayDeque<Object>[]>                            LOCAL_INDEXED_CACHES = ThreadLocal.withInitial(RecycleObjectPool::createIndexedCaches);
    // Per-type ThreadLocal (eliminates inner HashMap lookup on every get/free call)
    private static final ConcurrentHashMap<Class<?>, ThreadLocal<ArrayDeque<Object>>> PER_TYPE_LOCAL       = new ConcurrentHashMap<>();
    // L2: Global Shared Cache
    private static final Map<Class<?>, RecyclerQueue>                                 GLOBAL_CACHE         = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private static ArrayDeque<Object>[] createIndexedCaches() {
        ArrayDeque<Object>[] arr = new ArrayDeque[MAX_INDEXED_TYPES];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = new ArrayDeque<>();
        }
        return arr;
    }

    /**
     * Register a new type for indexed fast-path recycling.
     * Call once per type during static initialization.
     * @return the type index for use with {@link #get(int, RecycleHandler)} and {@link #free(int, Object)}.
     */
    static int registerType() {
        int idx = TYPE_COUNTER.getAndIncrement();
        if (idx >= MAX_INDEXED_TYPES) {
            throw new IllegalStateException("Too many recycled types registered: " + idx + " (max " + MAX_INDEXED_TYPES + ")");
        }
        GLOBAL_INDEXED[idx] = new RecyclerQueue();
        return idx;
    }

    // ==================== Class-based API (Legacy) ====================

    /**
     * Fast-path get: retrieve a recycled object or create a new one.
     * Uses array-indexed ThreadLocal (no ConcurrentHashMap lookup).
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(int typeIndex, RecycleHandler<?> handler) {
        // 1. Try L1 (Thread Local) — single ThreadLocal.get() for all types
        ArrayDeque<Object> localDeque = LOCAL_INDEXED_CACHES.get()[typeIndex];
        if (!localDeque.isEmpty()) {
            return (T) localDeque.pop();
        }

        // 2. Try L2 (Global Shared)
        RecyclerQueue rq = GLOBAL_INDEXED[typeIndex];
        Object obj = rq.queue.poll();
        if (obj != null) {
            rq.size.decrementAndGet();
            return (T) obj;
        }

        // 3. Create New
        return (T) handler.create();
    }

    /**
     * Fast-path free: return an object to the pool for reuse.
     * Uses array-indexed ThreadLocal (no ConcurrentHashMap lookup).
     */
    public static void free(int typeIndex, Object obj) {
        // 1. Try L1 (Thread Local)
        ArrayDeque<Object> localDeque = LOCAL_INDEXED_CACHES.get()[typeIndex];
        if (localDeque.size() < LOCAL_CAPACITY) {
            localDeque.push(obj);
            return;
        }

        // 2. Try L2 (Global Shared)
        RecyclerQueue rq = GLOBAL_INDEXED[typeIndex];
        if (rq.size.get() < GLOBAL_CAPACITY) {
            if (rq.queue.offer(obj)) {
                rq.size.incrementAndGet();
            }
        }
        // 3. Discard if both full (Let GC handle it)
    }

    private static ArrayDeque<Object> localQueue(Class<?> objType) {
        ThreadLocal<ArrayDeque<Object>> tl = PER_TYPE_LOCAL.get(objType);
        if (tl == null) {
            tl = PER_TYPE_LOCAL.computeIfAbsent(objType, k -> ThreadLocal.withInitial(ArrayDeque::new));
        }
        return tl.get();
    }

    private static RecyclerQueue globalQueue(Class<?> objType) {
        return GLOBAL_CACHE.computeIfAbsent(objType, k -> new RecyclerQueue());
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Class<T> objType, RecycleHandler<?> handler) {
        // 1. Try L1 (Thread Local)
        ArrayDeque<Object> localDeque = localQueue(objType);
        if (!localDeque.isEmpty()) {
            return (T) localDeque.pop();
        }

        // 2. Try L2 (Global Shared) - Steal from global if local is empty
        RecyclerQueue recyclerQueue = globalQueue(objType);
        Object obj = recyclerQueue.queue.poll();
        if (obj != null) {
            recyclerQueue.size.decrementAndGet();
            return (T) obj;
        }

        // 3. Create New
        return (T) handler.create();
    }

    public static void free(Class<?> objType, Object obj) {
        assert objType.isInstance(obj) : "Type mismatch: expected " + objType.getName() + ", got " + obj.getClass().getName();
        // 1. Try L1 (Thread Local)
        ArrayDeque<Object> localDeque = localQueue(objType);
        if (localDeque.size() < LOCAL_CAPACITY) {
            localDeque.push(obj);
            return;
        }

        // 2. Try L2 (Global Shared) - Offer to global if local is full
        RecyclerQueue recyclerQueue = globalQueue(objType);
        if (recyclerQueue.size.get() < GLOBAL_CAPACITY) {
            if (recyclerQueue.queue.offer(obj)) {
                recyclerQueue.size.incrementAndGet();
            }
        }

        // 3. Discard if both full (Let GC handle it)
    }

    // ==================== Shared ====================

    static class RecyclerQueue {
        final Queue<Object> queue = new ConcurrentLinkedQueue<>();
        final AtomicInteger size  = new AtomicInteger(0);
    }
}