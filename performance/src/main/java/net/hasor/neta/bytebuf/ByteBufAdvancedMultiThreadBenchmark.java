/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Complements ByteBufMultiThreadBenchmark.java with NEW concurrent scenarios:
 *   1.  Pooled Direct allocation under contention (4t / 16t)
 *   2.  Complex protocol frame encode/decode under contention (8t / 16t)
 *   3.  Buffer auto-growth under contention (8t)
 *   4.  Ring buffer write+read under contention (8t, Neta unique)
 *   5.  Byte-by-byte write under contention (8t)
 *
 * Already covered in ByteBufMultiThreadBenchmark (NOT duplicated here):
 *   - Pooled heap alloc at 4t / 16t / 32t
 *   - Mixed pipeline (write+read 256B) at 8t
 *   - Unpooled heap alloc at 8t
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
public class ByteBufAdvancedMultiThreadBenchmark {

    private static final int    SMALL = 256;
    private              byte[] payload;
    private              byte[] readDst;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder().include(ByteBufAdvancedMultiThreadBenchmark.class.getSimpleName()).resultFormat(ResultFormatType.TEXT).result("benchmark-advanced-multithread-results.txt").build();
        new Runner(opt).run();
    }

    // ========================================================================
    // 1. Pooled Direct Allocation - 4 threads
    //    (Existing multi-thread benchmark only tests pooled HEAP)
    // ========================================================================

    @Setup(Level.Trial)
    public void setup() {
        payload = new byte[SMALL];
        new Random(42).nextBytes(payload);
        readDst = new byte[SMALL];
    }

    @Benchmark
    @Threads(4)
    public int pooledDirectAlloc_4t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_DIRECT_ALLOCATOR.pooledBuffer(SMALL);
        int cap = buf.capacity();
        buf.free();
        return cap;
    }

    // ========================================================================
    // 1b. Pooled Direct Allocation - 16 threads
    // ========================================================================

    @Benchmark
    @Threads(4)
    public int pooledDirectAlloc_4t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(SMALL);
        int cap = buf.capacity();
        buf.release();
        return cap;
    }

    @Benchmark
    @Threads(16)
    public int pooledDirectAlloc_16t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_DIRECT_ALLOCATOR.pooledBuffer(SMALL);
        int cap = buf.capacity();
        buf.free();
        return cap;
    }

    // ========================================================================
    // 2. Complex Protocol Frame - 8 threads
    //    Each thread encodes + decodes a 268-byte binary RPC frame.
    //    Tests pooled buffer throughput under realistic workload contention.
    // ========================================================================

    @Benchmark
    @Threads(16)
    public int pooledDirectAlloc_16t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(SMALL);
        int cap = buf.capacity();
        buf.release();
        return cap;
    }

    @Benchmark
    @Threads(8)
    public long protocolFrame_8t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.pooledBuffer(512, 512);
        // Encode
        buf.writeInt16((short) 0xCAFE);
        buf.writeByte((byte) 1);
        buf.writeByte((byte) 0x03);
        buf.writeInt32(123456);
        buf.writeInt32(SMALL);
        buf.writeBytes(payload);
        buf.markWriter();
        // Decode
        int magic = buf.readInt16();
        byte version = buf.readByte();
        byte flags = buf.readByte();
        int msgId = buf.readInt32();
        int bodyLen = buf.readInt32();
        buf.readBytes(readDst, 0, bodyLen);
        buf.free();
        return magic + version + flags + msgId + bodyLen + readDst[0];
    }

    // ========================================================================
    // 2b. Complex Protocol Frame - 16 threads (high contention)
    // ========================================================================

    @Benchmark
    @Threads(8)
    public long protocolFrame_8t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(512);
        buf.writeShort(0xCAFE);
        buf.writeByte(1);
        buf.writeByte(0x03);
        buf.writeInt(123456);
        buf.writeInt(SMALL);
        buf.writeBytes(payload);

        int magic = buf.readShort();
        byte version = buf.readByte();
        byte flags = buf.readByte();
        int msgId = buf.readInt();
        int bodyLen = buf.readInt();
        buf.readBytes(readDst, 0, bodyLen);
        buf.release();
        return magic + version + flags + msgId + bodyLen + readDst[0];
    }

    @Benchmark
    @Threads(16)
    public long protocolFrame_16t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.POOLED_HEAP_ALLOCATOR.pooledBuffer(512, 512);
        buf.writeInt16((short) 0xCAFE);
        buf.writeByte((byte) 1);
        buf.writeByte((byte) 0x03);
        buf.writeInt32(123456);
        buf.writeInt32(SMALL);
        buf.writeBytes(payload);
        buf.markWriter();

        int magic = buf.readInt16();
        byte version = buf.readByte();
        byte flags = buf.readByte();
        int msgId = buf.readInt32();
        int bodyLen = buf.readInt32();
        buf.readBytes(readDst, 0, bodyLen);
        buf.free();
        return magic + version + flags + msgId + bodyLen + readDst[0];
    }

    // ========================================================================
    // 3. Buffer Auto-Growth - 8 threads
    //    Start with 64-byte buffer, write 1024 bytes to force expansion.
    // ========================================================================

    @Benchmark
    @Threads(16)
    public long protocolFrame_16t_netty() {
        io.netty.buffer.ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(512);
        buf.writeShort(0xCAFE);
        buf.writeByte(1);
        buf.writeByte(0x03);
        buf.writeInt(123456);
        buf.writeInt(SMALL);
        buf.writeBytes(payload);

        int magic = buf.readShort();
        byte version = buf.readByte();
        byte flags = buf.readByte();
        int msgId = buf.readInt();
        int bodyLen = buf.readInt();
        buf.readBytes(readDst, 0, bodyLen);
        buf.release();
        return magic + version + flags + msgId + bodyLen + readDst[0];
    }

    @Benchmark
    @Threads(8)
    public int autoGrowth_8t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(64);
        for (int i = 0; i < 16; i++) {
            buf.writeBytes(payload, 0, 64);
        }
        int w = buf.writtenBytes();
        buf.free();
        return w;
    }

    // ========================================================================
    // 4. Ring Buffer Write+Read - 8 threads (Neta unique feature)
    //    Compare ring buffer with Netty unpooled heap for fairness.
    // ========================================================================

    @Benchmark
    @Threads(8)
    public int autoGrowth_8t_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(64, Integer.MAX_VALUE);
        for (int i = 0; i < 16; i++) {
            buf.writeBytes(payload, 0, 64);
        }
        int w = buf.writerIndex();
        buf.release();
        return w;
    }

    @Benchmark
    @Threads(8)
    public long ringBuffer_8t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.ringHeapBuffer(SMALL);
        buf.writeBytes(payload);
        buf.markWriter();
        long sum = 0;
        for (int i = 0; i < SMALL; i++) {
            sum += buf.readByte();
        }
        buf.free();
        return sum;
    }

    // ========================================================================
    // 5. Byte-by-byte Write - 8 threads (worst-case per-byte overhead)
    // ========================================================================

    @Benchmark
    @Threads(8)
    public long ringBuffer_8t_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(SMALL);
        buf.writeBytes(payload);
        long sum = 0;
        for (int i = 0; i < SMALL; i++) {
            sum += buf.readByte();
        }
        buf.release();
        return sum;
    }

    @Benchmark
    @Threads(8)
    public int byteByByteWrite_8t_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(1024, 1024);
        for (int i = 0; i < 1024; i++) {
            buf.writeByte((byte) i);
        }
        int w = buf.writtenBytes();
        buf.free();
        return w;
    }

    // ========================================================================
    // Runner
    // ========================================================================

    @Benchmark
    @Threads(8)
    public int byteByByteWrite_8t_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
        for (int i = 0; i < 1024; i++) {
            buf.writeByte(i);
        }
        int w = buf.writerIndex();
        buf.release();
        return w;
    }
}
