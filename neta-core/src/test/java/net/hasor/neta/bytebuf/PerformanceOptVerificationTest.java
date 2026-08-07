/*
 * Verification tests for 3 performance optimizations:
 *   1. Ring Buffer: % modulo → & bitwise AND (power-of-2 capacity)
 *   2. BufferRing: custom spinlock → StampedLock
 *   3. SmallBufferCache: L1 trim support
 */
package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class PerformanceOptVerificationTest {
    private static final ByteBufAllocator HEAP_ALLOC   = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR;
    private static final ByteBufAllocator DIRECT_ALLOC = ByteBufUtils.UNPOOLED_DIRECT_ALLOCATOR;

    // ==================== 1. Ring Buffer power-of-2 capacity ====================

    @Test
    public void test_ringHeap_capacityRoundedToPowerOf2() {
        // Request 100 bytes → should round up to 128
        ByteBuf buf = HEAP_ALLOC.ringHeapBuffer(100);
        assert buf.capacity() == 128 : "expected 128, got " + buf.capacity();
        buf.free();
    }

    @Test
    public void test_ringHeap_capacityAlreadyPowerOf2() {
        // Request 256 bytes → should stay 256
        ByteBuf buf = HEAP_ALLOC.ringHeapBuffer(256);
        assert buf.capacity() == 256 : "expected 256, got " + buf.capacity();
        buf.free();
    }

    @Test
    public void test_ringHeap_capacityOne() {
        ByteBuf buf = HEAP_ALLOC.ringHeapBuffer(1);
        assert buf.capacity() == 1 : "expected 1, got " + buf.capacity();
        buf.free();
    }

    @Test
    public void test_ringHeap_capacityZero() {
        ByteBuf buf = HEAP_ALLOC.ringHeapBuffer(0);
        assert buf.capacity() == 0 : "expected 0, got " + buf.capacity();
        buf.free();
    }

    @Test
    public void test_ringDirect_capacityRoundedToPowerOf2() {
        ByteBuf buf = DIRECT_ALLOC.ringDirectBuffer(300);
        assert buf.capacity() == 512 : "expected 512, got " + buf.capacity();
        buf.free();
    }

    @Test
    public void test_ringDirect_capacityAlreadyPowerOf2() {
        ByteBuf buf = DIRECT_ALLOC.ringDirectBuffer(64);
        assert buf.capacity() == 64 : "expected 64, got " + buf.capacity();
        buf.free();
    }

    @Test
    public void test_ringHeap_writeReadWrapAround() {
        // Verify ring buffer with rounded capacity works correctly for wrap-around
        ByteBuf buf = HEAP_ALLOC.ringHeapBuffer(10); // → rounds to 16
        assert buf.capacity() == 16 : "expected 16, got " + buf.capacity();

        // Write 12 bytes, mark reader (consume), write more
        byte[] data = new byte[12];
        for (int i = 0; i < 12; i++)
            data[i] = (byte) (i + 1);
        buf.writeBytes(data);
        buf.markWriter();

        // Read 8 bytes, mark reader
        byte[] read = new byte[8];
        buf.readBytes(read, 0, 8);
        for (int i = 0; i < 8; i++) {
            assert read[i] == (byte) (i + 1) : "mismatch at " + i;
        }
        buf.markReader();

        // Now write 8 more bytes (wraps around)
        byte[] data2 = new byte[8];
        for (int i = 0; i < 8; i++)
            data2[i] = (byte) (20 + i);
        buf.writeBytes(data2);
        buf.markWriter();

        // Read remaining 4 from first write
        byte[] read2 = new byte[4];
        buf.readBytes(read2, 0, 4);
        for (int i = 0; i < 4; i++) {
            assert read2[i] == (byte) (9 + i) : "mismatch at " + i;
        }

        // Read 8 wrapped bytes
        byte[] read3 = new byte[8];
        buf.readBytes(read3, 0, 8);
        for (int i = 0; i < 8; i++) {
            assert read3[i] == (byte) (20 + i) : "wrap mismatch at " + i;
        }

        buf.free();
    }

    @Test
    public void test_ringDirect_writeReadWrapAround() {
        ByteBuf buf = DIRECT_ALLOC.ringDirectBuffer(10); // → rounds to 16
        assert buf.capacity() == 16 : "expected 16, got " + buf.capacity();

        byte[] data = new byte[12];
        for (int i = 0; i < 12; i++)
            data[i] = (byte) (i + 1);
        buf.writeBytes(data);
        buf.markWriter();

        byte[] read = new byte[8];
        buf.readBytes(read, 0, 8);
        for (int i = 0; i < 8; i++) {
            assert read[i] == (byte) (i + 1) : "mismatch at " + i;
        }
        buf.markReader();

        byte[] data2 = new byte[8];
        for (int i = 0; i < 8; i++)
            data2[i] = (byte) (20 + i);
        buf.writeBytes(data2);
        buf.markWriter();

        byte[] read2 = new byte[4];
        buf.readBytes(read2, 0, 4);
        for (int i = 0; i < 4; i++) {
            assert read2[i] == (byte) (9 + i) : "mismatch at " + i;
        }

        byte[] read3 = new byte[8];
        buf.readBytes(read3, 0, 8);
        for (int i = 0; i < 8; i++) {
            assert read3[i] == (byte) (20 + i) : "wrap mismatch at " + i;
        }

        buf.free();
    }

    @Test
    public void test_ringHeap_copy_preservesPowerOf2() {
        ByteBuf buf = HEAP_ALLOC.ringHeapBuffer(50); // → rounds to 64
        assert buf.capacity() == 64 : "expected 64, got " + buf.capacity();

        byte[] data = new byte[30];
        for (int i = 0; i < 30; i++)
            data[i] = (byte) i;
        buf.writeBytes(data);
        buf.markWriter();

        ByteBuf copy = buf.copy();
        assert copy.capacity() == 64 : "copy expected 64, got " + copy.capacity();

        // Verify copy data
        byte[] readFromCopy = new byte[30];
        copy.readBytes(readFromCopy, 0, 30);
        for (int i = 0; i < 30; i++) {
            assert readFromCopy[i] == (byte) i : "copy data mismatch at " + i;
        }

        buf.free();
        copy.free();
    }

    @Test
    public void test_ringDirect_copy_preservesPowerOf2() {
        ByteBuf buf = DIRECT_ALLOC.ringDirectBuffer(50); // → rounds to 64
        assert buf.capacity() == 64 : "expected 64, got " + buf.capacity();

        byte[] data = new byte[30];
        for (int i = 0; i < 30; i++)
            data[i] = (byte) i;
        buf.writeBytes(data);
        buf.markWriter();

        ByteBuf copy = buf.copy();
        assert copy.capacity() == 64 : "copy expected 64, got " + copy.capacity();

        byte[] readFromCopy = new byte[30];
        copy.readBytes(readFromCopy, 0, 30);
        for (int i = 0; i < 30; i++) {
            assert readFromCopy[i] == (byte) i : "copy data mismatch at " + i;
        }

        buf.free();
        copy.free();
    }

    @Test
    public void test_ringHeap_singleByteWrapAround() {
        // Full single-byte wrap around with capacity 4
        ByteBuf buf = HEAP_ALLOC.ringHeapBuffer(4);
        assert buf.capacity() == 4 : "expected 4, got " + buf.capacity();

        // Write 3 bytes
        buf.writeByte((byte) 'A');
        buf.writeByte((byte) 'B');
        buf.writeByte((byte) 'C');
        buf.markWriter();

        // Read 2 bytes
        assert buf.readByte() == (byte) 'A';
        assert buf.readByte() == (byte) 'B';
        buf.markReader();

        // Now write 3 more (wraps around)
        buf.writeByte((byte) 'D');
        buf.writeByte((byte) 'E');
        buf.writeByte((byte) 'F');
        buf.markWriter();

        // Read all 4
        assert buf.readByte() == (byte) 'C';
        assert buf.readByte() == (byte) 'D';
        assert buf.readByte() == (byte) 'E';
        assert buf.readByte() == (byte) 'F';

        buf.free();
    }

    @Test
    public void test_ringHeap_sliceOff_afterRounding() {
        ByteBuf buf = HEAP_ALLOC.ringHeapBuffer(10); // → rounds to 16
        byte[] data = { 1, 2, 3, 4, 5 };
        buf.writeBytes(data);
        buf.markWriter();

        ByteBuf slice = buf.sliceOff(3);
        assert slice.readableBytes() == 3;
        assert slice.readByte() == 1;
        assert slice.readByte() == 2;
        assert slice.readByte() == 3;

        assert buf.readableBytes() == 2;
        assert buf.readByte() == 4;
        assert buf.readByte() == 5;

        slice.free();
        buf.free();
    }

    // ==================== 2. BufferRing StampedLock ====================

    @Test
    public void test_bufferRing_concurrentReadWrite() throws Exception {
        BufferRing<String> ring = new BufferRing<>();
        ring.add("A");
        ring.add("B");
        ring.add("C");

        int threadCount = 8;
        int opsPerThread = 1000;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        if (tid % 2 == 0) {
                            // Reader thread
                            String result = ring.next();
                            if (result == null) {
                                errors.incrementAndGet();
                            }
                        } else {
                            // Writer thread: add then remove
                            String val = "T" + tid + "_" + i;
                            ring.add(val);
                            ring.remove(val);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assert errors.get() == 0 : "concurrent errors: " + errors.get();
        assert ring.size() == 3 : "expected size 3, got " + ring.size();
    }

    @Test
    public void test_bufferRing_findAfterStampedLock() {
        BufferRing<Integer> ring = new BufferRing<>();
        ring.add(10);
        ring.add(20);
        ring.add(30);

        assert ring.size() == 3;
        Integer found = ring.find(0);
        assert found != null : "find(0) should not be null";
        found = ring.find(1);
        assert found != null : "find(1) should not be null";
    }

    // ==================== 3. SmallBufferCache trim ====================

    @Test
    public void test_trimSmallBufferCache_clearsL1() {
        // Allocate and free some small buffers to populate L1
        for (int i = 0; i < 10; i++) {
            byte[] buf = SmallBufferCache.allocHeap(16);
            SmallBufferCache.freeHeap(buf);
        }

        int sizeBefore = SmallBufferCache.currentThreadCacheSize();
        assert sizeBefore > 0 : "L1 cache should have entries, got " + sizeBefore;

        SmallBufferCache.trimCurrentThread();
        int sizeAfter = SmallBufferCache.currentThreadCacheSize();
        assert sizeAfter == 0 : "L1 cache should be empty after trim, got " + sizeAfter;
    }

    @Test
    public void test_trimSmallBufferCache_directBuffers() {
        // Allocate and free some small direct buffers to populate L1
        for (int i = 0; i < 5; i++) {
            ByteBuffer buf = SmallBufferCache.allocDirect(32);
            SmallBufferCache.freeDirect(buf);
        }

        int sizeBefore = SmallBufferCache.currentThreadCacheSize();
        assert sizeBefore > 0 : "L1 direct cache should have entries, got " + sizeBefore;

        SmallBufferCache.trimCurrentThread();
        int sizeAfter = SmallBufferCache.currentThreadCacheSize();
        assert sizeAfter == 0 : "L1 cache should be empty after trim, got " + sizeAfter;
    }

    @Test
    public void test_trimSmallBufferCache_reusableAfterTrim() {
        // After trimming, allocations should still work normally
        for (int i = 0; i < 5; i++) {
            byte[] buf = SmallBufferCache.allocHeap(64);
            SmallBufferCache.freeHeap(buf);
        }
        SmallBufferCache.trimCurrentThread();

        // Allocate again — should get new (or L2) buffers
        byte[] buf = SmallBufferCache.allocHeap(64);
        assert buf != null;
        assert buf.length == 64;
        SmallBufferCache.freeHeap(buf);
    }

    @Test
    public void test_ringHeap_variousNonPowerOf2Sizes() {
        // Test several non-power-of-2 sizes to verify correct rounding
        int[][] testCases = { { 3, 4 }, { 5, 8 }, { 9, 16 }, { 17, 32 }, { 33, 64 }, { 65, 128 }, { 129, 256 }, { 257, 512 }, { 513, 1024 }, { 1000, 1024 }, { 2000, 2048 }, { 4000, 4096 } };
        for (int[] tc : testCases) {
            ByteBuf buf = HEAP_ALLOC.ringHeapBuffer(tc[0]);
            assert buf.capacity() == tc[1] : "for input " + tc[0] + ", expected " + tc[1] + ", got " + buf.capacity();
            buf.free();
        }
    }
}
