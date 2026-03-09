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
 * High-level allocation entry point for Neta {@link ByteBuf} implementations.
 * <p>Besides plain heap or direct buffers, the allocator API also exposes
 * pool-backed buffers, fixed-capacity ring buffers, and spill-to-disk
 * swap-file buffers. The interface extends {@link BufferAllocator} so the same
 * implementation can also provide raw JVM {@link java.nio.ByteBuffer} storage
 * for lower-level transport code.
 * <p>Its overall role is similar to Netty's allocator layer, but it targets the
 * buffer types defined in this package rather than Netty's {@code ByteBuf}
 * hierarchy.
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
     * Allocates a memory-swap {@link ByteBuf} using all defaults:
     * 128 KB memory threshold, 512 KB segment size, and {@code "neta-swap-"} temp file prefix.
     */
    default ByteBuf swapFile() {
        return swapFile(SwapFileByteBuf.DEFAULT_MEM_THRESHOLD, SwapFileByteBuf.DEFAULT_SEGMENT_SIZE, SwapFileByteBuf.DEFAULT_TEMP_FILE_PREFIX);
    }

    /**
     * Allocates a memory-swap {@link ByteBuf} with a custom memory threshold.
     * The segment size defaults to {@code memThreshold × 4}.
     * @param memThreshold bytes to keep in heap before spilling to disk
     */
    default ByteBuf swapFile(int memThreshold) {
        return swapFile(memThreshold, memThreshold * 4, SwapFileByteBuf.DEFAULT_TEMP_FILE_PREFIX);
    }

    /**
     * Allocates a memory-swap {@link ByteBuf} with a custom memory threshold and segment size.
     * Uses the default temp file prefix ({@code "neta-swap-"}).
     * @param memThreshold bytes to keep in heap before spilling to disk
     * @param segmentSize maximum size (bytes) of each temporary file segment
     */
    default ByteBuf swapFile(int memThreshold, int segmentSize) {
        return swapFile(memThreshold, segmentSize, SwapFileByteBuf.DEFAULT_TEMP_FILE_PREFIX);
    }

    /**
     * Allocates a memory-swap {@link ByteBuf} with full control over all three parameters.
     * <p>Data stays in heap memory until {@code writerIndex} exceeds {@code memThreshold}, then
     * overflows into a deque of fixed-size temporary files named {@code tempFilePrefix + "*.buf"}.
     * Fully consumed segments are deleted O(1) with no data copying.
     * @param memThreshold bytes to keep in heap before spilling to disk
     * @param segmentSize maximum size (bytes) of each temporary file segment
     * @param tempFilePrefix prefix string for temporary swap files (passed to {@link java.io.File#createTempFile})
     */
    default ByteBuf swapFile(int memThreshold, int segmentSize, String tempFilePrefix) {
        return new SwapFileByteBuf(this, memThreshold, segmentSize, tempFilePrefix, false);
    }

    // ── direct (mmap) variants ────────────────────────────────────────────────

    /**
     * Allocates a memory-swap {@link ByteBuf} backed by <em>direct</em> (memory-mapped) file
     * segments, using all defaults (512 KB memory threshold, 64 MB segment, {@code "neta-swap-"} prefix).
     * <p>Each spill segment is pre-mapped into native off-heap memory via
     * {@link java.nio.MappedByteBuffer}.  Data never touches the Java heap once spilled,
     * reducing GC pressure and enabling near-zero-copy I/O.
     * <p><b>Compatibility:</b> requires OS {@code mmap} support.  Not available on some Android
     * versions or sandboxed JVM environments — fall back to {@link #swapFile()} in those cases.
     */
    default ByteBuf swapFileDirect() {
        return swapFileDirect(SwapFileByteBuf.DEFAULT_MEM_THRESHOLD, SwapFileByteBuf.DEFAULT_SEGMENT_SIZE, SwapFileByteBuf.DEFAULT_TEMP_FILE_PREFIX);
    }

    /**
     * Allocates a direct memory-swap {@link ByteBuf} with a custom memory threshold.
     * Segment size defaults to {@code memThreshold × 4}.
     * @param memThreshold bytes to keep in heap before spilling to disk
     */
    default ByteBuf swapFileDirect(int memThreshold) {
        return swapFileDirect(memThreshold, memThreshold * 4, SwapFileByteBuf.DEFAULT_TEMP_FILE_PREFIX);
    }

    /**
     * Allocates a direct memory-swap {@link ByteBuf} with custom memory threshold and segment size.
     * Uses the default temp file prefix ({@code "neta-swap-"}).
     * @param memThreshold bytes to keep in heap before spilling to disk
     * @param segmentSize maximum size (bytes) of each memory-mapped segment
     */
    default ByteBuf swapFileDirect(int memThreshold, int segmentSize) {
        return swapFileDirect(memThreshold, segmentSize, SwapFileByteBuf.DEFAULT_TEMP_FILE_PREFIX);
    }

    /**
     * Allocates a direct memory-swap {@link ByteBuf} with full control over all three parameters.
     * <p>File segments are memory-mapped ({@link java.nio.MappedByteBuffer}); each segment file is
     * pre-extended to {@code segmentSize} bytes at creation time so the full region can be mapped.
     * @param memThreshold bytes to keep in heap before spilling to disk
     * @param segmentSize size (bytes) of each memory-mapped segment file
     * @param tempFilePrefix prefix string for temporary swap files
     */
    default ByteBuf swapFileDirect(int memThreshold, int segmentSize, String tempFilePrefix) {
        return new SwapFileByteBuf(this, memThreshold, segmentSize, tempFilePrefix, true);
    }
}