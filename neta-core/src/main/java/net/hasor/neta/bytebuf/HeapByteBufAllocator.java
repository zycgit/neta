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

/**
 * 基于 堆内存的 ByteBuf 接口实现。
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class HeapByteBufAllocator extends AbstractByteBufAllocator {
    /** Create new instance */
    protected HeapByteBufAllocator(int initialCapacityByDefault, int sliceSizeByDefault, int recycleSizeByDefault) {
        super(initialCapacityByDefault, sliceSizeByDefault, recycleSizeByDefault);
    }

    @Override
    public ByteBuffer jvmBuffer(int capacity) {
        return ByteBuffer.allocate(capacity);
    }

    @Override
    public boolean isPooled() {
        return false;
    }

    @Override
    public boolean isDirect() {
        return false;
    }

    @Override
    public ByteBuf buffer(int initCapacity, int maxCapacity) {
        return this.heapBuffer(initCapacity, maxCapacity);
    }

    @Override
    public ByteBuf pooledBuffer(int initCapacity, int maxCapacity, int sliceSize) {
        return this.pooledHeapBuffer(initCapacity, maxCapacity, sliceSize);
    }
}