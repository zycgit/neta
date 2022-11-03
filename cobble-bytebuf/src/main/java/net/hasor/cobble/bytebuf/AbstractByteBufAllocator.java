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
import java.nio.ByteBuffer;

/**
 * readMark <= readIndex <= writerMark <= writerIndex <= capacity
 */
public abstract class AbstractByteBufAllocator implements ByteBufAllocator {
    protected final int               initialCapacityByDefault;
    protected final int               sliceSizeByDefault;
    protected final NioChunkAllocator heapNioChunkAllocator   = capacity -> new NioChunk(ByteBuffer.allocate(capacity));
    protected final NioChunkAllocator directNioChunkAllocator = capacity -> new NioChunk(ByteBuffer.allocateDirect(capacity));

    /** Create new instance */
    protected AbstractByteBufAllocator(int initialCapacityByDefault, int sliceSizeByDefault) {
        this.initialCapacityByDefault = initialCapacityByDefault;
        this.sliceSizeByDefault = sliceSizeByDefault;
    }

    @Override
    public ByteBuf buffer() {
        return this.buffer(this.initialCapacityByDefault, this.initialCapacityByDefault);
    }

    @Override
    public ByteBuf buffer(int initialCapacity) {
        return this.buffer(initialCapacity, initialCapacity);
    }

    @Override
    public ByteBuf arrayBuffer() {
        return this.arrayBuffer(this.initialCapacityByDefault, this.initialCapacityByDefault);
    }

    @Override
    public ByteBuf wrap(byte[] bytes) {
        return new ArrayByteBuf(bytes, bytes.length);
    }

    @Override
    public ByteBuf arrayBuffer(int initialCapacity) {
        return this.arrayBuffer(initialCapacity, initialCapacity);
    }

    @Override
    public ByteBuf arrayBuffer(int initialCapacity, int maxCapacity) {
        return new ArrayByteBuf(initialCapacity, maxCapacity);
    }

    @Override
    public ByteBuf heapBuffer() {
        return this.heapBuffer(this.initialCapacityByDefault, this.initialCapacityByDefault);
    }

    @Override
    public ByteBuf heapBuffer(int initialCapacity) {
        return this.heapBuffer(initialCapacity, initialCapacity);
    }

    @Override
    public ByteBuf heapBuffer(int initialCapacity, int maxCapacity) {
        return new SliceNioByteBuf(initialCapacity, maxCapacity, this.heapNioChunkAllocator);
    }

    @Override
    public ByteBuf directBuffer() {
        return this.directBuffer(this.initialCapacityByDefault, this.initialCapacityByDefault);
    }

    @Override
    public ByteBuf directBuffer(int initialCapacity) {
        return this.directBuffer(initialCapacity, initialCapacity);
    }

    @Override
    public ByteBuf directBuffer(int initialCapacity, int maxCapacity) {
        return new SliceNioByteBuf(initialCapacity, maxCapacity, this.directNioChunkAllocator);
    }

    @Override
    public ByteBuf pooledBuffer() {
        return this.pooledBuffer(this.initialCapacityByDefault, this.initialCapacityByDefault, this.sliceSizeByDefault);
    }

    @Override
    public ByteBuf pooledBuffer(int maxCapacity, int sliceSize) {
        return this.pooledBuffer(maxCapacity, maxCapacity, sliceSize);
    }

    @Override
    public ByteBuf pooledHeapBuffer() {
        return this.pooledHeapBuffer(this.initialCapacityByDefault, this.initialCapacityByDefault, this.sliceSizeByDefault);
    }

    @Override
    public ByteBuf pooledHeapBuffer(int maxCapacity, int sliceSize) {
        return this.pooledHeapBuffer(maxCapacity, maxCapacity, sliceSize);
    }

    @Override
    public ByteBuf pooledHeapBuffer(int initialCapacity, int maxCapacity, int sliceSize) {
        return new PooledNioByteBuf(initialCapacity, maxCapacity, sliceSize, this.heapNioChunkAllocator);
    }

    @Override
    public ByteBuf pooledDirectBuffer() {
        return this.pooledDirectBuffer(this.initialCapacityByDefault, this.initialCapacityByDefault, this.sliceSizeByDefault);
    }

    @Override
    public ByteBuf pooledDirectBuffer(int maxCapacity, int sliceSize) {
        return this.pooledDirectBuffer(maxCapacity, maxCapacity, sliceSize);
    }

    @Override
    public ByteBuf pooledDirectBuffer(int initialCapacity, int maxCapacity, int sliceSize) {
        return new PooledNioByteBuf(initialCapacity, maxCapacity, sliceSize, this.directNioChunkAllocator);
    }
}