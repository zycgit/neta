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
 * <p>Implementations are responsible to allocate buffers.</p>
 * <p>Interface design reference netty io.netty.buffer.ByteBufAllocator,
 * The ByteBuf implementation is replaced with ByteBuf</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public interface ByteBufAllocator extends BufferAllocator {
    ByteBufAllocator DEFAULT = ByteBufUtils.defaultAllocator();

    /** Returns the {@link ByteBufAllocatorMetric} for this allocator. */
    ByteBufAllocatorMetric metric();

    /**
     * Allocate a {@link ByteBuf}.
     * If it is a direct or heap buffer depends on the actual implementation.
     */
    ByteBuf buffer();

    /**
     * Allocate a {@link ByteBuf} with the given initial capacity.
     * If it is a direct or heap buffer depends on the actual implementation.
     */
    ByteBuf buffer(int initCapacity);

    /**
     * Allocate a {@link ByteBuf} with the given initial capacity and the given maximal capacity.
     * If it is a direct or heap buffer depends on the actual implementation.
     */
    ByteBuf buffer(int initCapacity, int maxCapacity);

    /** Returns {@code true} if pooled {@link ByteBuf}'s */
    boolean isDirect();

    /** Allocate a {@link ByteBuf}, with the bytes array. */
    ByteBuf ringBuffer(int capacity);

    /** Allocate a {@link ByteBuf}, with the bytes array. */
    ByteBuf ringHeapBuffer(int capacity);

    /** Allocate a {@link ByteBuf}, with the bytes array. */
    ByteBuf ringDirectBuffer(int capacity);

    /** Allocate a heap {@link ByteBuf}. */
    ByteBuf heapBuffer();

    /** Allocate a heap {@link ByteBuf} with the given initial capacity. */
    ByteBuf heapBuffer(int capacity);

    /** Allocate a heap {@link ByteBuf} with the given initial capacity and the given maximal capacity. */
    ByteBuf heapBuffer(int initCapacity, int maxCapacity);

    /** Allocate a direct {@link ByteBuf}. */
    ByteBuf directBuffer();

    /** Allocate a direct {@link ByteBuf} with the given initial capacity. */
    ByteBuf directBuffer(int capacity);

    /** Allocate a direct {@link ByteBuf} with the given initial capacity and the given maximal capacity. */
    ByteBuf directBuffer(int initCapacity, int maxCapacity);

    /*** Allocate pooled {@link ByteBuf}.
     * If it is a direct or heap buffer depends on the actual implementation. */
    ByteBuf pooledBuffer();

    /*** Allocate pooled {@link ByteBuf} with the given maximal capacity.
     * If it is a direct or heap buffer depends on the actual implementation. */
    ByteBuf pooledBuffer(int initCapacity);

    /*** Allocate pooled {@link ByteBuf} with the given maximal capacity.
     * If it is a direct or heap buffer depends on the actual implementation. */
    ByteBuf pooledBuffer(int initCapacity, int maxCapacity);

    //    /*** Allocate pooled {@link ByteBuf}. */
    //    ByteBuf mappedBuffer();
    //
    //    /*** Allocate pooled {@link ByteBuf} with the given maximal capacity. */
    //    ByteBuf mappedBuffer(int memSize, int maxCapacity);
    //
    //    /** Allocate a direct {@link ByteBuf} with the given initial capacity and the given maximal capacity. */
    //    ByteBuf mappedBuffer(int memSize, int maxCapacity, File tempFile);

    /**
     * 分配一个内存-文件交换 {@link ByteBuf}，使用默认阈值（128 KB 内存 / 512 KB 紧凑）。
     * 数据量未超过 memThreshold 时全程基于堆内存；超过后自动换出到临时文件，
     * 已消费的头部数据在 fileBaseOffset 超过 compactThreshold 时触发文件紧凑。
     */
    default ByteBuf swapFile() {
        return swapFile(SwapFileByteBuf.DEFAULT_MEM_THRESHOLD, SwapFileByteBuf.DEFAULT_COMPACT_THRESHOLD);
    }

    /**
     * 分配一个内存-文件交换 {@link ByteBuf}，指定内存阈值，紧凑阈值默认为 memThreshold × 4。
     * @param memThreshold 触发换出到文件的内存字节阈值（当前写指针超过此值时切换）
     */
    default ByteBuf swapFile(int memThreshold) {
        return swapFile(memThreshold, memThreshold * 4);
    }

    /**
     * 分配一个内存-文件交换 {@link ByteBuf}，分别指定内存阈值和文件头部紧凑阈值。
     * @param memThreshold 触发换出到文件的内存字节阈值
     * @param compactThreshold fileBaseOffset 超过此值时触发文件紧凑，以回收磁盘空间
     */
    default ByteBuf swapFile(int memThreshold, int compactThreshold) {
        return new SwapFileByteBuf(this, memThreshold, compactThreshold);
    }
}