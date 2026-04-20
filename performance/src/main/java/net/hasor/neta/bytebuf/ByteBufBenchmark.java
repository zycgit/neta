/*
 * ByteBuf Performance Benchmark: Neta vs Netty (Single-Thread)
 *
 * Design principles:
 *   - Fair comparison: heap-to-heap, direct-to-direct
 *   - Separate concerns: allocation tests vs read/write tests
 *   - Use @Setup to pre-allocate reusable arrays (avoid measuring array alloc)
 *   - Return values from all benchmarks to prevent JMH DCE
 *
 * Benchmark Scenarios:
 *   1. Pooled heap buffer allocation & release
 *   2. Pooled direct buffer allocation & release
 *   3. Unpooled heap buffer allocation & release
 *   4. Unpooled direct buffer allocation & release
 *   5. Sequential write (byte, int32, int64, byte[])
 *   6. Sequential read (byte, int32, int64, byte[])
 *   7. Random access read (getByte at random offsets)
 *   8. Mixed read-write pipeline (protocol encode/decode)
 *   9. Composite buffer assembly + sequential read
 *  10. Large payload write & read (64KB)
 *
 * Note on Neta's double-index model:
 *   Neta uses a markWriter/markReader model where data written via write*()
 *   only becomes readable after markWriter() is called. This extra call is
 *   an inherent part of the Neta API and is included in benchmarks that
 *   write-then-read. Netty does not require this step.
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
public class ByteBufBenchmark {

    // ========================================================================
    // Shared test data (pre-allocated in @Setup to avoid measurement noise)
    // ========================================================================

    private static final int SMALL_SIZE  = 256;
    private static final int MEDIUM_SIZE = 4096;
    private static final int LARGE_SIZE  = 65536;

    private byte[] smallPayload;
    private byte[] mediumPayload;
    private byte[] largePayload;
    private int[]  randomOffsets;
    private byte[] readDst;          // reusable read destination
    private byte[] largeDst;         // reusable large read destination

    // Pre-created fragments for composite tests
    private byte[] frag1;
    private byte[] frag2;
    private byte[] frag3;
    private byte[] frag4;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder().include(ByteBufBenchmark.class.getSimpleName()).resultFormat(ResultFormatType.TEXT).result("benchmark-results.txt").build();
        new Runner(opt).run();
    }

    // ========================================================================
    // 1. Pooled Heap Buffer Allocation (heap vs heap, fair comparison)
    // ========================================================================

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42);

        smallPayload = new byte[SMALL_SIZE];
        mediumPayload = new byte[MEDIUM_SIZE];
        largePayload = new byte[LARGE_SIZE];
        rng.nextBytes(smallPayload);
        rng.nextBytes(mediumPayload);
        rng.nextBytes(largePayload);

        // Pre-computed random offsets for random access tests (within 256 bytes)
        randomOffsets = new int[1024];
        for (int i = 0; i < randomOffsets.length; i++) {
            randomOffsets[i] = rng.nextInt(252); // leave room for int32 read at offset
        }

        // Pre-allocate read destinations to avoid measuring array allocation
        readDst = new byte[SMALL_SIZE];
        largeDst = new byte[LARGE_SIZE];

        // Pre-allocate composite fragments
        frag1 = new byte[] { 1, 2, 3, 4 };
        frag2 = new byte[] { 5, 6, 7, 8 };
        frag3 = new byte[] { 9, 10, 11, 12 };
        frag4 = new byte[] { 13, 14, 15, 16 };
    }

    @Benchmark
    public int pooledHeapAlloc_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.pooledBuffer(SMALL_SIZE);
        int cap = buf.capacity();
        buf.free();
        return cap;
    }

    // ========================================================================
    // 2. Pooled Direct Buffer Allocation (direct vs direct, fair comparison)
    // ========================================================================

    @Benchmark
    public int pooledHeapAlloc_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(SMALL_SIZE);
        int cap = buf.capacity();
        buf.release();
        return cap;
    }

    @Benchmark
    public int pooledDirectAlloc_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_DIRECT_ALLOCATOR.pooledBuffer(SMALL_SIZE);
        int cap = buf.capacity();
        buf.free();
        return cap;
    }

    // ========================================================================
    // 3. Unpooled Heap Buffer Allocation
    // ========================================================================

    @Benchmark
    public int pooledDirectAlloc_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(SMALL_SIZE);
        int cap = buf.capacity();
        buf.release();
        return cap;
    }

    @Benchmark
    public int heapAlloc_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(SMALL_SIZE, SMALL_SIZE);
        int cap = buf.capacity();
        buf.free();
        return cap;
    }

    // ========================================================================
    // 4. Unpooled Direct Buffer Allocation
    // ========================================================================

    @Benchmark
    public int heapAlloc_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(SMALL_SIZE);
        int cap = buf.capacity();
        buf.release();
        return cap;
    }

    @Benchmark
    public int directAlloc_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_DIRECT_ALLOCATOR.buffer(SMALL_SIZE, SMALL_SIZE);
        int cap = buf.capacity();
        buf.free();
        return cap;
    }

    // ========================================================================
    // 5. Sequential Write (byte + int32 + int64 + byte[])
    //    100 rounds of (1+4+8)=13 bytes + 256 bytes = 1556 bytes total.
    // ========================================================================

    @Benchmark
    public int directAlloc_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.directBuffer(SMALL_SIZE);
        int cap = buf.capacity();
        buf.release();
        return cap;
    }

    @Benchmark
    public int seqWrite_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(MEDIUM_SIZE, MEDIUM_SIZE);
        for (int i = 0; i < 100; i++) {
            buf.writeByte((byte) i);
            buf.writeInt32(i);
            buf.writeInt64(i);
        }
        buf.writeBytes(smallPayload);
        int written = buf.writtenBytes();
        buf.free();
        return written;
    }

    // ========================================================================
    // 6. Sequential Read (byte + int32 + int64 + byte[])
    //    Includes buffer alloc + data write + markWriter() as setup overhead.
    // ========================================================================

    @Benchmark
    public int seqWrite_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(MEDIUM_SIZE);
        for (int i = 0; i < 100; i++) {
            buf.writeByte(i);
            buf.writeInt(i);
            buf.writeLong(i);
        }
        buf.writeBytes(smallPayload);
        int written = buf.writerIndex();
        buf.release();
        return written;
    }

    @Benchmark
    public long seqRead_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(MEDIUM_SIZE, MEDIUM_SIZE);
        buf.writeBytes(mediumPayload);
        buf.markWriter();

        long sum = 0;
        for (int i = 0; i < 100; i++) {
            sum += buf.readByte();
            sum += buf.readInt32();
            sum += buf.readInt64();
        }
        buf.readBytes(readDst);
        sum += readDst[0];
        buf.free();
        return sum;
    }

    // ========================================================================
    // 7. Random Access Read (getByte at 1024 random offsets)
    //    Neta getByte(offset) is relative to readerIndex.
    //    Netty getByte(index) is absolute from index 0.
    //    Both read 1024 random positions from a 256-byte buffer.
    // ========================================================================

    @Benchmark
    public long seqRead_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(MEDIUM_SIZE);
        buf.writeBytes(mediumPayload);

        long sum = 0;
        for (int i = 0; i < 100; i++) {
            sum += buf.readByte();
            sum += buf.readInt();
            sum += buf.readLong();
        }
        buf.readBytes(readDst);
        sum += readDst[0];
        buf.release();
        return sum;
    }

    @Benchmark
    public long randomAccessRead_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(SMALL_SIZE, SMALL_SIZE);
        buf.writeBytes(smallPayload);
        buf.markWriter();

        long sum = 0;
        for (int i = 0; i < randomOffsets.length; i++) {
            sum += buf.getByte(randomOffsets[i]);
        }
        buf.free();
        return sum;
    }

    // ========================================================================
    // 8. Mixed Read-Write Pipeline (simulate protocol encode/decode)
    //    Writes a length-prefixed message then reads it back.
    // ========================================================================

    @Benchmark
    public long randomAccessRead_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(SMALL_SIZE);
        buf.writeBytes(smallPayload);

        long sum = 0;
        for (int i = 0; i < randomOffsets.length; i++) {
            sum += buf.getByte(randomOffsets[i]);
        }
        buf.release();
        return sum;
    }

    @Benchmark
    public long mixedPipeline_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(1024, 1024);
        // Encode: write header + payload
        buf.writeInt32(smallPayload.length);
        buf.writeBytes(smallPayload);
        buf.markWriter();

        // Decode: read header + payload
        int len = buf.readInt32();
        byte[] payload = new byte[len];
        buf.readBytes(payload);
        buf.free();
        return len + payload[0];
    }

    // ========================================================================
    // 9. Composite Buffer (assemble 4 fragments, sequential read)
    //    Both frameworks: wrap + composite assembly + read all + release.
    //    Neta: addComponents() calls retain() on each component (refCnt: 1->2).
    //          Releasing original refs transfers ownership to composite, so
    //          composite.free() fully cleans up (refCnt: 1->0 for each).
    //    Netty: addComponent(true, wrappedBuf) takes ownership directly.
    //          composite.release() fully releases all components.
    //    Neta: addComponent(...) transfers ownership directly.
    // ========================================================================

    @Benchmark
    public long mixedPipeline_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
        // Encode
        buf.writeInt(smallPayload.length);
        buf.writeBytes(smallPayload);

        // Decode
        int len = buf.readInt();
        byte[] payload = new byte[len];
        buf.readBytes(payload);
        buf.release();
        return len + payload[0];
    }

    @Benchmark
    public long compositeRead_neta() {
        net.hasor.neta.bytebuf.ByteBuf b1 = net.hasor.neta.bytebuf.ByteBuf.wrap(frag1);
        net.hasor.neta.bytebuf.ByteBuf b2 = net.hasor.neta.bytebuf.ByteBuf.wrap(frag2);
        net.hasor.neta.bytebuf.ByteBuf b3 = net.hasor.neta.bytebuf.ByteBuf.wrap(frag3);
        net.hasor.neta.bytebuf.ByteBuf b4 = net.hasor.neta.bytebuf.ByteBuf.wrap(frag4);

        net.hasor.neta.bytebuf.CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        composite.addComponent(b1);
        composite.addComponent(b2);
        composite.addComponent(b3);
        composite.addComponent(b4);

        long sum = 0;
        while (composite.readableBytes() > 0) {
            sum += composite.readByte();
        }

        // composite.free() fully releases all components (refCnt: 1->0)
        composite.free();
        return sum;
    }

    // ========================================================================
    // 10. Large Payload Write & Read (64KB)
    //     Uses pre-allocated dst array to avoid measuring array allocation.
    // ========================================================================

    @Benchmark
    public long compositeRead_netty() {
        io.netty.buffer.CompositeByteBuf composite = io.netty.buffer.Unpooled.compositeBuffer(4);
        composite.addComponent(true, io.netty.buffer.Unpooled.wrappedBuffer(frag1));
        composite.addComponent(true, io.netty.buffer.Unpooled.wrappedBuffer(frag2));
        composite.addComponent(true, io.netty.buffer.Unpooled.wrappedBuffer(frag3));
        composite.addComponent(true, io.netty.buffer.Unpooled.wrappedBuffer(frag4));

        long sum = 0;
        while (composite.readableBytes() > 0) {
            sum += composite.readByte();
        }

        // composite.release() releases all contained components
        composite.release();
        return sum;
    }

    @Benchmark
    public long largePayload_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(LARGE_SIZE, LARGE_SIZE);
        buf.writeBytes(largePayload);
        buf.markWriter();

        buf.readBytes(largeDst);
        buf.free();
        return largeDst[0] + largeDst[LARGE_SIZE - 1];
    }

    // ========================================================================
    // Runner
    // ========================================================================

    @Benchmark
    public long largePayload_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(LARGE_SIZE);
        buf.writeBytes(largePayload);

        buf.readBytes(largeDst);
        buf.release();
        return largeDst[0] + largeDst[LARGE_SIZE - 1];
    }
}
