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
 * ByteBufAllocator 接口实现用于创建池化的堆 ByteBuf
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class PooledHeapByteBufAllocator extends AbstractByteBufAllocator {
    /** Create new instance */
    protected PooledHeapByteBufAllocator(int initialCapacityByDefault, int sliceSizeByDefault) {
        super(initialCapacityByDefault, sliceSizeByDefault);
    }

    @Override
    public boolean isPooled() {
        return true;
    }

    @Override
    public boolean isDirect() {
        return false;
    }

    @Override
    public ByteBuf buffer(int initialCapacity, int maxCapacity) {
        return this.pooledHeapBuffer(initialCapacity, maxCapacity);
    }

    @Override
    public ByteBuf pooledBuffer(int initialCapacity, int maxCapacity, int sliceSize) {
        return this.pooledHeapBuffer(initialCapacity, maxCapacity, sliceSize);
    }
}