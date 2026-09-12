/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Concurrent tests for ByteBuf - retain/release CAS, cross-thread handoff,
 * concurrent read/write, and BufferPool concurrent requests.
 */
public class ConcurrentByteBufTest {

    // ========================================================================
    // Concurrent retain / release (CAS on refCnt)
    // ========================================================================

    @Test
    public void concurrentRetain_thenRelease_allSucceed() throws Exception {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeBytes(new byte[64]);
        buf.markWriter();

        final int threadCount = 16;
        final int retainsPerThread = 1000;
        final CyclicBarrier barrier = new CyclicBarrier(threadCount);
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final AtomicInteger errorCount = new AtomicInteger(0);

        // Phase 1: all threads retain concurrently
        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < retainsPerThread; i++) {
                        buf.retain();
                    }
                } catch (Throwable e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        latch.await();
        assert errorCount.get() == 0 : "retain phase had errors";

        // refCnt should be 1 (initial) + threadCount * retainsPerThread
        int expectedRefCnt = 1 + threadCount * retainsPerThread;
        assert buf.refCnt() == expectedRefCnt : "expected refCnt=" + expectedRefCnt + " got " + buf.refCnt();

        // Phase 2: release all retained counts
        final CyclicBarrier barrier2 = new CyclicBarrier(threadCount);
        final CountDownLatch latch2 = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    barrier2.await();
                    for (int i = 0; i < retainsPerThread; i++) {
                        buf.release();
                    }
                } catch (Throwable e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch2.countDown();
                }
            }).start();
        }
        latch2.await();
        assert errorCount.get() == 0 : "release phase had errors";

        // should be back to refCnt=1
        assert buf.refCnt() == 1 : "expected refCnt=1 got " + buf.refCnt();
        buf.free();
        assert buf.isFree();
    }

    @Test
    public void concurrentRelease_exactlyOneReturnsTrue() throws Exception {
        // When multiple threads try to release the last reference, exactly one should succeed (return true)
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(32);
        buf.writeBytes(new byte[32]);
        buf.markWriter();

        final int threadCount = 8;
        // Retain threadCount-1 more times so total refCnt = threadCount
        buf.retain(threadCount - 1);
        assert buf.refCnt() == threadCount;

        final CyclicBarrier barrier = new CyclicBarrier(threadCount);
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final AtomicInteger trueCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    barrier.await();
                    boolean result = buf.release();
                    if (result) {
                        trueCount.incrementAndGet();
                    }
                } catch (Throwable e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        latch.await();

        assert errorCount.get() == 0 : "concurrent release had errors";
        assert trueCount.get() == 1 : "exactly one thread should get true, got " + trueCount.get();
        assert buf.isFree() : "buffer should be freed";
    }

    // ========================================================================
    // Cross-thread read/write handoff
    // ========================================================================

    @Test
    public void crossThread_writeInA_readInB() throws Exception {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(256);
        byte[] testData = new byte[200];
        for (int i = 0; i < testData.length; i++) {
            testData[i] = (byte) (i & 0xFF);
        }

        final AtomicReference<Throwable> error = new AtomicReference<>();

        // Thread A: write data
        Thread writerThread = new Thread(() -> {
            try {
                buf.writeBytes(testData);
                buf.markWriter();
            } catch (Throwable e) {
                error.set(e);
            }
        });
        writerThread.start();
        writerThread.join(5000);

        assert error.get() == null : "writer error: " + error.get();

        // Thread B: read data
        Thread readerThread = new Thread(() -> {
            try {
                byte[] readBack = new byte[200];
                int read = buf.readBytes(readBack);
                assert read == 200 : "should read 200 bytes";
                for (int i = 0; i < 200; i++) {
                    assert readBack[i] == (byte) (i & 0xFF) : "data mismatch at " + i;
                }
            } catch (Throwable e) {
                error.set(e);
            }
        });
        readerThread.start();
        readerThread.join(5000);

        assert error.get() == null : "reader error: " + error.get();
        buf.free();
    }

    // ========================================================================
    // Concurrent ByteBuf allocate and free
    // ========================================================================

    @Test
    public void concurrent_allocateAndFree_noLeaks() throws Exception {
        final int threadCount = 8;
        final int opsPerThread = 500;
        final CyclicBarrier barrier = new CyclicBarrier(threadCount);
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final AtomicInteger errorCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
                        buf.writeBytes(new byte[] { 1, 2, 3, 4 });
                        buf.markWriter();
                        byte b = buf.readByte();
                        assert b == 1;
                        buf.free();
                        assert buf.isFree();
                    }
                } catch (Throwable e) {
                    errorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assert errorCount.get() == 0 : "concurrent allocate/free had errors: " + errorCount.get();
    }

    // ========================================================================
    // Concurrent retain of already-freed buffer should fail
    // ========================================================================

    @Test
    public void retainAfterFree_throwsException() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeBytes(new byte[] { 1, 2, 3 });
        buf.free();

        try {
            buf.retain();
            assert false : "should throw IllegalStateException";
        } catch (IllegalStateException e) {
            // expected
        }
    }

    @Test
    public void releaseMoreThanRefCnt_throwsException() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeBytes(new byte[] { 1, 2, 3 });

        try {
            buf.release(2); // refCnt is 1, releasing 2 should fail
            assert false : "should throw IllegalStateException";
        } catch (IllegalStateException e) {
            // expected
        }

        // buffer should still be usable
        assert buf.refCnt() == 1;
        buf.free();
    }

    @Test
    public void retainWithInvalidIncrement_throwsException() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);

        try {
            buf.retain(0);
            assert false : "should throw IllegalArgumentException";
        } catch (IllegalArgumentException e) {
            // expected
        }

        try {
            buf.retain(-1);
            assert false : "should throw IllegalArgumentException";
        } catch (IllegalArgumentException e) {
            // expected
        }

        buf.free();
    }

    @Test
    public void releaseWithInvalidDecrement_throwsException() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);

        try {
            buf.release(0);
            assert false : "should throw IllegalArgumentException";
        } catch (IllegalArgumentException e) {
            // expected
        }

        try {
            buf.release(-1);
            assert false : "should throw IllegalArgumentException";
        } catch (IllegalArgumentException e) {
            // expected
        }

        buf.free();
    }

    // ========================================================================
    // Concurrent DirectByteBuffer operations
    // ========================================================================

    @Test
    public void concurrent_directBuffer_operations() throws Exception {
        final int threadCount = 4;
        final int opsPerThread = 200;
        final CyclicBarrier barrier = new CyclicBarrier(threadCount);
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final AtomicInteger errorCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer(128);
                        buf.writeBytes(new byte[] { 10, 20, 30, 40 });
                        buf.markWriter();

                        assert buf.isDirect();
                        assert buf.readByte() == 10;
                        assert buf.readByte() == 20;
                        buf.free();
                        assert buf.isFree();
                    }
                } catch (Throwable e) {
                    errorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assert errorCount.get() == 0 : "concurrent direct buffer ops had errors: " + errorCount.get();
    }

    // ========================================================================
    // Concurrent RingBuffer operations
    // ========================================================================

    @Test
    public void concurrent_ringBuffer_writeAndRead() throws Exception {
        // Each thread gets its own ring buffer - no shared state, but tests object pool concurrent access
        final int threadCount = 4;
        final int opsPerThread = 200;
        final CyclicBarrier barrier = new CyclicBarrier(threadCount);
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final AtomicInteger errorCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(64);
                        ring.writeBytes(new byte[] { 1, 2, 3, 4 });
                        ring.markWriter();

                        assert ring.readByte() == 1;
                        assert ring.readByte() == 2;
                        assert ring.readByte() == 3;
                        assert ring.readByte() == 4;
                        ring.markReader();

                        // Write again after consuming (ring reuse)
                        ring.writeBytes(new byte[] { 5, 6, 7, 8 });
                        ring.markWriter();
                        assert ring.readByte() == 5;

                        ring.free();
                    }
                } catch (Throwable e) {
                    errorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assert errorCount.get() == 0 : "concurrent ring buffer ops had errors: " + errorCount.get();
    }
}
