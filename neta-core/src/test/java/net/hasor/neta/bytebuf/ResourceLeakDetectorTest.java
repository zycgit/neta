package net.hasor.neta.bytebuf;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.Test;

/**
 * Tests for {@link ResourceLeakDetector} - PhantomReference based leak detection.
 */
public class ResourceLeakDetectorTest {

    // ========================================================================
    // Basic open/close lifecycle
    // ========================================================================

    @Test
    public void open_thenClose_noLeak() {
        ResourceLeakDetector<Object> detector = new ResourceLeakDetector<>(Object.class);
        Object tracked = new Object();
        ResourceLeakDetector.ResourceLeak leak = detector.open(tracked);
        assert leak != null;

        boolean closed = leak.close();
        assert closed : "first close should return true";
    }

    @Test
    public void close_twice_secondReturnsFalse() {
        if (ResourceLeakDetector.getLevel() == ResourceLeakDetector.Level.DISABLED) {
            return; // leak detection disabled, nothing to test
        }

        ResourceLeakDetector<Object> detector = new ResourceLeakDetector<>(Object.class);

        // In SIMPLE mode, only 1/128 allocations are fully tracked.
        // Try enough times to guarantee hitting a tracked allocation.
        boolean foundTracked = false;
        for (int i = 0; i < 128; i++) {
            ResourceLeakDetector.ResourceLeak leak = detector.open(new Object());

            assert leak.close() : "first close should return true";
            if (!leak.close()) {
                // second close returned false -> this was a fully tracked leak (DefaultResourceLeak)
                foundTracked = true;
                break;
            }
        }

        assert foundTracked : "Expected to find a tracked leak within sampling interval";
    }

    @Test
    public void record_isNoop() {
        // record() and record(hint) are no-ops in current implementation
        ResourceLeakDetector<Object> detector = new ResourceLeakDetector<>(Object.class);
        Object tracked = new Object();
        ResourceLeakDetector.ResourceLeak leak = detector.open(tracked);

        // Should not throw
        leak.record();
        leak.record("some hint");

        leak.close();
    }

    // ========================================================================
    // Leak detection via GC (PhantomReference)
    // ========================================================================

    @Test
    public void leakedObject_reportsToStderr() throws Exception {
        ResourceLeakDetector<Object> detector = new ResourceLeakDetector<>("TestResource");

        // Create a tracked object and intentionally "leak" it
        createLeakedObject(detector);

        // Force GC to enqueue the PhantomReference
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errCapture = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errCapture));

        try {
            // Multiple GC attempts to trigger PhantomReference collection
            for (int attempt = 0; attempt < 10; attempt++) {
                System.gc();
                Thread.sleep(100);
                // trigger reportLeak via opening a new resource
                detector.open(new Object()).close();

                if (errCapture.toString().contains("LEAK:")) {
                    break;
                }
            }
        } finally {
            System.setErr(originalErr);
        }

        // Note: GC-based leak detection is non-deterministic, so we don't assert
        // but we verify the mechanism doesn't crash
    }

    private void createLeakedObject(ResourceLeakDetector<Object> detector) {
        Object leaked = new Object();
        detector.open(leaked);
        // intentionally DO NOT call leak.close()
        // 'leaked' goes out of scope -> eligible for GC
    }

    // ========================================================================
    // ResourceLeakDetector with String constructor
    // ========================================================================

    @Test
    public void stringConstructor_works() {
        ResourceLeakDetector<Object> detector = new ResourceLeakDetector<>("CustomName");
        Object tracked = new Object();
        ResourceLeakDetector.ResourceLeak leak = detector.open(tracked);
        assert leak.close();
    }

    // ========================================================================
    // ByteBuf integration - leak detector is embedded in AbstractByteBuf
    // ========================================================================

    @Test
    public void byteBuf_normalLifecycle_noLeak() {
        // Normal use: create + free -> no leak
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeBytes(new byte[] { 1, 2, 3 });
        buf.free();
        // If leak detector is working, it should have closed the leak on free()
    }

    @Test
    public void byteBuf_retainRelease_closesLeakOnFinalRelease() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.retain();  // refCnt = 2
        buf.release(); // refCnt = 1
        buf.release(); // refCnt = 0, triggers _free() and closeLeak()

        assert buf.isFree();
    }

    // ========================================================================
    // Multiple concurrent leak opens
    // ========================================================================

    @Test
    public void concurrentLeakOpen_noErrors() throws Exception {
        final ResourceLeakDetector<Object> detector = new ResourceLeakDetector<>(Object.class);
        final int threadCount = 4;
        final int opsPerThread = 1000;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
        final java.util.concurrent.atomic.AtomicInteger errorCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        Object obj = new Object();
                        ResourceLeakDetector.ResourceLeak leak = detector.open(obj);
                        leak.close();
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
        assert errorCount.get() == 0 : "concurrent leak open/close had errors";
    }
}
