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
package net.hasor.cobble.bytebuf;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * <p>Implementations are responsible to allocate buffers.</p>
 *
 * <p>Interface design reference netty io.netty.buffer.ByteBufAllocator,
 * The ByteBuf implementation is replaced with cobble.bytebuf</p>
 *
 * @version : 2022-11-01
 * @author 赵永春 (zyc@hasor.net)
 */
public interface ByteBufAllocator {
    ByteBufAllocator DEFAULT = ByteBufUtil.DEFAULT_ALLOCATOR;

    /** Allocate a {@link ByteBuf}.
     * If it is a direct or heap buffer depends on the actual implementation. */
    ByteBuf buffer();

    /** Allocate a {@link ByteBuf} with the given initial capacity.
     * If it is a direct or heap buffer depends on the actual implementation. */
    ByteBuf buffer(int initialCapacity);

    /** Allocate a {@link ByteBuf} with the given initial capacity and the given maximal capacity.
     * If it is a direct or heap buffer depends on the actual implementation. */
    ByteBuf buffer(int initialCapacity, int maxCapacity);

    /** Allocate a heap {@link StreamByteBuf} and connect InputStream. */
    StreamByteBuf streamBuffer(int capacity, InputStream inStream);

    /** Allocate a heap {@link StreamByteBuf} and connect OutputStream. */
    StreamByteBuf streamBuffer(int capacity, OutputStream outStream);

    /** Allocate a heap {@link StreamByteBuf} and connect InputStream and OutputStream. */
    StreamByteBuf streamBuffer(int capacity, InputStream inStream, OutputStream outStream);

    /** Returns {@code true} if direct {@link ByteBuf}'s */
    boolean isPooled();

    /** Returns {@code true} if pooled {@link ByteBuf}'s */
    boolean isDirect();

    /** Allocate a {@link ByteBuf}, with the bytes array. */
    ByteBuf arrayBuffer();

    /** Allocate a {@link ByteBuf}, with the bytes array. */
    ByteBuf wrap(byte[] bytes);

    /** Allocate a {@link ByteBuf}, with the ByteBuffer. */
    ByteBuf wrap(ByteBuffer javaBuffer);

    /** Allocate a {@link ByteBuf}, with the bytes array. */
    ByteBuf arrayBuffer(int capacity);

    /** Allocate a {@link ByteBuf}, with the bytes array. */
    ByteBuf arrayBuffer(int initialCapacity, int maxCapacity);

    /** Allocate a heap {@link ByteBuf}. */
    ByteBuf heapBuffer();

    /** Allocate a heap {@link ByteBuf} with the given initial capacity. */
    ByteBuf heapBuffer(int capacity);

    /** Allocate a heap {@link ByteBuf} with the given initial capacity and the given maximal capacity. */
    ByteBuf heapBuffer(int initialCapacity, int maxCapacity);

    /** Allocate a heap {@link StreamByteBuf} and connect InputStream. */
    StreamByteBuf heapStreamBuffer(int capacity, InputStream inStream);

    /** Allocate a heap {@link StreamByteBuf} and connect OutputStream. */
    StreamByteBuf heapStreamBuffer(int capacity, OutputStream outStream);

    /** Allocate a heap {@link StreamByteBuf} and connect InputStream and OutputStream. */
    StreamByteBuf heapStreamBuffer(int capacity, InputStream inStream, OutputStream outStream);

    /** Allocate a direct {@link ByteBuf}. */
    ByteBuf directBuffer();

    /** Allocate a direct {@link ByteBuf} with the given initial capacity. */
    ByteBuf directBuffer(int capacity);

    /** Allocate a direct {@link ByteBuf} with the given initial capacity and the given maximal capacity. */
    ByteBuf directBuffer(int initialCapacity, int maxCapacity);

    /** Allocate a heap {@link StreamByteBuf} and connect InputStream. */
    StreamByteBuf directStreamBuffer(int capacity, InputStream inStream);

    /** Allocate a heap {@link StreamByteBuf} and connect OutputStream. */
    StreamByteBuf directStreamBuffer(int capacity, OutputStream outStream);

    /** Allocate a heap {@link StreamByteBuf} and connect InputStream and OutputStream. */
    StreamByteBuf directStreamBuffer(int capacity, InputStream inStream, OutputStream outStream);

    /*** Allocate pooled {@link ByteBuf}.
     * If it is a direct or heap buffer depends on the actual implementation. */
    ByteBuf pooledBuffer();

    /*** Allocate pooled {@link ByteBuf} with the given maximal capacity.
     * If it is a direct or heap buffer depends on the actual implementation. */
    ByteBuf pooledBuffer(int maxCapacity, int sliceSize);

    /** Allocate a direct {@link ByteBuf} with the given initial capacity and the given maximal capacity.
     * If it is a direct or heap buffer depends on the actual implementation. */
    ByteBuf pooledBuffer(int initialCapacity, int maxCapacity, int sliceSize);

    /*** Allocate pooled {@link ByteBuf}. */
    ByteBuf pooledHeapBuffer();

    /*** Allocate pooled {@link ByteBuf} with the given maximal capacity. */
    ByteBuf pooledHeapBuffer(int maxCapacity, int sliceSize);

    /** Allocate a direct {@link ByteBuf} with the given initial capacity and the given maximal capacity. */
    ByteBuf pooledHeapBuffer(int initialCapacity, int maxCapacity, int sliceSize);

    /*** Allocate pooled {@link ByteBuf}. */
    ByteBuf pooledDirectBuffer();

    /*** Allocate pooled {@link ByteBuf} with the given maximal capacity. */
    ByteBuf pooledDirectBuffer(int maxCapacity, int sliceSize);

    /** Allocate a direct {@link ByteBuf} with the given initial capacity and the given maximal capacity. */
    ByteBuf pooledDirectBuffer(int initialCapacity, int maxCapacity, int sliceSize);

    //    /*** Allocate pooled {@link ByteBuf}. */
    //    ByteBuf mappedBuffer();
    //
    //    /*** Allocate pooled {@link ByteBuf} with the given maximal capacity. */
    //    ByteBuf mappedBuffer(int memSize, int maxCapacity);
    //
    //    /** Allocate a direct {@link ByteBuf} with the given initial capacity and the given maximal capacity. */
    //    ByteBuf mappedBuffer(int memSize, int maxCapacity, File tempFile);
}