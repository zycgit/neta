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
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 基于 堆内存的 ByteBuf 接口实现。
 * @version : 2022-11-01
 * @author 赵永春 (zyc@hasor.net)
 */
public class HeapByteBufAllocator extends AbstractByteBufAllocator {
    /** Create new instance */
    protected HeapByteBufAllocator(int initialCapacityByDefault, int sliceSizeByDefault) {
        super(initialCapacityByDefault, sliceSizeByDefault);
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
    public ByteBuf buffer(int initialCapacity, int maxCapacity) {
        return this.heapBuffer(initialCapacity, maxCapacity);
    }

    @Override
    public StreamByteBuf streamBuffer(int capacity, InputStream inStream, OutputStream outStream) {
        return this.heapStreamBuffer(capacity, inStream, outStream);
    }

    @Override
    public ByteBuf pooledBuffer(int initialCapacity, int maxCapacity, int sliceSize) {
        return this.pooledHeapBuffer(initialCapacity, maxCapacity);
    }
}