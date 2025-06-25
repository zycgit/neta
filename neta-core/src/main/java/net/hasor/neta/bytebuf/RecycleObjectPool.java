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

/**
 * The object pool.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class RecycleObjectPool {
    private static final ThreadLocal<Map<Class<?>, ArrayDeque<Object>>> THREAD_CACHE = new ThreadLocal<>();

    private static ArrayDeque<Object> cacheDeque(Class<?> objType) {
        Map<Class<?>, ArrayDeque<Object>> cacheMap = THREAD_CACHE.get();
        if (cacheMap == null) {
            synchronized (RecycleObjectPool.class) {
                cacheMap = THREAD_CACHE.get();
                if (cacheMap == null) {
                    cacheMap = new HashMap<>();
                    THREAD_CACHE.set(cacheMap);
                }
            }
        }

        ArrayDeque<Object> deque = cacheMap.get(objType);
        if (deque == null) {
            synchronized (cacheMap) {
                deque = cacheMap.computeIfAbsent(objType, k -> new ArrayDeque<>());
            }
        }

        return deque;
    }

    public static <T> T get(Class<T> objType, RecycleHandler<?> handler) {
        ArrayDeque<Object> deque = cacheDeque(objType);
        if (deque.isEmpty()) {
            return (T) handler.create();
        } else {
            return (T) deque.pop();
        }
    }

    public static void free(Class<?> objType, Object obj) {
        cacheDeque(objType).add(obj);
    }
}