package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;
import org.junit.Test;

/**
 * Tests for {@link BasicByteBufAllocator} covering all allocation methods,
 * parameter validation, pooled vs unpooled, heap vs direct.
 */
public class BasicByteBufAllocatorTest {

    // ========================================================================
    // Default allocator
    // ========================================================================

    @Test
    public void defaultAllocator_notNull() {
        assert ByteBufAllocator.DEFAULT != null;
    }

    @Test
    public void defaultBuffer_hasDefaultCapacity() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        assert buf != null;
        assert buf.capacity() > 0 : "default buffer should have positive capacity";
        buf.free();
    }

    @Test
    public void buffer_withCapacity() {
        ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(128);
        assert buf.capacity() == 128;
        buf.free();
    }

    @Test
    public void buffer_withZeroCapacity_returnsEmpty() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(0);
        assert buf == ByteBuf.EMPTY : "buffer(0) should return EMPTY";
    }

    @Test
    public void buffer_initCapacity_exceedsMaxCapacity_throwsIAE() {
        try {
            ByteBufAllocator.DEFAULT.buffer(100, 50);
            assert false : "should throw IllegalArgumentException";
        } catch (IllegalArgumentException e) {
            assert e.getMessage().contains("initCapacity");
        }
    }

    // ========================================================================
    // Heap buffer allocation
    // ========================================================================

    @Test
    public void heapBuffer_default() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        assert buf != null;
        assert !buf.isDirect();
        buf.free();
    }

    @Test
    public void heapBuffer_withCapacity() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(256);
        assert buf.capacity() == 256;
        assert !buf.isDirect();
        buf.free();
    }

    @Test
    public void heapBuffer_withInitAndMaxCapacity() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64, 256);
        assert buf.capacity() == 64;
        assert !buf.isDirect();
        buf.free();
    }

    @Test
    public void heapBuffer_isAutoArrayByteBuf() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        assert buf.toString().startsWith("AutoArrayByteBuf[") : "heap buffer should be AutoArrayByteBuf";
        buf.free();
    }

    // ========================================================================
    // Direct buffer allocation
    // ========================================================================

    @Test
    public void directBuffer_default() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer();
        assert buf != null;
        assert buf.isDirect();
        buf.free();
    }

    @Test
    public void directBuffer_withCapacity() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer(128);
        assert buf.capacity() == 128;
        assert buf.isDirect();
        buf.free();
    }

    @Test
    public void directBuffer_withInitAndMaxCapacity() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer(32, 128);
        assert buf.capacity() == 32;
        assert buf.isDirect();
        buf.free();
    }

    @Test
    public void directBuffer_isAutoByteBuffer() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer(64);
        assert buf.toString().startsWith("AutoByteBuffer[") : "direct buffer should be AutoByteBuffer";
        buf.free();
    }

    // ========================================================================
    // Ring buffer allocation
    // ========================================================================

    @Test
    public void ringHeapBuffer_isRingArrayByteBuf() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.ringHeapBuffer(64);
        assert buf.toString().startsWith("RingArrayByteBuf[");
        assert !buf.isDirect();
        assert buf.capacity() == 64;
        buf.free();
    }

    @Test
    public void ringDirectBuffer_isRingByteBuffer() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.ringDirectBuffer(64);
        assert buf.toString().startsWith("RingByteBuffer[");
        assert buf.isDirect();
        assert buf.capacity() == 64;
        buf.free();
    }

    @Test
    public void ringBuffer_dependsOnAllocator() {
        // DEFAULT ring depends on allocator isDirect()
        ByteBuf buf = ByteBufAllocator.DEFAULT.ringBuffer(64);
        assert buf.capacity() == 64;
        buf.free();
    }

    // ========================================================================
    // Pooled buffer allocation
    // ========================================================================

    @Test
    public void pooledBuffer_default() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.pooledBuffer();
        assert buf != null;
        buf.free();
    }

    @Test
    public void pooledBuffer_withCapacity() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.pooledBuffer(64);
        assert buf != null;
        assert buf.toString().startsWith("PooledByteBuf[") : "pooled buffer should be PooledByteBuf, got: " + buf;
        buf.free();
    }

    @Test
    public void pooledBuffer_withInitAndMaxCapacity() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.pooledBuffer(32, 128);
        assert buf != null;
        buf.free();
    }

    // ========================================================================
    // Named allocators
    // ========================================================================

    @Test
    public void unpooledHeapAllocator() {
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR;
        assert !alloc.isDirect();

        ByteBuf buf = alloc.buffer(64);
        assert !buf.isDirect();
        buf.free();
    }

    @Test
    public void unpooledDirectAllocator() {
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_DIRECT_ALLOCATOR;
        assert alloc.isDirect();

        ByteBuf buf = alloc.buffer(64);
        assert buf.isDirect();
        buf.free();
    }

    @Test
    public void pooledHeapAllocator() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_HEAP_ALLOCATOR;
        assert !alloc.isDirect();

        // Small allocations via buffer() use SmallBufferCache for efficiency
        ByteBuf smallBuf = alloc.buffer(64);
        assert smallBuf != null;
        assert !smallBuf.isDirect();
        smallBuf.free();

        // Large allocations use pooled path
        ByteBuf largeBuf = alloc.buffer(1024);
        assert largeBuf.toString().startsWith("PooledByteBuf[");
        largeBuf.free();
    }

    @Test
    public void pooledDirectAllocator() {
        ByteBufAllocator alloc = ByteBufUtils.POOLED_DIRECT_ALLOCATOR;
        assert alloc.isDirect();

        // Small allocations via buffer() use SmallBufferCache for efficiency
        ByteBuf smallBuf = alloc.buffer(64);
        assert smallBuf != null;
        assert smallBuf.isDirect();
        smallBuf.free();

        // Large allocations use pooled path
        ByteBuf largeBuf = alloc.buffer(1024);
        assert largeBuf.toString().startsWith("PooledByteBuf[");
        largeBuf.free();
    }

    // ========================================================================
    // jvmBuffer allocation
    // ========================================================================

    @Test
    public void heapAllocator_jvmBuffer_returnsHeapByteBuffer() {
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR;
        ByteBuffer jvmBuf = alloc.jvmBuffer(64);
        assert jvmBuf != null;
        assert !jvmBuf.isDirect();
        assert jvmBuf.capacity() == 64;
    }

    @Test
    public void directAllocator_jvmBuffer_returnsDirectByteBuffer() {
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_DIRECT_ALLOCATOR;
        ByteBuffer jvmBuf = alloc.jvmBuffer(64);
        assert jvmBuf != null;
        assert jvmBuf.isDirect();
        assert jvmBuf.capacity() == 64;
    }

    // ========================================================================
    // Cross-allocator consistency
    // ========================================================================

    @Test
    public void heapBuffer_fromDirectAllocator_isStillHeap() {
        // heapBuffer() always uses UNPOOLED_HEAP_ALLOCATOR regardless of allocator type
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_DIRECT_ALLOCATOR;
        ByteBuf buf = alloc.heapBuffer(64);
        assert !buf.isDirect() : "heapBuffer should always be heap";
        buf.free();
    }

    @Test
    public void directBuffer_fromHeapAllocator_isStillDirect() {
        // directBuffer() always uses UNPOOLED_DIRECT_ALLOCATOR
        ByteBufAllocator alloc = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR;
        ByteBuf buf = alloc.directBuffer(64);
        assert buf.isDirect() : "directBuffer should always be direct";
        buf.free();
    }

    // ========================================================================
    // AutoArrayByteBuf auto-expansion
    // ========================================================================

    @Test
    public void heapBuffer_autoExpand_withinMaxCapacity() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(4, 64);
        assert buf.capacity() == 4;

        // Write more than initial capacity
        buf.writeBytes(new byte[] { 1, 2, 3, 4 });
        buf.writeBytes(new byte[] { 5, 6, 7, 8 }); // triggers expansion
        buf.markWriter();

        assert buf.readByte() == 1;
        assert buf.readByte() == 2;
        assert buf.readByte() == 3;
        assert buf.readByte() == 4;
        assert buf.readByte() == 5;
        assert buf.readByte() == 6;
        assert buf.readByte() == 7;
        assert buf.readByte() == 8;
        buf.free();
    }

    // ========================================================================
    // AutoByteBuffer auto-expansion (direct)
    // ========================================================================

    @Test
    public void directBuffer_autoExpand_withinMaxCapacity() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer(4, 64);
        assert buf.capacity() == 4;

        buf.writeBytes(new byte[] { 1, 2, 3, 4 });
        buf.writeBytes(new byte[] { 5, 6, 7, 8 }); // triggers expansion
        buf.markWriter();

        assert buf.readByte() == 1;
        assert buf.readByte() == 2;
        // just verify it didn't crash — internal expansion works
        buf.free();
    }

    // ========================================================================
    // Concurrent allocator usage
    // ========================================================================

    @Test
    public void concurrent_allocateFromDifferentAllocators() throws Exception {
        final int threadCount = 4;
        final int opsPerThread = 200;
        final java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threadCount);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
        final java.util.concurrent.atomic.AtomicInteger errorCount = new java.util.concurrent.atomic.AtomicInteger(0);

        ByteBufAllocator[] allocators = { ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR, ByteBufUtils.UNPOOLED_DIRECT_ALLOCATOR, ByteBufUtils.POOLED_HEAP_ALLOCATOR, ByteBufUtils.POOLED_DIRECT_ALLOCATOR, };

        for (int t = 0; t < threadCount; t++) {
            final ByteBufAllocator alloc = allocators[t % allocators.length];
            new Thread(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        ByteBuf buf = alloc.buffer(32);
                        buf.writeBytes(new byte[] { 1, 2, 3, 4 });
                        buf.markWriter();
                        assert buf.readByte() == 1;
                        buf.free();
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
        assert errorCount.get() == 0 : "concurrent allocation had errors";
    }
}
