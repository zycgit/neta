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
/**
 * Small striped registry of shared {@link BufferPool} instances.
 * <p>At startup one pool is created per available CPU core, and allocation code
 * picks a pool by {@code threadId % CPU_CORES}. This is a lightweight sharding
 * strategy: it reduces hot-spot contention compared with a single global pool
 * without introducing any explicit thread-local ownership model.
 * <p>The default pools created here use {@code new BufferPool(4096)}, which
 * means:
 * <ul>
 *   <li>page size is 4096 bytes;</li>
 *   <li>tree height falls back to {@link BufferPool}'s internal default;</li>
 *   <li>maximum chunk count is unlimited unless another {@link BufferPool}
 *       configuration is introduced elsewhere.</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 * @see BufferPool
 */
class BufferPoolUtils {
    private static final int          CPU_CORES = Runtime.getRuntime().availableProcessors();
    private static final BufferPool[] POOLS     = new BufferPool[CPU_CORES];

    static {
        for (int i = 0; i < POOLS.length; i++) {
            POOLS[i] = new BufferPool(4096);
        }
    }

    public static BufferPool getPool(int reqSize, BufferAllocator a) {
        // Simple Round-Robin or Hashing based on Thread
        // This significantly reduces lock contention compared to a single global pool.
        long hash = Thread.currentThread().getId();
        int index = (int) (hash % CPU_CORES);
        return POOLS[Math.abs(index)];
    }
}
