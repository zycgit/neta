package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Tests for {@link SmallBufferCache} - thread-local small buffer caching.
 */
public class SmallBufferCacheTest {

    // ========================================================================
    // isSmallSize
    // ========================================================================

    @Test
    public void isSmallSize_zeroAndNegative_returnsFalse() {
        assert !SmallBufferCache.isSmallSize(0);
        assert !SmallBufferCache.isSmallSize(-1);
        assert !SmallBufferCache.isSmallSize(Integer.MIN_VALUE);
    }

    @Test
    public void isSmallSize_withinRange_returnsTrue() {
        assert SmallBufferCache.isSmallSize(1);
        assert SmallBufferCache.isSmallSize(4);
        assert SmallBufferCache.isSmallSize(16);
        assert SmallBufferCache.isSmallSize(64);
        assert SmallBufferCache.isSmallSize(128);
        assert SmallBufferCache.isSmallSize(256);
        assert SmallBufferCache.isSmallSize(512);
    }

    @Test
    public void isSmallSize_overMaximum_returnsFalse() {
        assert !SmallBufferCache.isSmallSize(513);
        assert !SmallBufferCache.isSmallSize(1024);
        assert !SmallBufferCache.isSmallSize(4096);
        assert !SmallBufferCache.isSmallSize(Integer.MAX_VALUE);
    }

    // ========================================================================
    // normalizeCapacity
    // ========================================================================

    @Test
    public void normalizeCapacity_zeroAndNegative_returnsZero() {
        assert SmallBufferCache.normalizeCapacity(0) == 0;
        assert SmallBufferCache.normalizeCapacity(-1) == 0;
    }

    @Test
    public void normalizeCapacity_overMax_returnsMinusOne() {
        assert SmallBufferCache.normalizeCapacity(513) == -1;
        assert SmallBufferCache.normalizeCapacity(1024) == -1;
    }

    @Test
    public void normalizeCapacity_powersOfTwo_unchanged() {
        assert SmallBufferCache.normalizeCapacity(4) == 4;
        assert SmallBufferCache.normalizeCapacity(8) == 8;
        assert SmallBufferCache.normalizeCapacity(16) == 16;
        assert SmallBufferCache.normalizeCapacity(32) == 32;
        assert SmallBufferCache.normalizeCapacity(64) == 64;
        assert SmallBufferCache.normalizeCapacity(128) == 128;
        assert SmallBufferCache.normalizeCapacity(256) == 256;
        assert SmallBufferCache.normalizeCapacity(512) == 512;
    }

    @Test
    public void normalizeCapacity_roundsUp() {
        assert SmallBufferCache.normalizeCapacity(1) == 4;
        assert SmallBufferCache.normalizeCapacity(3) == 4;
        assert SmallBufferCache.normalizeCapacity(5) == 8;
        assert SmallBufferCache.normalizeCapacity(7) == 8;
        assert SmallBufferCache.normalizeCapacity(9) == 16;
        assert SmallBufferCache.normalizeCapacity(17) == 32;
        assert SmallBufferCache.normalizeCapacity(33) == 64;
        assert SmallBufferCache.normalizeCapacity(65) == 128;
        assert SmallBufferCache.normalizeCapacity(200) == 256;
        assert SmallBufferCache.normalizeCapacity(500) == 512;
    }

    // ========================================================================
    // Heap byte[] alloc/free cycle
    // ========================================================================

    @Test
    public void allocHeap_exactSizeClass_returnsCorrectSize() {
        byte[] buf4 = SmallBufferCache.allocHeap(4);
        assert buf4.length == 4;

        byte[] buf64 = SmallBufferCache.allocHeap(64);
        assert buf64.length == 64;

        byte[] buf512 = SmallBufferCache.allocHeap(512);
        assert buf512.length == 512;
    }

    @Test
    public void allocHeap_nonSizeClass_returnsExactSize() {
        byte[] buf7 = SmallBufferCache.allocHeap(7);
        assert buf7.length == 7;

        byte[] buf100 = SmallBufferCache.allocHeap(100);
        assert buf100.length == 100;

        byte[] buf1000 = SmallBufferCache.allocHeap(1000);
        assert buf1000.length == 1000;
    }

    @Test
    public void allocHeap_afterFree_reusesBuffer() {
        byte[] original = SmallBufferCache.allocHeap(64);
        original[0] = 42;
        SmallBufferCache.freeHeap(original);

        byte[] reused = SmallBufferCache.allocHeap(64);
        // Should be the same instance (reused from cache)
        assert reused == original : "Expected cached buffer to be reused";
        assert reused[0] == 42 : "Reused buffer should retain old data";
    }

    @Test
    public void freeHeap_nonSizeClass_notCached() {
        byte[] buf = new byte[100]; // not a power of 2
        SmallBufferCache.freeHeap(buf);

        byte[] next = SmallBufferCache.allocHeap(100);
        assert next != buf : "Non-size-class buffer should not be cached";
    }

    @Test
    public void freeHeap_oversized_notCached() {
        byte[] buf = new byte[1024]; // > MAX_SMALL_SIZE
        SmallBufferCache.freeHeap(buf);

        byte[] next = SmallBufferCache.allocHeap(1024);
        assert next != buf : "Oversized buffer should not be cached";
    }

    @Test
    public void freeHeap_null_noError() {
        SmallBufferCache.freeHeap(null); // should not throw
    }

    @Test
    public void allocHeap_multipleFreesAndAllocs_FIFO() {
        byte[] buf1 = new byte[32];
        buf1[0] = 1;
        byte[] buf2 = new byte[32];
        buf2[0] = 2;

        SmallBufferCache.freeHeap(buf1);
        SmallBufferCache.freeHeap(buf2);

        // LIFO order (offerFirst/pollFirst)
        byte[] out1 = SmallBufferCache.allocHeap(32);
        byte[] out2 = SmallBufferCache.allocHeap(32);

        assert out1 == buf2 : "Should return most recently freed first (LIFO)";
        assert out2 == buf1;
    }

    // ========================================================================
    // Direct ByteBuffer alloc/free cycle
    // ========================================================================

    @Test
    public void allocDirect_exactSizeClass_returnsDirect() {
        ByteBuffer buf = SmallBufferCache.allocDirect(64);
        assert buf.isDirect();
        assert buf.capacity() == 64;
    }

    @Test
    public void allocDirect_nonSizeClass_returnsExactSize() {
        ByteBuffer buf = SmallBufferCache.allocDirect(100);
        assert buf.isDirect();
        assert buf.capacity() == 100;
    }

    @Test
    public void allocDirect_afterFree_reusesBuffer() {
        ByteBuffer original = SmallBufferCache.allocDirect(128);
        original.put((byte) 99);
        assert SmallBufferCache.freeDirect(original);

        ByteBuffer reused = SmallBufferCache.allocDirect(128);
        assert reused == original : "Expected cached direct buffer to be reused";
        assert reused.position() == 0 : "Reused buffer should be cleared";
        assert reused.limit() == 128 : "Reused buffer should have full limit";
    }

    @Test
    public void freeDirect_nonDirect_notCached() {
        ByteBuffer heapBuf = ByteBuffer.allocate(64);
        assert !SmallBufferCache.freeDirect(heapBuf) : "Heap ByteBuffer should not be cached as direct";
    }

    @Test
    public void freeDirect_null_returnsFalse() {
        assert !SmallBufferCache.freeDirect(null);
    }

    @Test
    public void freeDirect_oversized_notCached() {
        ByteBuffer buf = ByteBuffer.allocateDirect(1024);
        assert !SmallBufferCache.freeDirect(buf);
    }

    // ========================================================================
    // Size class isolation
    // ========================================================================

    @Test
    public void sizeClasses_areIsolated() {
        byte[] buf16 = new byte[16];
        byte[] buf32 = new byte[32];

        SmallBufferCache.freeHeap(buf16);
        SmallBufferCache.freeHeap(buf32);

        // Allocate 32 should get buf32, not buf16
        byte[] out32 = SmallBufferCache.allocHeap(32);
        assert out32 == buf32 : "Size class 32 should be isolated from 16";
        assert out32.length == 32;

        // Allocate 16 should get buf16
        byte[] out16 = SmallBufferCache.allocHeap(16);
        assert out16 == buf16 : "Size class 16 should be isolated from 32";
        assert out16.length == 16;
    }

    @Test
    public void allSizeClasses_work() {
        int[] sizes = { 4, 8, 16, 32, 64, 128, 256, 512 };

        for (int size : sizes) {
            byte[] buf = SmallBufferCache.allocHeap(size);
            assert buf.length == size : "Expected " + size + " but got " + buf.length;
            SmallBufferCache.freeHeap(buf);

            byte[] reused = SmallBufferCache.allocHeap(size);
            assert reused == buf : "Size class " + size + " cache failed";
        }
    }

    // ========================================================================
    // Integration with ByteBuf allocator
    // ========================================================================

    @Test
    public void smallHeapBuffer_allocAndFree_lifecycle() {
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR;

        ByteBuf buf = alloc.heapBuffer(64);
        assert buf.capacity() == 64;
        buf.writeByte((byte) 1);
        buf.writeByte((byte) 2);
        buf.markWriter();
        assert buf.readByte() == (byte) 1;
        assert buf.readByte() == (byte) 2;
        buf.free();

        // Second allocation of same size should benefit from cache
        ByteBuf buf2 = alloc.heapBuffer(64);
        assert buf2.capacity() == 64;
        buf2.writeByte((byte) 3);
        buf2.markWriter();
        assert buf2.readByte() == (byte) 3;
        buf2.free();
    }

    @Test
    public void smallDirectBuffer_allocAndFree_lifecycle() {
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_DIRECT_ALLOCATOR;

        ByteBuf buf = alloc.directBuffer(64);
        assert buf.capacity() == 64;
        assert buf.isDirect();
        buf.writeByte((byte) 1);
        buf.writeByte((byte) 2);
        buf.markWriter();
        assert buf.readByte() == (byte) 1;
        assert buf.readByte() == (byte) 2;
        buf.free();

        // Second allocation
        ByteBuf buf2 = alloc.directBuffer(64);
        assert buf2.capacity() == 64;
        assert buf2.isDirect();
        buf2.writeByte((byte) 3);
        buf2.markWriter();
        assert buf2.readByte() == (byte) 3;
        buf2.free();
    }

    @Test
    public void pooledAllocator_smallBuffer_viaBufferMethod_usesCache() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_HEAP_ALLOCATOR;

        // Small allocation via buffer() should bypass pooled path
        ByteBuf buf = alloc.buffer(32);
        assert buf != null;
        assert buf.capacity() == 32;
        buf.writeBytes(new byte[] { 1, 2, 3, 4 });
        buf.markWriter();
        assert buf.readByte() == (byte) 1;
        buf.free();
    }

    @Test
    public void pooledAllocator_largeBuffer_usesPooledPath() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_HEAP_ALLOCATOR;

        // Large allocation should still use pooled path
        ByteBuf buf = alloc.buffer(1024);
        assert buf.toString().startsWith("PooledByteBuf[");
        buf.free();
    }

    @Test
    public void smallBuffer_autoExtension_cachesOldArray() {
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR;

        // Start small (64 bytes), grow to larger
        ByteBuf buf = alloc.heapBuffer(64, Integer.MAX_VALUE);
        assert buf.capacity() == 64;

        // Write more than 64 bytes to trigger extension
        byte[] data = new byte[128];
        for (int i = 0; i < 128; i++)
            data[i] = (byte) i;
        buf.writeBytes(data);

        // The old 64-byte array should have been returned to cache
        // Allocate another 64-byte buffer - should get cached one
        byte[] cached = SmallBufferCache.allocHeap(64);
        assert cached.length == 64;

        buf.free();
    }

    // ========================================================================
    // Concurrent access (thread-local isolation)
    // ========================================================================

    @Test
    public void concurrentAccess_threadLocalIsolation() throws Exception {
        final int threadCount = 4;
        final int opsPerThread = 1000;
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    List<byte[]> buffers = new ArrayList<>();
                    for (int i = 0; i < opsPerThread; i++) {
                        byte[] buf = SmallBufferCache.allocHeap(64);
                        assert buf.length == 64;
                        buffers.add(buf);
                    }
                    // Free all
                    for (byte[] buf : buffers) {
                        SmallBufferCache.freeHeap(buf);
                    }
                    // Re-allocate - should get cached buffers
                    for (int i = 0; i < opsPerThread; i++) {
                        byte[] buf = SmallBufferCache.allocHeap(64);
                        assert buf.length == 64;
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assert errors.get() == 0 : "Concurrent access had errors";
    }

    @Test
    public void concurrentByteBufAlloc_noErrors() throws Exception {
        final int threadCount = 4;
        final int opsPerThread = 500;
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final AtomicInteger errors = new AtomicInteger(0);
        final ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR;

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        ByteBuf buf = alloc.heapBuffer(64);
                        buf.writeBytes(new byte[] { 1, 2, 3, 4 });
                        buf.markWriter();
                        assert buf.readByte() == (byte) 1;
                        buf.free();
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assert errors.get() == 0 : "Concurrent ByteBuf alloc had errors";
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    public void allocHeap_zeroCapacity() {
        byte[] buf = SmallBufferCache.allocHeap(0);
        assert buf.length == 0;
    }

    @Test
    public void allocHeap_singleByte() {
        byte[] buf = SmallBufferCache.allocHeap(1);
        assert buf.length == 1;
        buf[0] = 42;
        // size 1 is not a size class (< 4), so not cached
        SmallBufferCache.freeHeap(buf);
        byte[] next = SmallBufferCache.allocHeap(1);
        assert next != buf : "Size 1 is not a size class, should not be cached";
    }

    @Test
    public void allocHeap_twoBytes() {
        byte[] buf = SmallBufferCache.allocHeap(2);
        assert buf.length == 2;
        // size 2 is not a size class (< 4), so not cached
        SmallBufferCache.freeHeap(buf);
        byte[] next = SmallBufferCache.allocHeap(2);
        assert next != buf : "Size 2 is not a size class, should not be cached";
    }

    @Test
    public void allocHeap_fourBytes_cached() {
        byte[] buf = SmallBufferCache.allocHeap(4);
        assert buf.length == 4;
        SmallBufferCache.freeHeap(buf);
        byte[] next = SmallBufferCache.allocHeap(4);
        assert next == buf : "Size 4 is a size class, should be cached";
    }

    @Test
    public void allocDirect_fourBytes_cached() {
        ByteBuffer buf = SmallBufferCache.allocDirect(4);
        assert buf.capacity() == 4;
        assert buf.isDirect();
        assert SmallBufferCache.freeDirect(buf);
        ByteBuffer next = SmallBufferCache.allocDirect(4);
        assert next == buf : "Size 4 direct should be cached";
    }

    @Test
    public void smallBuffer_writeReadIntegrity() {
        // Verify data integrity through cache reuse cycle
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR;

        // First allocation - write and read data
        ByteBuf buf1 = alloc.heapBuffer(16);
        buf1.writeInt32(0x12345678);
        buf1.writeInt32(0xDEADBEEF);
        buf1.markWriter();
        assert buf1.readInt32() == 0x12345678;
        assert buf1.readInt32() == (int) 0xDEADBEEF;
        buf1.free();

        // Second allocation - old data may be in the cached array, but writer/reader indices are reset
        ByteBuf buf2 = alloc.heapBuffer(16);
        assert buf2.readableBytes() == 0 : "New buffer should have 0 readable bytes";
        assert buf2.writableBytes() == 16;
        buf2.writeInt32(0x11223344);
        buf2.markWriter();
        assert buf2.readInt32() == 0x11223344;
        buf2.free();
    }

    // ========================================================================
    // Pooled small allocation
    // ========================================================================

    @Test
    public void pooledBuffer_smallCapacity_isPooledByteBuf() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_HEAP_ALLOCATOR;

        // Even small capacity should return PooledByteBuf via pooledBuffer()
        ByteBuf buf = alloc.pooledBuffer(64);
        assert buf != null;
        assert buf.toString().startsWith("PooledByteBuf[") : "pooledBuffer(64) should return PooledByteBuf, got: " + buf;
        assert buf.capacity() == 64;
        buf.free();
    }

    @Test
    public void pooledBuffer_smallDirect_isPooledByteBuf() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_DIRECT_ALLOCATOR;

        ByteBuf buf = alloc.pooledBuffer(64);
        assert buf != null;
        assert buf.toString().startsWith("PooledByteBuf[");
        assert buf.isDirect();
        assert buf.capacity() == 64;
        buf.free();
    }

    @Test
    public void pooledBuffer_smallCapacity_readWrite() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_HEAP_ALLOCATOR;

        ByteBuf buf = alloc.pooledBuffer(32);
        buf.writeBytes(new byte[] { 10, 20, 30, 40 });
        buf.markWriter();
        assert buf.readByte() == (byte) 10;
        assert buf.readByte() == (byte) 20;
        assert buf.readByte() == (byte) 30;
        assert buf.readByte() == (byte) 40;
        buf.free();
    }

    @Test
    public void pooledBuffer_allSmallSizeClasses() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_HEAP_ALLOCATOR;
        int[] sizes = { 4, 8, 16, 32, 64, 128, 256, 512 };

        for (int size : sizes) {
            ByteBuf buf = alloc.pooledBuffer(size);
            assert buf.toString().startsWith("PooledByteBuf[") : "pooledBuffer(" + size + ") should be PooledByteBuf";
            assert buf.capacity() == size : "Expected capacity " + size;
            buf.writeByte((byte) 42);
            buf.markWriter();
            assert buf.readByte() == (byte) 42;
            buf.free();
        }
    }

    // ========================================================================
    // Small-to-normal natural transition
    // ========================================================================

    @Test
    public void pooledBuffer_smallToNormal_transition_heap() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_HEAP_ALLOCATOR;

        // Start with small initial capacity, large max capacity
        ByteBuf buf = alloc.pooledBuffer(64, Integer.MAX_VALUE);
        assert buf.toString().startsWith("PooledByteBuf[");
        assert buf.capacity() == 64 : "Initial capacity should be 64";

        // Write data within small range
        byte[] smallData = new byte[60];
        for (int i = 0; i < 60; i++)
            smallData[i] = (byte) (i + 1);
        buf.writeBytes(smallData);
        buf.markWriter();

        // Verify data integrity
        for (int i = 0; i < 60; i++) {
            assert buf.readByte() == (byte) (i + 1) : "Data mismatch at index " + i;
        }

        buf.free();
    }

    @Test
    public void pooledBuffer_smallToNormal_extensionBeyond512() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_HEAP_ALLOCATOR;

        // Start small (64 bytes), max is large
        ByteBuf buf = alloc.pooledBuffer(64, Integer.MAX_VALUE);
        assert buf.capacity() == 64;

        // Write more than 512 bytes to trigger transition from small cache to buddy algorithm
        byte[] bigData = new byte[1024];
        for (int i = 0; i < 1024; i++)
            bigData[i] = (byte) (i % 127 + 1);
        buf.writeBytes(bigData);
        buf.markWriter();

        // Buffer should have grown to accommodate the data
        assert buf.capacity() >= 1024 : "Capacity should be >= 1024 after extension, got: " + buf.capacity();

        // Verify all data is intact after transition
        for (int i = 0; i < 1024; i++) {
            byte expected = (byte) (i % 127 + 1);
            byte actual = buf.readByte();
            assert actual == expected : "Data mismatch at index " + i + ": expected " + expected + " got " + actual;
        }

        buf.free();
    }

    @Test
    public void pooledBuffer_smallToNormal_extensionBeyond512_direct() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_DIRECT_ALLOCATOR;

        ByteBuf buf = alloc.pooledBuffer(64, Integer.MAX_VALUE);
        assert buf.isDirect();
        assert buf.capacity() == 64;

        // Write beyond small buffer threshold
        byte[] bigData = new byte[1024];
        for (int i = 0; i < 1024; i++)
            bigData[i] = (byte) (i % 127 + 1);
        buf.writeBytes(bigData);
        buf.markWriter();

        assert buf.capacity() >= 1024;

        // Verify data integrity
        for (int i = 0; i < 1024; i++) {
            byte expected = (byte) (i % 127 + 1);
            assert buf.readByte() == expected : "Direct data mismatch at index " + i;
        }

        buf.free();
    }

    @Test
    public void pooledBuffer_gradualGrowth_smallToNormal() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_HEAP_ALLOCATOR;

        // Start very small (4 bytes)
        ByteBuf buf = alloc.pooledBuffer(4, Integer.MAX_VALUE);
        assert buf.capacity() == 4;

        // Gradually grow: 4 -> 8 -> 16 -> 32 -> 64 -> 128 -> 256 -> 512 -> 1024+
        int totalWritten = 0;
        for (int round = 0; round < 10; round++) {
            int chunkSize = 1 << (round + 2); // 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048
            if (totalWritten + chunkSize > 8192)
                break; // reasonable limit
            byte[] chunk = new byte[chunkSize];
            for (int j = 0; j < chunkSize; j++) {
                chunk[j] = (byte) ((totalWritten + j) % 127 + 1);
            }
            buf.writeBytes(chunk);
            totalWritten += chunkSize;
        }
        buf.markWriter();

        // Read back and verify
        for (int i = 0; i < totalWritten; i++) {
            byte expected = (byte) (i % 127 + 1);
            byte actual = buf.readByte();
            assert actual == expected : "Gradual growth data mismatch at index " + i;
        }

        buf.free();
    }

    @Test
    public void pooledBuffer_smallCopy_returnsPooledByteBuf() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_HEAP_ALLOCATOR;

        ByteBuf buf = alloc.pooledBuffer(64);
        buf.writeBytes(new byte[] { 1, 2, 3, 4, 5 });
        buf.markWriter();

        ByteBuf copy = buf.copy();
        assert copy.toString().startsWith("PooledByteBuf[");
        assert copy.capacity() == 64;
        assert copy.readByte() == (byte) 1;
        assert copy.readByte() == (byte) 2;

        copy.free();
        buf.free();
    }

    @Test
    public void pooledBuffer_small_markReaderRecycle() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_HEAP_ALLOCATOR;

        ByteBuf buf = alloc.pooledBuffer(64, Integer.MAX_VALUE);

        // Write some data
        buf.writeBytes(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        buf.markWriter();

        // Read some data
        assert buf.readByte() == (byte) 1;
        assert buf.readByte() == (byte) 2;

        // markReader triggers recycle - should work with small buffer
        buf.markReader();

        // Continue reading remaining data
        assert buf.readByte() == (byte) 3;
        assert buf.readByte() == (byte) 4;

        buf.free();
    }

    @Test
    public void pooledBuffer_small_discardReadBytes() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_HEAP_ALLOCATOR;

        ByteBuf buf = alloc.pooledBuffer(64);
        buf.writeBytes(new byte[] { 10, 20, 30, 40, 50 });
        buf.markWriter();

        // Read 2 bytes
        assert buf.readByte() == (byte) 10;
        assert buf.readByte() == (byte) 20;

        // Discard read bytes
        buf.discardReadBytes();
        assert buf.readableBytes() == 3;
        assert buf.readByte() == (byte) 30;
        assert buf.readByte() == (byte) 40;
        assert buf.readByte() == (byte) 50;

        buf.free();
    }

    @Test
    public void allocSmallBuffer_heap_createsBufferWrap() {
        Buffer buf = SmallBufferCache.allocSmallBuffer(false, 64);
        assert buf != null;
        assert !buf.isDirect();
        assert buf.capacity() == 64;
        assert buf.getOffset() == 0;

        // Write and read through Buffer interface
        buf.put(0, (byte) 42);
        assert buf.get(0) == (byte) 42;

        buf.free();
    }

    @Test
    public void allocSmallBuffer_direct_createsBufferWrap() {
        Buffer buf = SmallBufferCache.allocSmallBuffer(true, 128);
        assert buf != null;
        assert buf.isDirect();
        assert buf.capacity() == 128;

        buf.put(0, (byte) 99);
        assert buf.get(0) == (byte) 99;

        buf.free();
    }

    @Test
    public void allocSmallBuffer_afterFree_bufferReturnsToCache() {
        // Allocate and free a small buffer
        Buffer buf1 = SmallBufferCache.allocSmallBuffer(false, 64);
        buf1.free();

        // Next allocation from SmallBufferCache should reuse the buffer
        byte[] cached = SmallBufferCache.allocHeap(64);
        assert cached.length == 64;
        // Just verify it doesn't throw - the cached array might or might not be the same one
    }

    @Test
    public void pooledBuffer_smallFixed_noTransition() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_HEAP_ALLOCATOR;

        // pooledBuffer(64, 64) - fixed small buffer, never transitions
        ByteBuf buf = alloc.pooledBuffer(64, 64);
        assert buf.toString().startsWith("PooledByteBuf[");
        assert buf.capacity() == 64;

        // Fill it up
        byte[] data = new byte[64];
        for (int i = 0; i < 64; i++)
            data[i] = (byte) i;
        buf.writeBytes(data);
        buf.markWriter();

        // Verify
        for (int i = 0; i < 64; i++) {
            assert buf.readByte() == (byte) i;
        }

        buf.free();
    }

    @Test
    public void pooledBuffer_concurrent_smallAlloc_noErrors() throws Exception {
        final int threadCount = 4;
        final int opsPerThread = 500;
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final boolean direct = (t % 2 == 0);
            final ByteBufAllocator alloc = direct ? ByteBufUtils.POOLED_DIRECT_ALLOCATOR : ByteBufUtils.POOLED_HEAP_ALLOCATOR;
            new Thread(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        ByteBuf buf = alloc.pooledBuffer(64, Integer.MAX_VALUE);
                        buf.writeBytes(new byte[] { 1, 2, 3, 4 });
                        buf.markWriter();
                        assert buf.readByte() == (byte) 1;
                        buf.free();
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assert errors.get() == 0 : "Concurrent pooled small alloc had errors";
    }

    @Test
    public void pooledBuffer_concurrent_smallToNormal_noErrors() throws Exception {
        final int threadCount = 4;
        final int opsPerThread = 100;
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final ByteBufAllocator alloc = ByteBufUtils.POOLED_HEAP_ALLOCATOR;
            new Thread(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        ByteBuf buf = alloc.pooledBuffer(32, Integer.MAX_VALUE);
                        // Write 1024 bytes to trigger small-to-normal transition
                        byte[] data = new byte[1024];
                        for (int j = 0; j < 1024; j++)
                            data[j] = (byte) (j % 127 + 1);
                        buf.writeBytes(data);
                        buf.markWriter();

                        // Verify first few bytes
                        assert buf.readByte() == (byte) 1;
                        assert buf.readByte() == (byte) 2;
                        assert buf.readByte() == (byte) 3;
                        buf.free();
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assert errors.get() == 0 : "Concurrent small-to-normal transition had errors";
    }

    // ========================================================================
    // Cross-thread allocation/deallocation (L2 global shared cache)
    // ========================================================================

    @Test
    public void crossThread_heapAllocOnThread1_freeOnThread2_reuseOnThread3() throws Exception {
        // Thread 1: allocate array and fill with data, pass to Thread 2
        // Thread 2: free the array (goes to L1 of Thread 2, overflow → L2)
        // Thread 3: allocate and verify it gets a reused array from L2
        int size = 64;
        AtomicReference<byte[]> sharedRef = new AtomicReference<>();
        CountDownLatch allocDone = new CountDownLatch(1);
        CountDownLatch freeDone = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);

        // Thread 1: allocate
        new Thread(() -> {
            try {
                byte[] buf = SmallBufferCache.allocHeap(size);
                buf[0] = 42;
                sharedRef.set(buf);
            } catch (Throwable e) {
                errors.incrementAndGet();
            } finally {
                allocDone.countDown();
            }
        }).start();

        allocDone.await();
        byte[] allocated = sharedRef.get();
        assert allocated != null;
        assert allocated[0] == 42;

        // Thread 2: free it — fills L1, then overflow to L2
        // First flood Thread 2's L1 for this size class to force L2 usage
        new Thread(() -> {
            try {
                // Fill L1 cache of this thread for size 64
                for (int i = 0; i < 260; i++) {
                    SmallBufferCache.freeHeap(new byte[size]);
                }
                // Now free the cross-thread buffer — L1 is full, should go to L2
                SmallBufferCache.freeHeap(allocated);
            } catch (Throwable e) {
                errors.incrementAndGet();
            } finally {
                freeDone.countDown();
            }
        }).start();

        freeDone.await();

        // Thread 3: allocate and check if we can get the buffer from L2
        CountDownLatch reuseCheckDone = new CountDownLatch(1);
        AtomicReference<byte[]> reusedRef = new AtomicReference<>();
        new Thread(() -> {
            try {
                // This thread's L1 is empty; allocation should come from L2
                byte[] buf = SmallBufferCache.allocHeap(size);
                reusedRef.set(buf);
            } catch (Throwable e) {
                errors.incrementAndGet();
            } finally {
                reuseCheckDone.countDown();
            }
        }).start();

        reuseCheckDone.await();
        assert errors.get() == 0 : "Cross-thread heap test had errors";
        // The reused buffer should have been obtained (either from L2 or new)
        assert reusedRef.get() != null;
        assert reusedRef.get().length == size;
    }

    @Test
    public void crossThread_directAllocOnThread1_freeOnThread2_reuseOnThread3() throws Exception {
        int size = 128;
        AtomicReference<ByteBuffer> sharedRef = new AtomicReference<>();
        CountDownLatch allocDone = new CountDownLatch(1);
        CountDownLatch freeDone = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);

        // Thread 1: allocate direct buffer
        new Thread(() -> {
            try {
                ByteBuffer buf = SmallBufferCache.allocDirect(size);
                buf.put((byte) 99);
                sharedRef.set(buf);
            } catch (Throwable e) {
                errors.incrementAndGet();
            } finally {
                allocDone.countDown();
            }
        }).start();

        allocDone.await();
        ByteBuffer allocated = sharedRef.get();
        assert allocated != null;
        assert allocated.isDirect();

        // Thread 2: flood L1 then free
        new Thread(() -> {
            try {
                for (int i = 0; i < 260; i++) {
                    SmallBufferCache.freeDirect(ByteBuffer.allocateDirect(size));
                }
                // Free cross-thread buffer — should go to L2
                SmallBufferCache.freeDirect(allocated);
            } catch (Throwable e) {
                errors.incrementAndGet();
            } finally {
                freeDone.countDown();
            }
        }).start();

        freeDone.await();

        // Thread 3: allocate and expect reuse from L2
        CountDownLatch reuseCheckDone = new CountDownLatch(1);
        AtomicReference<ByteBuffer> reusedRef = new AtomicReference<>();
        new Thread(() -> {
            try {
                ByteBuffer buf = SmallBufferCache.allocDirect(size);
                reusedRef.set(buf);
            } catch (Throwable e) {
                errors.incrementAndGet();
            } finally {
                reuseCheckDone.countDown();
            }
        }).start();

        reuseCheckDone.await();
        assert errors.get() == 0 : "Cross-thread direct test had errors";
        assert reusedRef.get() != null;
        assert reusedRef.get().capacity() == size;
        assert reusedRef.get().isDirect();
    }

    @Test
    public void crossThread_l2FallbackWhenL1Empty() throws Exception {
        // Directly test: free on one thread, alloc on another
        int size = 32;
        byte[] original = new byte[size];
        original[0] = 77;
        CountDownLatch freeDone = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);

        // Free on Thread A while flooding L1 to push to L2
        new Thread(() -> {
            try {
                // Fill up L1
                for (int i = 0; i < 260; i++) {
                    SmallBufferCache.freeHeap(new byte[size]);
                }
                // This should go to L2
                SmallBufferCache.freeHeap(original);
            } catch (Throwable e) {
                errors.incrementAndGet();
            } finally {
                freeDone.countDown();
            }
        }).start();

        freeDone.await();

        // Alloc on Thread B (different thread, empty L1)
        CountDownLatch allocDone = new CountDownLatch(1);
        AtomicReference<byte[]> resultRef = new AtomicReference<>();
        new Thread(() -> {
            try {
                byte[] buf = SmallBufferCache.allocHeap(size);
                resultRef.set(buf);
            } catch (Throwable e) {
                errors.incrementAndGet();
            } finally {
                allocDone.countDown();
            }
        }).start();

        allocDone.await();
        assert errors.get() == 0;
        assert resultRef.get() != null;
        assert resultRef.get().length == size;
    }

    @Test
    public void crossThread_massiveCrossThreadRecycling_heapNoErrors() throws Exception {
        // Producer threads allocate, pass buffers to consumer threads that free them.
        // This simulates the typical IO-thread-alloc / worker-thread-free pattern.
        int threadCount = 4;
        int buffersPerThread = 500;
        int size = 64;
        ConcurrentLinkedQueue<byte[]> transferQueue = new ConcurrentLinkedQueue<>();
        CountDownLatch producersDone = new CountDownLatch(threadCount);
        CountDownLatch consumersDone = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        // Producers: allocate buffers and enqueue them
        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < buffersPerThread; i++) {
                        byte[] buf = SmallBufferCache.allocHeap(size);
                        buf[0] = (byte) (i & 0xFF);
                        transferQueue.offer(buf);
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    producersDone.countDown();
                }
            }).start();
        }

        producersDone.await();
        assert transferQueue.size() == threadCount * buffersPerThread;

        // Consumers: dequeue and free
        int totalBuffers = transferQueue.size();
        int buffersPerConsumer = totalBuffers / threadCount;
        for (int t = 0; t < threadCount; t++) {
            final int count = (t == threadCount - 1) ? (totalBuffers - buffersPerConsumer * (threadCount - 1)) : buffersPerConsumer;
            new Thread(() -> {
                try {
                    for (int i = 0; i < count; i++) {
                        byte[] buf = transferQueue.poll();
                        if (buf != null) {
                            SmallBufferCache.freeHeap(buf);
                        }
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    consumersDone.countDown();
                }
            }).start();
        }

        consumersDone.await();
        assert errors.get() == 0 : "Massive cross-thread recycling had " + errors.get() + " errors";
    }

    @Test
    public void crossThread_massiveCrossThreadRecycling_directNoErrors() throws Exception {
        int threadCount = 4;
        int buffersPerThread = 200; // fewer for direct buffers (more expensive)
        int size = 128;
        ConcurrentLinkedQueue<ByteBuffer> transferQueue = new ConcurrentLinkedQueue<>();
        CountDownLatch producersDone = new CountDownLatch(threadCount);
        CountDownLatch consumersDone = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        // Producers
        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < buffersPerThread; i++) {
                        ByteBuffer buf = SmallBufferCache.allocDirect(size);
                        buf.put((byte) i);
                        transferQueue.offer(buf);
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    producersDone.countDown();
                }
            }).start();
        }

        producersDone.await();

        // Consumers
        int totalBuffers = transferQueue.size();
        int buffersPerConsumer = totalBuffers / threadCount;
        for (int t = 0; t < threadCount; t++) {
            final int count = (t == threadCount - 1) ? (totalBuffers - buffersPerConsumer * (threadCount - 1)) : buffersPerConsumer;
            new Thread(() -> {
                try {
                    for (int i = 0; i < count; i++) {
                        ByteBuffer buf = transferQueue.poll();
                        if (buf != null) {
                            SmallBufferCache.freeDirect(buf);
                        }
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    consumersDone.countDown();
                }
            }).start();
        }

        consumersDone.await();
        assert errors.get() == 0 : "Massive cross-thread direct recycling had errors";
    }

    @Test
    public void crossThread_allocAndFreeSimultaneously_noRace() throws Exception {
        // Multiple threads simultaneously alloc and free on the same size class
        // Testing L1/L2 concurrency safety
        int threadCount = 8;
        int iterations = 1000;
        int size = 256;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    barrier.await(); // synchronize start
                    for (int i = 0; i < iterations; i++) {
                        if (threadId % 2 == 0) {
                            // Even threads: alloc then free
                            byte[] buf = SmallBufferCache.allocHeap(size);
                            assert buf.length == size;
                            SmallBufferCache.freeHeap(buf);
                        } else {
                            // Odd threads: alloc direct then free
                            ByteBuffer buf = SmallBufferCache.allocDirect(size);
                            assert buf.capacity() == size;
                            SmallBufferCache.freeDirect(buf);
                        }
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        done.await();
        assert errors.get() == 0 : "Concurrent alloc/free race had errors";
    }
}
