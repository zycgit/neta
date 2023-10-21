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
import net.hasor.cobble.io.output.hole.BlackHoleOutputStream;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * readMark <= readIndex <= writerMark <= writerIndex <= capacity
 * @version : 2022-11-01
 * @author 赵永春 (zyc@hasor.net)
 */
public abstract class AbstractByteBufAllocator implements ByteBufAllocator {
    private final static InputStream       EMPTY_IN                = new ByteArrayInputStream(new byte[0]);
    private final static OutputStream      EMPTY_OUT               = new BlackHoleOutputStream();
    protected final      int               initialCapacityByDefault;
    protected final      int               sliceSizeByDefault;
    protected final      NioChunkAllocator heapNioChunkAllocator   = new NioChunkAllocator() {
        public NioChunk allocateBuffer(int capacity) {
            return new NioChunk(ByteBuffer.allocate(capacity));
        }

        public boolean isDirect() {
            return false;
        }
    };
    protected final      NioChunkAllocator directNioChunkAllocator = new NioChunkAllocator() {
        public NioChunk allocateBuffer(int capacity) {
            return new NioChunk(ByteBuffer.allocateDirect(capacity));
        }

        public boolean isDirect() {
            return true;
        }
    };

    /** Create new instance */
    protected AbstractByteBufAllocator(int initialCapacityByDefault, int sliceSizeByDefault) {
        this.initialCapacityByDefault = initialCapacityByDefault;
        this.sliceSizeByDefault = sliceSizeByDefault;
    }

    @Override
    public ByteBuf buffer() {
        return this.buffer(this.initialCapacityByDefault, -1);
    }

    @Override
    public ByteBuf buffer(int initialCapacity) {
        return this.buffer(initialCapacity, initialCapacity);
    }

    @Override
    public StreamByteBuf streamBuffer(int capacity, InputStream inStream) {
        return this.streamBuffer(capacity, inStream, EMPTY_OUT);
    }

    @Override
    public StreamByteBuf streamBuffer(int capacity, OutputStream outStream) {
        return this.streamBuffer(capacity, EMPTY_IN, outStream);
    }

    @Override
    public ByteBuf arrayBuffer() {
        return this.arrayBuffer(this.initialCapacityByDefault, -1);
    }

    @Override
    public ByteBuf wrap(byte[] bytes) {
        return new ArrayByteBuf(this, bytes, bytes.length);
    }

    @Override
    public ByteBuf wrap(ByteBuffer javaBuffer) {
        return new SliceNioByteBuf(this, javaBuffer);
    }

    @Override
    public ByteBuf arrayBuffer(int capacity) {
        return this.arrayBuffer(capacity, capacity);
    }

    @Override
    public ByteBuf arrayBuffer(int initialCapacity, int maxCapacity) {
        return new ArrayByteBuf(this, initialCapacity, maxCapacity);
    }

    @Override
    public ByteBuf heapBuffer() {
        return this.heapBuffer(this.initialCapacityByDefault, -1);
    }

    @Override
    public ByteBuf heapBuffer(int capacity) {
        return this.heapBuffer(capacity, capacity);
    }

    @Override
    public ByteBuf heapBuffer(int initialCapacity, int maxCapacity) {
        return new SliceNioByteBuf(this, initialCapacity, maxCapacity, this.heapNioChunkAllocator);
    }

    @Override
    public StreamByteBuf heapStreamBuffer(int capacity, InputStream inStream) {
        return this.heapStreamBuffer(capacity, inStream, EMPTY_OUT);
    }

    @Override
    public StreamByteBuf heapStreamBuffer(int capacity, OutputStream outStream) {
        return this.heapStreamBuffer(capacity, EMPTY_IN, outStream);
    }

    @Override
    public StreamByteBuf heapStreamBuffer(int capacity, InputStream inStream, OutputStream outStream) {
        return new StreamSliceByteBuf(this, capacity, inStream, outStream, this.heapNioChunkAllocator);
    }

    @Override
    public ByteBuf directBuffer() {
        return this.directBuffer(this.initialCapacityByDefault, -1);
    }

    @Override
    public ByteBuf directBuffer(int capacity) {
        return this.directBuffer(capacity, capacity);
    }

    @Override
    public ByteBuf directBuffer(int initialCapacity, int maxCapacity) {
        return new SliceNioByteBuf(this, initialCapacity, maxCapacity, this.directNioChunkAllocator);
    }

    @Override
    public StreamByteBuf directStreamBuffer(int capacity, InputStream inStream) {
        return this.directStreamBuffer(capacity, inStream, EMPTY_OUT);
    }

    @Override
    public StreamByteBuf directStreamBuffer(int capacity, OutputStream outStream) {
        return this.directStreamBuffer(capacity, EMPTY_IN, outStream);
    }

    @Override
    public StreamByteBuf directStreamBuffer(int capacity, InputStream inStream, OutputStream outStream) {
        return new StreamSliceByteBuf(this, capacity, inStream, outStream, this.directNioChunkAllocator);
    }

    @Override
    public ByteBuf pooledBuffer() {
        return this.pooledBuffer(this.initialCapacityByDefault, -1, this.sliceSizeByDefault);
    }

    @Override
    public ByteBuf pooledBuffer(int maxCapacity, int sliceSize) {
        return this.pooledBuffer(this.initialCapacityByDefault, maxCapacity, sliceSize);
    }

    @Override
    public ByteBuf pooledHeapBuffer() {
        return this.pooledHeapBuffer(this.initialCapacityByDefault, -1, this.sliceSizeByDefault);
    }

    @Override
    public ByteBuf pooledHeapBuffer(int maxCapacity, int sliceSize) {
        return this.pooledHeapBuffer(this.initialCapacityByDefault, maxCapacity, sliceSize);
    }

    @Override
    public ByteBuf pooledHeapBuffer(int initialCapacity, int maxCapacity, int sliceSize) {
        if (maxCapacity < 0) {
            return new PooledNioByteBuf(this, initialCapacity, maxCapacity, sliceSize, this.heapNioChunkAllocator);
        } else {
            return new PooledNioByteBuf(this, Math.min(initialCapacity, maxCapacity), maxCapacity, sliceSize, this.heapNioChunkAllocator);
        }
    }

    @Override
    public ByteBuf pooledDirectBuffer() {
        return this.pooledDirectBuffer(this.initialCapacityByDefault, -1, this.sliceSizeByDefault);
    }

    @Override
    public ByteBuf pooledDirectBuffer(int maxCapacity, int sliceSize) {
        return this.pooledDirectBuffer(maxCapacity, maxCapacity, sliceSize);
    }

    @Override
    public ByteBuf pooledDirectBuffer(int initialCapacity, int maxCapacity, int sliceSize) {
        return new PooledNioByteBuf(this, initialCapacity, maxCapacity, sliceSize, this.directNioChunkAllocator);
    }
}