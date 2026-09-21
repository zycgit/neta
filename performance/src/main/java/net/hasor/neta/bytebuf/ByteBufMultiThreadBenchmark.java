/*
 * Multi-threaded ByteBuf Benchmark: Neta vs Netty
 *
 * Tests concurrent buffer allocation/release under contention.
 * All comparisons are type-matched: heap vs heap, direct vs direct.
 *
 * Design principles:
 *   - Fair comparison: same buffer type (heap/direct) for both frameworks
 *   - Return values to prevent JMH DCE
 *   - Vary thread counts: 4, 16, 32 for scaling analysis
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

@Fork(1)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
public class ByteBufMultiThreadBenchmark {

    private static final int    SMALL_SIZE = 256;
    private              byte[] payload;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder().include(ByteBufMultiThreadBenchmark.class.getSimpleName()).resultFormat(ResultFormatType.TEXT).result("benchmark-multithread-results.txt").build();
        new Runner(opt).run();
    }

    // ========================================================================
    // Pooled Heap Allocation - 4 threads (heap vs heap)
    // ========================================================================

    @Setup(Level.Trial)
    public void setup() {
        payload = new byte[SMALL_SIZE];
        new Random(42).nextBytes(payload);
    }

    @Benchmark
    @Threads(4)
    public int pooledHeapAlloc_4t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.pooledBuffer(SMALL_SIZE);
        int cap = buf.capacity();
        buf.free();
        return cap;
    }

    // ========================================================================
    // Pooled Heap Allocation - 16 threads
    // ========================================================================

    @Benchmark
    @Threads(4)
    public int pooledHeapAlloc_4t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(SMALL_SIZE);
        int cap = buf.capacity();
        buf.release();
        return cap;
    }

    @Benchmark
    @Threads(16)
    public int pooledHeapAlloc_16t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.pooledBuffer(SMALL_SIZE);
        int cap = buf.capacity();
        buf.free();
        return cap;
    }

    // ========================================================================
    // Pooled Heap Allocation - 32 threads
    // ========================================================================

    @Benchmark
    @Threads(16)
    public int pooledHeapAlloc_16t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(SMALL_SIZE);
        int cap = buf.capacity();
        buf.release();
        return cap;
    }

    @Benchmark
    @Threads(32)
    public int pooledHeapAlloc_32t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.pooledBuffer(SMALL_SIZE);
        int cap = buf.capacity();
        buf.free();
        return cap;
    }

    // ========================================================================
    // Multi-thread Write+Read pipeline - 8 threads (pooled heap)
    //   Write 256 bytes, markWriter, read 32 bytes back
    // ========================================================================

    @Benchmark
    @Threads(32)
    public int pooledHeapAlloc_32t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(SMALL_SIZE);
        int cap = buf.capacity();
        buf.release();
        return cap;
    }

    @Benchmark
    @Threads(8)
    public long mixedPipeline_8t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.pooledBuffer(512, 512);
        buf.writeBytes(payload);
        buf.markWriter();

        long sum = 0;
        for (int i = 0; i < 32; i++) {
            sum += buf.readByte();
        }
        buf.free();
        return sum;
    }

    // ========================================================================
    // Multi-thread Unpooled Heap - 8 threads
    // ========================================================================

    @Benchmark
    @Threads(8)
    public long mixedPipeline_8t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(512);
        buf.writeBytes(payload);

        long sum = 0;
        for (int i = 0; i < 32; i++) {
            sum += buf.readByte();
        }
        buf.release();
        return sum;
    }

    @Benchmark
    @Threads(8)
    public int heapAlloc_8t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(SMALL_SIZE, SMALL_SIZE);
        int cap = buf.capacity();
        buf.free();
        return cap;
    }

    // ========================================================================
    // Runner
    // ========================================================================

    @Benchmark
    @Threads(8)
    public int heapAlloc_8t_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(SMALL_SIZE);
        int cap = buf.capacity();
        buf.release();
        return cap;
    }
}
