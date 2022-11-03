/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.cobble.bytebuf;
/**
 * readMark <= readIndex <= writerMark <= writerIndex <= capacity
 */
public class PooledDirectByteBufAllocator extends AbstractByteBufAllocator {
    /** Create new instance */
    protected PooledDirectByteBufAllocator(int initialCapacityByDefault, int sliceSizeByDefault) {
        super(initialCapacityByDefault, sliceSizeByDefault);
    }

    @Override
    public boolean isPooled() {
        return true;
    }

    @Override
    public boolean isDirect() {
        return true;
    }

    @Override
    public ByteBuf buffer(int initialCapacity, int maxCapacity) {
        return this.pooledDirectBuffer(initialCapacity, maxCapacity);
    }

    @Override
    public ByteBuf pooledBuffer(int initialCapacity, int maxCapacity, int sliceSize) {
        return this.pooledDirectBuffer(initialCapacity, maxCapacity);
    }
}