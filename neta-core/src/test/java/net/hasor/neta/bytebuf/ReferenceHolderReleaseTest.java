/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ReferenceHolderReleaseTest {
    @Test
    public void releaseOverloadsAgreeAcrossReuseAndSharedCounts() {
        CountingHolder single = new CountingHolder();
        CountingHolder explicit = new CountingHolder();
        for (int count = 1; count <= 32; count++) {
            single.resetRefCnt();
            explicit.resetRefCnt();
            if (count > 1) {
                single.retain(count - 1);
                explicit.retain(count - 1);
            }
            for (int remaining = count - 1; remaining >= 0; remaining--) {
                assertEquals(explicit.release(1), single.release());
                assertEquals(remaining, single.refCnt());
            }
            assertEquals(count, single.disposals.get());
            assertEquals(count, explicit.disposals.get());
            try {
                single.release();
                fail("Duplicate release");
            } catch (IllegalStateException expected) {
                assertEquals(count, single.disposals.get());
            }
        }
    }

    @Test(timeout = 20000)
    public void concurrentRetainsAndReleasesDisposeExactlyOnce() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 200; round++) {
                CountingHolder holder = new CountingHolder();
                holder.retain(3);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> tasks = new ArrayList<>();
                for (int worker = 0; worker < 4; worker++) {
                    tasks.add(workers.submit(() -> {
                        start.await();
                        holder.retain();
                        holder.release();
                        holder.release();
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> task : tasks) {
                    task.get();
                }
                assertEquals(0, holder.refCnt());
                assertEquals(1, holder.disposals.get());
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void deallocationFailureDoesNotAllowASecondDisposal() {
        CountingHolder holder = new CountingHolder() {
            @Override
            protected void deallocate() {
                super.deallocate();
                throw new IllegalArgumentException("dispose failed");
            }
        };
        try {
            holder.release();
            fail("Expected disposal failure");
        } catch (IllegalArgumentException expected) {
            assertEquals("dispose failed", expected.getMessage());
        }
        assertEquals(0, holder.refCnt());
        try {
            holder.release();
            fail("Expected duplicate release rejection");
        } catch (IllegalStateException expected) {
            assertEquals(1, holder.disposals.get());
        }
    }

    private static class CountingHolder extends AbstractReferenceHolder {
        final AtomicInteger disposals = new AtomicInteger();

        @Override
        protected void deallocate() {
            this.disposals.incrementAndGet();
        }
    }
}
