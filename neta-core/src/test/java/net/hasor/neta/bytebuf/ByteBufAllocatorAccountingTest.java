/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ByteBufAllocatorAccountingTest {
    @Test
    public void capacityChangesAffectActiveBytesButNotCumulativeAllocations() {
        ByteBufAllocatorMetric metric = new ByteBufAllocatorMetric();
        metric.recordAllocation(false, 64);
        metric.recordAllocation(true, 128);
        metric.recordCapacityChange(false, 32);
        metric.recordCapacityChange(true, -64);
        metric.recordCapacityChange(true, 0);
        assertEquals(1, metric.heapAllocations());
        assertEquals(1, metric.directAllocations());
        assertEquals(64, metric.heapBytesAllocated());
        assertEquals(128, metric.directBytesAllocated());
        assertEquals(96, metric.heapActiveBytes());
        assertEquals(64, metric.directActiveBytes());
        assertEquals(2, metric.totalActiveAllocations());
        assertEquals(160, metric.totalActiveBytes());
        metric.recordRelease(false, 96);
        metric.recordRelease(true, 64);
        assertEquals(0, metric.totalActiveAllocations());
        assertEquals(0, metric.totalActiveBytes());
        assertEquals(192, metric.totalBytesAllocated());
    }

    @Test
    public void zeroAndNegativeInitialCapacityStillCountTheBuffer() {
        ByteBufAllocatorMetric metric = new ByteBufAllocatorMetric();
        metric.recordAllocation(false, -1);
        metric.recordAllocation(true, 0);
        assertEquals(2, metric.totalAllocations());
        assertEquals(2, metric.totalActiveAllocations());
        assertEquals(0, metric.totalBytesAllocated());
        metric.recordCapacityChange(false, 17);
        metric.recordCapacityChange(true, 23);
        assertEquals(40, metric.totalActiveBytes());
        metric.recordRelease(false, 17);
        metric.recordRelease(true, 23);
        assertEquals(0, metric.totalActiveBytes());
        assertEquals(0, metric.totalActiveAllocations());
        metric.recordAllocation(false, -1);
        metric.recordRelease(false, -1);
        assertEquals(0, metric.totalActiveAllocations());
    }

    @Test
    public void liveBuffersRemainVisibleAcrossRepeatedRecycling() {
        ByteBufAllocatorMetric metric = new ByteBufAllocatorMetric();
        metric.recordAllocation(false, 101);
        metric.recordAllocation(true, 103);
        for (int i = 0; i < 10000; i++) {
            boolean direct = (i & 1) == 0;
            metric.recordAllocation(direct, 64);
            metric.recordCapacityChange(direct, 32);
            metric.recordRelease(direct, 96);
        }
        assertEquals(10002, metric.totalAllocations());
        assertEquals(640204, metric.totalBytesAllocated());
        assertEquals(1, metric.heapActiveAllocations());
        assertEquals(1, metric.directActiveAllocations());
        assertEquals(101, metric.heapActiveBytes());
        assertEquals(103, metric.directActiveBytes());
        assertTrue(metric.toString().contains("activeBytes=204"));
        metric.recordRelease(false, 101);
        metric.recordRelease(true, 103);
        assertEquals(0, metric.totalActiveAllocations());
        assertEquals(0, metric.totalActiveBytes());
    }

    @Test(timeout = 15000)
    public void concurrentUpdatesAndObservationsConvergeExactly() throws Exception {
        ByteBufAllocatorMetric metric = new ByteBufAllocatorMetric();
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(8);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        for (int thread = 0; thread < 8; thread++) {
            final boolean direct = (thread & 1) == 0;
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 20000; i++) {
                        metric.recordAllocation(direct, 64);
                        metric.recordCapacityChange(direct, 32);
                        metric.recordCapacityChange(direct, -16);
                        metric.recordRelease(direct, 80);
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        while (done.getCount() > 0) {
            assertTrue(metric.heapActiveAllocations() >= 0);
            assertTrue(metric.directActiveAllocations() >= 0);
            // LongAdder observations during updates are not a coherent byte snapshot.
            metric.totalActiveBytes();
            Thread.yield();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(80000, metric.heapAllocations());
        assertEquals(80000, metric.directAllocations());
        assertEquals(160000L * 64, metric.totalBytesAllocated());
        assertEquals(0, metric.totalActiveAllocations());
        assertEquals(0, metric.totalActiveBytes());
    }

    @Test(timeout = 10000)
    public void buffersAllocatedOnOneThreadCanBeReleasedOnAnother() throws Exception {
        ByteBufAllocatorMetric metric = ByteBufAllocator.DEFAULT.metric();
        long count = metric.totalActiveAllocations(), bytes = metric.totalActiveBytes();
        ByteBuf[] buffers = new ByteBuf[2048];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = ByteBuf.wrap(new byte[17 + i % 97]);
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try {
                for (ByteBuf buffer : buffers) {
                    buffer.release();
                }
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        consumer.start();
        consumer.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(count, metric.totalActiveAllocations());
        assertEquals(bytes, metric.totalActiveBytes());
    }
}
