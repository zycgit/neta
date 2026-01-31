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
import java.util.HashMap;
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
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class RecycleObjectPool {
    private static final int                                            LOCAL_CAPACITY  = 1024;
    private static final int                                            GLOBAL_CAPACITY = 4096;
    // L1: ThreadLocal Cache
    private static final ThreadLocal<Map<Class<?>, ArrayDeque<Object>>> THREAD_CACHE    = new ThreadLocal<>();
    // L2: Global Shared Cache
    private static final Map<Class<?>, RecyclerQueue>                   GLOBAL_CACHE    = new ConcurrentHashMap<>();

    private static class RecyclerQueue {
        final Queue<Object> queue = new ConcurrentLinkedQueue<>();
        final AtomicInteger size  = new AtomicInteger(0);
    }

    private static ArrayDeque<Object> localQueue(Class<?> objType) {
        Map<Class<?>, ArrayDeque<Object>> cacheMap = THREAD_CACHE.get();
        if (cacheMap == null) {
            cacheMap = new HashMap<>();
            THREAD_CACHE.set(cacheMap);
        }

        return cacheMap.computeIfAbsent(objType, k -> new ArrayDeque<>());
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
                return;
            }
        }

        // 3. Discard if both full (Let GC handle it)
    }
}