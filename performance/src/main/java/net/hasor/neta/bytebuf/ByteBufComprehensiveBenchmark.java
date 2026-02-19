/*
 * Comprehensive ByteBuf Benchmark: Neta vs Netty
 *
 * API-dimension comparison across all buffer types:
 *   - Pooled Heap vs Pooled Heap
 *   - Pooled Direct vs Pooled Direct
 *   - Unpooled Heap vs Unpooled Heap (array)
 *   - Unpooled Direct vs Unpooled Direct
 *   - Neta Ring (special) vs Netty Pooled (closest equivalent)
 *
 * Each scenario tests: alloc+free, alloc+write+read+free, multi-thread contention.
 * Run with -prof gc to capture GC metrics (gc.alloc.rate, gc.alloc.rate.norm).
 *
 * Metrics coverage:
 *   - CPU: ops/us throughput
 *   - Memory: gc.alloc.rate.norm (bytes/op via -prof gc)
 *   - GC pressure: gc.alloc.rate, gc.count, gc.time
 *   - Scalability: single-thread vs 8-thread vs 32-thread
 */
package net.hasor.neta.bytebuf;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@Fork(value = 1, jvmArgsAppend = { "-Xms512m", "-Xmx512m", "-XX:+UseG1GC" })
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
public class ByteBufComprehensiveBenchmark {

    private static final int SIZE_256 = 256;
    private static final int SIZE_4K  = 4096;

    private byte[] payload256;
    private byte[] payload4k;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()//
                .include(ByteBufComprehensiveBenchmark.class.getSimpleName())//
                .addProfiler("gc")//
                .resultFormat(ResultFormatType.TEXT)//
                .result("benchmark-comprehensive-results.txt")//
                .build();
        new Runner(opt).run();
    }

    @Setup(Level.Trial)
    public void setup() {
        payload256 = new byte[SIZE_256];
        payload4k = new byte[SIZE_4K];
        Random rng = new Random(42);
        rng.nextBytes(payload256);
        rng.nextBytes(payload4k);
    }

    // ====================================================================
    // SECTION 1: Pooled Heap — alloc + free
    // ====================================================================

    @Benchmark
    @Threads(1)
    public int pooledHeap_allocFree_1t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.pooledBuffer(SIZE_256);
        int c = buf.capacity();
        buf.free();
        return c;
    }

    @Benchmark
    @Threads(1)
    public int pooledHeap_allocFree_1t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(SIZE_256);
        int c = buf.capacity();
        buf.release();
        return c;
    }

    @Benchmark
    @Threads(8)
    public int pooledHeap_allocFree_8t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.pooledBuffer(SIZE_256);
        int c = buf.capacity();
        buf.free();
        return c;
    }

    @Benchmark
    @Threads(8)
    public int pooledHeap_allocFree_8t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(SIZE_256);
        int c = buf.capacity();
        buf.release();
        return c;
    }

    @Benchmark
    @Threads(32)
    public int pooledHeap_allocFree_32t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.pooledBuffer(SIZE_256);
        int c = buf.capacity();
        buf.free();
        return c;
    }

    @Benchmark
    @Threads(32)
    public int pooledHeap_allocFree_32t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(SIZE_256);
        int c = buf.capacity();
        buf.release();
        return c;
    }

    // ====================================================================
    // SECTION 2: Pooled Direct — alloc + free
    // ====================================================================

    @Benchmark
    @Threads(1)
    public int pooledDirect_allocFree_1t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_DIRECT_ALLOCATOR.pooledBuffer(SIZE_256);
        int c = buf.capacity();
        buf.free();
        return c;
    }

    @Benchmark
    @Threads(1)
    public int pooledDirect_allocFree_1t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(SIZE_256);
        int c = buf.capacity();
        buf.release();
        return c;
    }

    @Benchmark
    @Threads(8)
    public int pooledDirect_allocFree_8t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_DIRECT_ALLOCATOR.pooledBuffer(SIZE_256);
        int c = buf.capacity();
        buf.free();
        return c;
    }

    @Benchmark
    @Threads(8)
    public int pooledDirect_allocFree_8t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(SIZE_256);
        int c = buf.capacity();
        buf.release();
        return c;
    }

    // ====================================================================
    // SECTION 3: Unpooled Heap (Array) — alloc + free
    // ====================================================================

    @Benchmark
    @Threads(1)
    public int unpooledHeap_allocFree_1t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(SIZE_256, SIZE_256);
        int c = buf.capacity();
        buf.free();
        return c;
    }

    @Benchmark
    @Threads(1)
    public int unpooledHeap_allocFree_1t_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(SIZE_256);
        int c = buf.capacity();
        buf.release();
        return c;
    }

    @Benchmark
    @Threads(8)
    public int unpooledHeap_allocFree_8t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(SIZE_256, SIZE_256);
        int c = buf.capacity();
        buf.free();
        return c;
    }

    @Benchmark
    @Threads(8)
    public int unpooledHeap_allocFree_8t_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(SIZE_256);
        int c = buf.capacity();
        buf.release();
        return c;
    }

    // ====================================================================
    // SECTION 4: Unpooled Direct — alloc + free
    // ====================================================================

    @Benchmark
    @Threads(1)
    public int unpooledDirect_allocFree_1t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_DIRECT_ALLOCATOR.buffer(SIZE_256, SIZE_256);
        int c = buf.capacity();
        buf.free();
        return c;
    }

    @Benchmark
    @Threads(1)
    public int unpooledDirect_allocFree_1t_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.directBuffer(SIZE_256);
        int c = buf.capacity();
        buf.release();
        return c;
    }

    // ====================================================================
    // SECTION 5: Neta Ring Buffer vs Netty Pooled (closest equivalent)
    //   Ring buffers are Neta-specific. Compare against Netty pooled as the
    //   closest functional equivalent (reusable, pre-allocated memory).
    // ====================================================================

    @Benchmark
    @Threads(1)
    public int ringHeap_allocFree_1t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.ringHeapBuffer(SIZE_256);
        int c = buf.capacity();
        buf.free();
        return c;
    }

    @Benchmark
    @Threads(1)
    public int ringHeap_vs_pooled_1t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(SIZE_256);
        int c = buf.capacity();
        buf.release();
        return c;
    }

    @Benchmark
    @Threads(8)
    public int ringHeap_allocFree_8t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.ringHeapBuffer(SIZE_256);
        int c = buf.capacity();
        buf.free();
        return c;
    }

    @Benchmark
    @Threads(8)
    public int ringHeap_vs_pooled_8t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(SIZE_256);
        int c = buf.capacity();
        buf.release();
        return c;
    }

    @Benchmark
    @Threads(1)
    public int ringDirect_allocFree_1t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_DIRECT_ALLOCATOR.ringDirectBuffer(SIZE_256);
        int c = buf.capacity();
        buf.free();
        return c;
    }

    @Benchmark
    @Threads(1)
    public int ringDirect_vs_pooled_1t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(SIZE_256);
        int c = buf.capacity();
        buf.release();
        return c;
    }

    // ====================================================================
    // SECTION 6: Pooled Heap — alloc + write + read + free (pipeline)
    //   Simulates realistic protocol usage: allocate, write payload, read back.
    // ====================================================================

    @Benchmark
    @Threads(1)
    public long pooledHeap_pipeline_1t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.pooledBuffer(SIZE_256);
        buf.writeBytes(payload256);
        buf.markWriter();
        long sum = 0;
        for (int i = 0; i < SIZE_256; i++) {
            sum += buf.readByte();
        }
        buf.free();
        return sum;
    }

    @Benchmark
    @Threads(1)
    public long pooledHeap_pipeline_1t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(SIZE_256);
        buf.writeBytes(payload256);
        long sum = 0;
        for (int i = 0; i < SIZE_256; i++) {
            sum += buf.readByte();
        }
        buf.release();
        return sum;
    }

    @Benchmark
    @Threads(8)
    public long pooledHeap_pipeline_8t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.pooledBuffer(SIZE_256);
        buf.writeBytes(payload256);
        buf.markWriter();
        long sum = 0;
        for (int i = 0; i < SIZE_256; i++) {
            sum += buf.readByte();
        }
        buf.free();
        return sum;
    }

    @Benchmark
    @Threads(8)
    public long pooledHeap_pipeline_8t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(SIZE_256);
        buf.writeBytes(payload256);
        long sum = 0;
        for (int i = 0; i < SIZE_256; i++) {
            sum += buf.readByte();
        }
        buf.release();
        return sum;
    }

    // ====================================================================
    // SECTION 7: Pooled Direct — pipeline (write + read)
    // ====================================================================

    @Benchmark
    @Threads(1)
    public long pooledDirect_pipeline_1t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_DIRECT_ALLOCATOR.pooledBuffer(SIZE_256);
        buf.writeBytes(payload256);
        buf.markWriter();
        long sum = 0;
        for (int i = 0; i < SIZE_256; i++) {
            sum += buf.readByte();
        }
        buf.free();
        return sum;
    }

    @Benchmark
    @Threads(1)
    public long pooledDirect_pipeline_1t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(SIZE_256);
        buf.writeBytes(payload256);
        long sum = 0;
        for (int i = 0; i < SIZE_256; i++) {
            sum += buf.readByte();
        }
        buf.release();
        return sum;
    }

    // ====================================================================
    // SECTION 8: Ring Buffer — pipeline (write + read)
    //   Compare Neta ring heap pipeline with Netty pooled heap pipeline.
    // ====================================================================

    @Benchmark
    @Threads(1)
    public long ringHeap_pipeline_1t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.ringHeapBuffer(SIZE_256);
        buf.writeBytes(payload256);
        buf.markWriter();
        long sum = 0;
        for (int i = 0; i < SIZE_256; i++) {
            sum += buf.readByte();
        }
        buf.free();
        return sum;
    }

    @Benchmark
    @Threads(1)
    public long ringHeap_pipeline_vs_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(SIZE_256);
        buf.writeBytes(payload256);
        long sum = 0;
        for (int i = 0; i < SIZE_256; i++) {
            sum += buf.readByte();
        }
        buf.release();
        return sum;
    }

    // ====================================================================
    // SECTION 9: Large payload (4K) — pooled heap pipeline
    //   Tests throughput with larger realistic payload size.
    // ====================================================================

    @Benchmark
    @Threads(1)
    public long pooledHeap_4k_pipeline_1t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.pooledBuffer(SIZE_4K);
        buf.writeBytes(payload4k);
        buf.markWriter();
        long sum = 0;
        for (int i = 0; i < 64; i++) {
            sum += buf.readInt64();
        }
        buf.free();
        return sum;
    }

    @Benchmark
    @Threads(1)
    public long pooledHeap_4k_pipeline_1t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(SIZE_4K);
        buf.writeBytes(payload4k);
        long sum = 0;
        for (int i = 0; i < 64; i++) {
            sum += buf.readLong();
        }
        buf.release();
        return sum;
    }

    @Benchmark
    @Threads(8)
    public long pooledHeap_4k_pipeline_8t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.pooledBuffer(SIZE_4K);
        buf.writeBytes(payload4k);
        buf.markWriter();
        long sum = 0;
        for (int i = 0; i < 64; i++) {
            sum += buf.readInt64();
        }
        buf.free();
        return sum;
    }

    @Benchmark
    @Threads(8)
    public long pooledHeap_4k_pipeline_8t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(SIZE_4K);
        buf.writeBytes(payload4k);
        long sum = 0;
        for (int i = 0; i < 64; i++) {
            sum += buf.readLong();
        }
        buf.release();
        return sum;
    }

    // ====================================================================
    // SECTION 10: Pooled Heap — high contention (32 threads) pipeline
    //   Tests pool allocator scalability under heavy contention.
    // ====================================================================

    @Benchmark
    @Threads(32)
    public long pooledHeap_pipeline_32t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.pooledBuffer(SIZE_256);
        buf.writeBytes(payload256);
        buf.markWriter();
        long sum = 0;
        for (int i = 0; i < 32; i++) {
            sum += buf.readByte();
        }
        buf.free();
        return sum;
    }

    @Benchmark
    @Threads(32)
    public long pooledHeap_pipeline_32t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(SIZE_256);
        buf.writeBytes(payload256);
        long sum = 0;
        for (int i = 0; i < 32; i++) {
            sum += buf.readByte();
        }
        buf.release();
        return sum;
    }
}
