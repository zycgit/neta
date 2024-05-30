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
 * Memory pool utils
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class BufferPoolUtils {
    private static final ThreadLocal<BufferPool> cachePool = ThreadLocal.withInitial(() -> new BufferPool(64, 10, 12));
    private static final BufferPool              pool      = new BufferPool(4096);

    public static BufferPool getPool(int reqSize, BufferAllocator a) {
        //        if (reqSize > cachePool.get().getMemChunkSize()) {
        //
        //        }

        return pool;//.computeIfAbsent(a, bufferAllocator -> new BufferPool(4096));
    }
}
