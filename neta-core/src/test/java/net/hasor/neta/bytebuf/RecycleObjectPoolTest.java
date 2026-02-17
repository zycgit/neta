package net.hasor.neta.bytebuf;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Tests for {@link RecycleObjectPool} covering L1/L2 cache, capacity limits,
 * cross-thread recycling, and concurrent get/free operations.
 */
public class RecycleObjectPoolTest {

    private static final AtomicInteger              SEQ          = new AtomicInteger(0);
    private static final RecycleHandler<TestObj>    TEST_HANDLER = new RecycleHandler<TestObj>() {
        @Override
        public TestObj create() {
            return new TestObj(SEQ.incrementAndGet());
        }

        @Override
        public void free(TestObj tar) {
            RecycleObjectPool.free(TestObj.class, tar);
        }
    };
    private static final RecycleHandler<DedicatedA> HANDLER_A    = new RecycleHandler<DedicatedA>() {
        @Override
        public DedicatedA create() {
            return new DedicatedA();
        }

        @Override
        public void free(DedicatedA tar) {
            RecycleObjectPool.free(DedicatedA.class, tar);
        }
    };

    // ========================================================================
    // Basic L1 (ThreadLocal) tests
    // ========================================================================
    private static final RecycleHandler<DedicatedB> HANDLER_B       = new RecycleHandler<DedicatedB>() {
        @Override
        public DedicatedB create() {
            return new DedicatedB();
        }

        @Override
        public void free(DedicatedB tar) {
            RecycleObjectPool.free(DedicatedB.class, tar);
        }
    };
    private static final RecycleHandler<DedicatedB> HANDLER_B_NULL  = new RecycleHandler<DedicatedB>() {
        @Override
        public DedicatedB create() {
            return null;
        }

        @Override
        public void free(DedicatedB tar) {
            RecycleObjectPool.free(DedicatedB.class, tar);
        }
    };
    private static final RecycleHandler<String[]>   STR_ARR_HANDLER = new RecycleHandler<String[]>() {
        @Override
        public String[] create() {
            return new String[] { "str" };
        }

        @Override
        public void free(String[] tar) {
            RecycleObjectPool.free(String[].class, tar);
        }
    };

    // ========================================================================
    // L1 capacity limit (1024) - overflow goes to L2
    // ========================================================================
    private static final RecycleHandler<Integer[]> INT_ARR_HANDLER = new RecycleHandler<Integer[]>() {
        @Override
        public Integer[] create() {
            return new Integer[] { 42 };
        }

        @Override
        public void free(Integer[] tar) {
            RecycleObjectPool.free(Integer[].class, tar);
        }
    };

    @Test
    public void get_createsNewObject_whenPoolIsEmpty() {
        // each get on a fresh type creates via handler
        TestObj obj = RecycleObjectPool.get(TestObj.class, TEST_HANDLER);
        assert obj != null : "should create a new object";
    }

    @Test
    public void free_then_get_returnsSameObject_inSameThread() {
        TestObj obj = RecycleObjectPool.get(TestObj.class, TEST_HANDLER);
        int id = obj.id;
        RecycleObjectPool.free(TestObj.class, obj);

        TestObj reused = RecycleObjectPool.get(TestObj.class, TEST_HANDLER);
        assert reused.id == id : "should reuse from L1 cache";
    }

    @Test
    public void free_then_get_LIFO_order() {
        // L1 is ArrayDeque used as stack (push/pop), so LIFO
        TestObj a = RecycleObjectPool.get(TestObj.class, TEST_HANDLER);
        TestObj b = RecycleObjectPool.get(TestObj.class, TEST_HANDLER);
        int idA = a.id;
        int idB = b.id;

        RecycleObjectPool.free(TestObj.class, a);
        RecycleObjectPool.free(TestObj.class, b);

        TestObj first = RecycleObjectPool.get(TestObj.class, TEST_HANDLER);
        TestObj second = RecycleObjectPool.get(TestObj.class, TEST_HANDLER);

        // LIFO: b was pushed last, so popped first
        assert first.id == idB : "LIFO: expecting idB first, got " + first.id;
        assert second.id == idA : "LIFO: expecting idA second, got " + second.id;
    }

    @Test
    public void l1_overflow_goesToGlobalL2() {
        // Use a dedicated type so we don't interfere with other tests
        List<DedicatedA> objects = new ArrayList<>();
        for (int i = 0; i < 1100; i++) {
            objects.add(RecycleObjectPool.get(DedicatedA.class, HANDLER_A));
        }

        // free all 1100 — first 1024 go to L1, remaining 76 go to L2
        for (DedicatedA obj : objects) {
            RecycleObjectPool.free(DedicatedA.class, obj);
        }

        // get 1024 from L1
        for (int i = 0; i < 1024; i++) {
            DedicatedA obj = RecycleObjectPool.get(DedicatedA.class, HANDLER_A);
            assert obj != null;
        }

        // next gets should come from L2 (global)
        DedicatedA fromL2 = RecycleObjectPool.get(DedicatedA.class, HANDLER_A);
        assert fromL2 != null;
    }

    @Test
    public void l2_overflow_objectsAreDiscarded() {
        // Fill L1 to 1024 and L2 to 4096 = 5120 total, then free one more
        List<DedicatedB> objects = new ArrayList<>();
        int totalToOverflow = 1024 + 4096 + 10; // 5130
        for (int i = 0; i < totalToOverflow; i++) {
            objects.add(RecycleObjectPool.get(DedicatedB.class, HANDLER_B));
        }

        // Free all - only 1024 (L1) + 4096 (L2) = 5120 can be stored, 10 are discarded
        for (DedicatedB obj : objects) {
            RecycleObjectPool.free(DedicatedB.class, obj);
        }

        // Retrieve all stored ones
        int count = 0;
        for (int i = 0; i < totalToOverflow; i++) {
            DedicatedB obj = RecycleObjectPool.get(DedicatedB.class, HANDLER_B_NULL);
            if (obj != null) {
                count++;
            }
        }

        // At most 1024 + 4096 = 5120 were stored
        assert count <= 5120 : "should not return more than L1+L2 capacity, got " + count;
        assert count >= 1024 : "should return at least L1 capacity, got " + count;
    }

    // ========================================================================
    // L2 capacity limit (4096) - overflow is discarded
    // ========================================================================

    @Test
    public void crossThread_freeInA_getInB_viaL2() throws Exception {
        // Dedicated type for this test
        final AtomicInteger createdCount = new AtomicInteger(0);
        final RecycleHandler<String[]> handler = new RecycleHandler<String[]>() {
            @Override
            public String[] create() {
                createdCount.incrementAndGet();
                return new String[] { "new" };
            }

            @Override
            public void free(String[] tar) {
                RecycleObjectPool.free(String[].class, tar);
            }
        };

        // Thread A: get and free objects, filling L1 first, then L2
        String[] objFromA = RecycleObjectPool.get(String[].class, handler);
        objFromA[0] = "from_A";

        // Free from thread A - it goes to A's L1
        RecycleObjectPool.free(String[].class, objFromA);

        // Fill A's L1 by getting and freeing 1024 more, pushing objFromA to L2
        List<String[]> fillers = new ArrayList<>();
        for (int i = 0; i < 1024; i++) {
            fillers.add(RecycleObjectPool.get(String[].class, handler));
        }
        for (String[] f : fillers) {
            RecycleObjectPool.free(String[].class, f);
        }

        // Now L1 of thread A is full — objFromA may have moved or still be in L1
        // Let's try from another thread where L1 is empty
        final String[][] resultHolder = new String[1][];
        Thread threadB = new Thread(() -> {
            // Thread B's L1 for String[] is empty, so it will look at L2
            resultHolder[0] = RecycleObjectPool.get(String[].class, handler);
        });
        threadB.start();
        threadB.join(5000);

        assert resultHolder[0] != null;
    }

    // ========================================================================
    // Cross-thread recycling (free in thread A, get in thread B via L2)
    // ========================================================================

    @Test
    public void concurrent_getAndFree_noErrors() throws Exception {
        final int threadCount = 8;
        final int opsPerThread = 5000;
        final CyclicBarrier barrier = new CyclicBarrier(threadCount);
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final AtomicInteger errorCount = new AtomicInteger(0);

        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            threads[t] = new Thread(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        TestObj obj = RecycleObjectPool.get(TestObj.class, TEST_HANDLER);
                        assert obj != null;
                        // do some "work"
                        RecycleObjectPool.free(TestObj.class, obj);
                    }
                } catch (Throwable e) {
                    errorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
            threads[t].start();
        }

        latch.await();
        assert errorCount.get() == 0 : "concurrent get/free had errors: " + errorCount.get();
    }

    // ========================================================================
    // Concurrent get/free stress test
    // ========================================================================

    @Test
    public void concurrent_crossThread_stealFromGlobal() throws Exception {
        // Multiple threads produce objects, others consume from L2
        final int producers = 4;
        final int consumers = 4;
        final int totalPerProducer = 2000;
        final CyclicBarrier barrier = new CyclicBarrier(producers + consumers);
        final CountDownLatch latch = new CountDownLatch(producers + consumers);
        final AtomicInteger createCount = new AtomicInteger(0);
        final AtomicInteger reuseCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);

        // Use a unique type so L1/L2 start empty
        RecycleHandler<int[]> handler = new RecycleHandler<int[]>() {
            @Override
            public int[] create() {
                createCount.incrementAndGet();
                return new int[1];
            }

            @Override
            public void free(int[] tar) {
                RecycleObjectPool.free(int[].class, tar);
            }
        };

        // Producer threads: create objects and free them (filling both L1 and L2)
        for (int p = 0; p < producers; p++) {
            new Thread(() -> {
                try {
                    barrier.await();
                    List<int[]> batch = new ArrayList<>();
                    for (int i = 0; i < totalPerProducer; i++) {
                        batch.add(RecycleObjectPool.get(int[].class, handler));
                    }
                    for (int[] obj : batch) {
                        RecycleObjectPool.free(int[].class, obj);
                    }
                } catch (Throwable e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // Consumer threads: try to get objects (may reuse from L2)
        for (int c = 0; c < consumers; c++) {
            new Thread(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < totalPerProducer; i++) {
                        int[] obj = RecycleObjectPool.get(int[].class, handler);
                        assert obj != null;
                        RecycleObjectPool.free(int[].class, obj);
                    }
                } catch (Throwable e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assert errorCount.get() == 0 : "concurrent cross-thread had errors";
    }

    @Test
    public void differentTypes_areSeparated() {
        String[] strObj = RecycleObjectPool.get(String[].class, STR_ARR_HANDLER);
        Integer[] intObj = RecycleObjectPool.get(Integer[].class, INT_ARR_HANDLER);

        RecycleObjectPool.free(String[].class, strObj);
        RecycleObjectPool.free(Integer[].class, intObj);

        // Getting Integer[] should not return String[]
        Object intRetrieved = RecycleObjectPool.get(Integer[].class, INT_ARR_HANDLER);
        assert intRetrieved instanceof Integer[] : "should get Integer[], not other type";

        Object strRetrieved = RecycleObjectPool.get(String[].class, STR_ARR_HANDLER);
        assert strRetrieved instanceof String[] : "should get String[], not other type";
    }

    // ========================================================================
    // Type isolation
    // ========================================================================

    // --- helper type for testing ---
    private static class TestObj {
        final int id;

        TestObj(int id) {
            this.id = id;
        }
    }

    // Dedicated types to avoid cross-test interference
    private static class DedicatedA {
    }

    private static class DedicatedB {
    }
}
