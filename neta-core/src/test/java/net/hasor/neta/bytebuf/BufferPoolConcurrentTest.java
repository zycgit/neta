/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Concurrent and edge-case tests for {@link BufferPool}, {@link BufferArena}, and page management.
 * Covers: concurrent allocation/deallocation, arena migration under contention,
 * multi-chunk lifecycle, memoryCapacity tracking, and oversize allocation.
 */
public class BufferPoolConcurrentTest {

    // ========================================================================
    // Concurrent allocation & deallocation
    // ========================================================================

    @Test
    public void concurrent_allocAndFree_multipleThreads_noErrors() throws Exception {
        AtomicInteger addrGen = new AtomicInteger();
        BufferPool pool = new BufferPool(1, -1, 5) {
            @Override
            protected int newMemAddress() {
                return addrGen.incrementAndGet();
            }
        };

        int threadCount = 8;
        int opsPerThread = 200;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    barrier.await();
                    List<Buffer> buffers = new ArrayList<>();
                    for (int i = 0; i < opsPerThread; i++) {
                        int size = 1 + (i % 16); // 1~16 pages
                        Buffer buf = pool.requestBuffer(size, ByteBuffer::allocate);
                        assert buf != null : "requestBuffer returned null";
                        assert buf.capacity() >= size : "capacity < requested: " + buf.capacity() + " < " + size;
                        buffers.add(buf);
                    }
                    // Free all
                    for (Buffer buf : buffers) {
                        buf.free();
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        done.await(30, TimeUnit.SECONDS);
        assert errors.get() == 0 : "Concurrent alloc/free had " + errors.get() + " errors";
    }

    @Test
    public void concurrent_allocAndFree_interleavedOrder() throws Exception {
        AtomicInteger addrGen = new AtomicInteger();
        BufferPool pool = new BufferPool(1, -1, 5) {
            @Override
            protected int newMemAddress() {
                return addrGen.incrementAndGet();
            }
        };

        int threadCount = 4;
        int opsPerThread = 300;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    barrier.await();
                    List<Buffer> active = new ArrayList<>();
                    for (int i = 0; i < opsPerThread; i++) {
                        // Allocate
                        Buffer buf = pool.requestBuffer(1 + (i % 8), ByteBuffer::allocate);
                        active.add(buf);

                        // Free oldest every 3rd iteration to interleave alloc/free
                        if (active.size() > 3) {
                            active.remove(0).free();
                        }
                    }
                    // Cleanup remaining
                    for (Buffer buf : active) {
                        buf.free();
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        done.await(30, TimeUnit.SECONDS);
        assert errors.get() == 0 : "Interleaved alloc/free had errors";
    }

    // ========================================================================
    // Arena migration under contention
    // ========================================================================

    @Test
    public void arenaMigration_allocFreePattern_tracksCorrectly() {
        AtomicInteger addrGen = new AtomicInteger();
        BufferPool pool = new BufferPool(1, 1, 5) {
            @Override
            protected int newMemAddress() {
                return addrGen.incrementAndGet();
            }
        };

        // 32 pages total (2^5), pageSize=1

        // Allocate 25% — should go from qInit to q000 (1%~50%)
        Buffer b1 = pool.requestBuffer(8, ByteBuffer::allocate); // 8/32 = 25%
        int totalChunks = pool.qInit.getChunkCount() + pool.q000.getChunkCount() + pool.q025.getChunkCount() + pool.q050.getChunkCount() + pool.q075.getChunkCount() + pool.q100.getChunkCount();
        assert totalChunks == 1 : "Expected 1 chunk, got " + totalChunks;

        // Allocate to fill to 75%
        Buffer b2 = pool.requestBuffer(8, ByteBuffer::allocate); // 16/32 = 50%
        Buffer b3 = pool.requestBuffer(8, ByteBuffer::allocate); // 24/32 = 75%

        // Free all — should migrate back toward qInit
        b3.free();
        b2.free();
        b1.free();

        // After all freed, chunk should be in qInit (0% usage, but not released since no prev)
        totalChunks = pool.qInit.getChunkCount() + pool.q000.getChunkCount() + pool.q025.getChunkCount() + pool.q050.getChunkCount() + pool.q075.getChunkCount() + pool.q100.getChunkCount();
        assert totalChunks == 1 : "Expected 1 chunk after full free, got " + totalChunks;
    }

    // ========================================================================
    // Oversize allocation (> memoryChunkSize)
    // ========================================================================

    @Test
    public void oversize_allocation_returnsBigBuffer() {
        AtomicInteger addrGen = new AtomicInteger();
        BufferPool pool = new BufferPool(1, 1, 5) {
            @Override
            protected int newMemAddress() {
                return addrGen.incrementAndGet();
            }
        };

        // 2^5 = 32, request > 32 should get a non-pooled buffer
        Buffer bigBuf = pool.requestBuffer(64, ByteBuffer::allocate);
        assert bigBuf != null;
        assert bigBuf.capacity() == 64;
        assert bigBuf instanceof BufferWrap : "Oversize should be BufferWrap, got " + bigBuf.getClass().getSimpleName();

        // No chunks should have been created for this
        assert pool.qInit.getChunkCount() == 0;

        bigBuf.free();
    }

    // ========================================================================
    // OOM recovery — after OOM, existing buffers are still usable
    // ========================================================================

    @Test
    public void oom_recovery_existingBuffersStillUsable() {
        AtomicInteger addrGen = new AtomicInteger();
        BufferPool pool = new BufferPool(1, 1, 5) {
            @Override
            protected int newMemAddress() {
                return addrGen.incrementAndGet();
            }
        };

        // Fill the only chunk completely
        Buffer b1 = pool.requestBuffer(16, ByteBuffer::allocate); // 16/32
        Buffer b2 = pool.requestBuffer(16, ByteBuffer::allocate); // 32/32 = 100%

        // Should throw OOM (maxChunks=1, chunk is full)
        try {
            pool.requestBuffer(1, ByteBuffer::allocate);
            assert false : "Expected OutOfMemoryPoolException";
        } catch (OutOfMemoryPoolException e) {
            // Expected
        }

        // Existing buffers should still work
        b1.put(0, (byte) 42);
        assert b1.get(0) == 42;

        // Free some, then alloc should work again
        b2.free();
        Buffer b3 = pool.requestBuffer(8, ByteBuffer::allocate);
        assert b3 != null;
        assert b3.capacity() == 8;
        b3.free();
        b1.free();
    }

    // ========================================================================
    // Multi-chunk lifecycle
    // ========================================================================

    @Test
    public void multiChunk_allocAcrossMultipleChunks() {
        AtomicInteger addrGen = new AtomicInteger();
        BufferPool pool = new BufferPool(1, 10, 3) { // 2^3=8 pages per chunk, pageSize=1
            @Override
            protected int newMemAddress() {
                return addrGen.incrementAndGet();
            }
        };

        // Fill first chunk (8 pages)
        List<Buffer> buffers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            buffers.add(pool.requestBuffer(1, ByteBuffer::allocate));
        }

        // Next alloc should create a new chunk
        Buffer overflow = pool.requestBuffer(1, ByteBuffer::allocate);
        assert overflow != null;
        buffers.add(overflow);

        // Free all
        for (Buffer buf : buffers) {
            buf.free();
        }
    }

    // ========================================================================
    // Data integrity through pool lifecycle
    // ========================================================================

    @Test
    public void dataIntegrity_writeReadThroughPoolLifecycle() {
        AtomicInteger addrGen = new AtomicInteger();
        BufferPool pool = new BufferPool(4, -1, 5) { // pageSize=4
            @Override
            protected int newMemAddress() {
                return addrGen.incrementAndGet();
            }
        };

        // Allocate, write data, read back
        Buffer buf = pool.requestBuffer(16, ByteBuffer::allocate);
        for (int i = 0; i < 16; i++) {
            buf.put(i, (byte) (i + 1));
        }
        for (int i = 0; i < 16; i++) {
            assert buf.get(i) == (byte) (i + 1) : "Data mismatch at index " + i;
        }

        buf.free();

        // Allocate again — may reuse same memory, data should still be writable
        Buffer buf2 = pool.requestBuffer(16, ByteBuffer::allocate);
        for (int i = 0; i < 16; i++) {
            buf2.put(i, (byte) (100 + i));
        }
        for (int i = 0; i < 16; i++) {
            assert buf2.get(i) == (byte) (100 + i) : "Data mismatch after reuse at index " + i;
        }
        buf2.free();
    }

    // ========================================================================
    // Concurrent arena migration stress
    // ========================================================================

    @Test
    public void concurrent_arenaMigrationStress() throws Exception {
        AtomicInteger addrGen = new AtomicInteger();
        BufferPool pool = new BufferPool(1, -1, 5) {
            @Override
            protected int newMemAddress() {
                return addrGen.incrementAndGet();
            }
        };

        int threadCount = 4;
        int rounds = 100;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    barrier.await();
                    for (int r = 0; r < rounds; r++) {
                        // Rapid alloc-free cycles that trigger arena migration
                        List<Buffer> bufs = new ArrayList<>();
                        for (int i = 0; i < 10; i++) {
                            bufs.add(pool.requestBuffer(1 + (i % 4), ByteBuffer::allocate));
                        }
                        // free in reverse to trigger different migration paths
                        Collections.reverse(bufs);
                        for (Buffer buf : bufs) {
                            buf.free();
                        }
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        done.await(30, TimeUnit.SECONDS);
        assert errors.get() == 0 : "Arena migration stress had " + errors.get() + " errors";
    }
}
