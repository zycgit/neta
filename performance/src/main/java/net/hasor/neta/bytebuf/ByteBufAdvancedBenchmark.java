/*
 * Advanced ByteBuf Benchmark: Neta vs Netty (Single-Thread)
 *
 * Complements ByteBufBenchmark.java with NEW scenarios not already covered:
 *   1.  Ring buffer write + read cycle (Neta unique vs Netty unpooled direct)
 *   2.  Deep copy (buf.copy())
 *   3.  Slice / SliceOff
 *   4.  ByteOrder switch (LITTLE_ENDIAN write + read)
 *   5.  discardReadBytes compaction
 *   6.  String encode/decode (protocol text)
 *   7.  Complex protocol frame (multi-field struct encode/decode)
 *   8.  Buffer auto-growth (incremental writes beyond initial capacity)
 *   9.  Byte-by-byte sequential write (worst-case per-byte overhead)
 *  10.  Bulk ByteBuffer transfer (NIO interop)
 *
 * Already covered in ByteBufBenchmark (NOT duplicated here):
 *   - Pooled/Unpooled heap/direct allocation
 *   - Sequential write/read (byte+int32+int64+byte[])
 *   - Random access read (getByte at 1024 offsets)
 *   - Mixed pipeline (length-prefixed encode/decode)
 *   - Composite buffer assembly + sequential read
 *   - Large payload (64KB) write + read
 */
package net.hasor.neta.bytebuf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;
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
public class ByteBufAdvancedBenchmark {

    private static final int SMALL  = 256;
    private static final int MEDIUM = 4096;

    private byte[]     smallPayload;
    private byte[]     mediumPayload;
    private byte[]     readDst;
    private String     testString;
    private byte[]     stringBytes;
    private ByteBuffer nioSrcBuffer;
    private ByteBuffer nioDstBuffer;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder().include(ByteBufAdvancedBenchmark.class.getSimpleName()).resultFormat(ResultFormatType.TEXT).result("benchmark-advanced-results.txt").build();
        new Runner(opt).run();
    }

    // ========================================================================
    // 1. Ring Buffer Write + Read Cycle
    //    Neta ring buffer is a unique feature — compare with Netty unpooled direct.
    //    Both operate on same-sized buffers for fair comparison.
    // ========================================================================

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42);
        smallPayload = new byte[SMALL];
        mediumPayload = new byte[MEDIUM];
        rng.nextBytes(smallPayload);
        rng.nextBytes(mediumPayload);
        readDst = new byte[SMALL];

        // A typical HTTP-like text string for string encode/decode tests
        testString = "GET /api/v1/users?page=1&size=20 HTTP/1.1\r\nHost: example.com\r\nContent-Type: application/json\r\n\r\n";
        stringBytes = testString.getBytes(StandardCharsets.UTF_8);

        // Pre-allocated NIO buffers for transfer tests
        nioSrcBuffer = ByteBuffer.allocate(SMALL);
        nioSrcBuffer.put(smallPayload);
        nioSrcBuffer.flip();

        nioDstBuffer = ByteBuffer.allocate(SMALL);
    }

    @Benchmark
    public long ringBuffer_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.ringHeapBuffer(SMALL);
        buf.writeBytes(smallPayload);
        buf.markWriter();

        long sum = 0;
        for (int i = 0; i < SMALL; i++) {
            sum += buf.readByte();
        }
        buf.free();
        return sum;
    }

    // ========================================================================
    // 2. Deep Copy (buf.copy())
    //    Copy a 256-byte buffer, read from copy, release both.
    // ========================================================================

    @Benchmark
    public long ringBuffer_netty() {
        // Netty has no ring buffer. Compare with regular unpooled heap buffer.
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(SMALL);
        buf.writeBytes(smallPayload);

        long sum = 0;
        for (int i = 0; i < SMALL; i++) {
            sum += buf.readByte();
        }
        buf.release();
        return sum;
    }

    @Benchmark
    public long deepCopy_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(SMALL, SMALL);
        buf.writeBytes(smallPayload);
        buf.markWriter();

        net.hasor.neta.bytebuf.ByteBuf copy = buf.copy();
        long sum = 0;
        for (int i = 0; i < 16; i++) {
            sum += copy.readByte();
        }
        buf.free();
        copy.free();
        return sum;
    }

    // ========================================================================
    // 3. Slice / SliceOff
    //    Neta sliceOff(n): extract first n readable bytes into new buffer.
    //    Netty readSlice(n): zero-copy slice sharing underlying memory.
    //    Different semantics (copy vs zero-copy), but both represent the
    //    "extract a sub-buffer" user intent.
    // ========================================================================

    @Benchmark
    public long deepCopy_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(SMALL);
        buf.writeBytes(smallPayload);

        io.netty.buffer.ByteBuf copy = buf.copy();
        long sum = 0;
        for (int i = 0; i < 16; i++) {
            sum += copy.readByte();
        }
        buf.release();
        copy.release();
        return sum;
    }

    @Benchmark
    public long sliceOff_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(SMALL, SMALL);
        buf.writeBytes(smallPayload);
        buf.markWriter();

        net.hasor.neta.bytebuf.ByteBuf slice = buf.sliceOff(64);
        long sum = 0;
        for (int i = 0; i < 64; i++) {
            sum += slice.readByte();
        }
        slice.free();
        buf.free();
        return sum;
    }

    // ========================================================================
    // 4. ByteOrder Switch (LITTLE_ENDIAN write + read)
    //    Write int32 values in LE order, then read back.
    // ========================================================================

    @Benchmark
    public long slice_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(SMALL);
        buf.writeBytes(smallPayload);

        io.netty.buffer.ByteBuf slice = buf.readSlice(64);
        long sum = 0;
        for (int i = 0; i < 64; i++) {
            sum += slice.readByte();
        }
        // readSlice shares memory — no separate release needed
        buf.release();
        return sum;
    }

    @Benchmark
    public long byteOrderLE_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(512, 512);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 100; i++) {
            buf.writeInt32(i * 7 + 13);
        }
        buf.markWriter();

        long sum = 0;
        for (int i = 0; i < 100; i++) {
            sum += buf.readInt32();
        }
        buf.free();
        return sum;
    }

    // ========================================================================
    // 5. discardReadBytes Compaction
    //    Write 200 bytes, read 100, discardReadBytes, write 100 more, read all.
    //    Tests memory compaction efficiency.
    // ========================================================================

    @SuppressWarnings("deprecation")
    @Benchmark
    public long byteOrderLE_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(512).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 100; i++) {
            buf.writeInt(i * 7 + 13);
        }

        long sum = 0;
        for (int i = 0; i < 100; i++) {
            sum += buf.readInt();
        }
        buf.release();
        return sum;
    }

    @Benchmark
    public long discardReadBytes_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(SMALL, SMALL);
        buf.writeBytes(smallPayload, 0, 200);
        buf.markWriter();

        // Read first 100 bytes
        long sum = 0;
        for (int i = 0; i < 100; i++) {
            sum += buf.readByte();
        }
        buf.markReader();
        buf.discardReadBytes();

        // Write 100 more
        buf.writeBytes(smallPayload, 0, 100);
        buf.markWriter();

        // Read remaining 200 bytes
        for (int i = 0; i < 200; i++) {
            sum += buf.readByte();
        }
        buf.free();
        return sum;
    }

    // ========================================================================
    // 6. String Encode / Decode (protocol text handling)
    //    Write an HTTP-like header string and read it back.
    // ========================================================================

    @Benchmark
    public long discardReadBytes_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(SMALL);
        buf.writeBytes(smallPayload, 0, 200);

        long sum = 0;
        for (int i = 0; i < 100; i++) {
            sum += buf.readByte();
        }
        buf.discardReadBytes();

        buf.writeBytes(smallPayload, 0, 100);

        for (int i = 0; i < 200; i++) {
            sum += buf.readByte();
        }
        buf.release();
        return sum;
    }

    @Benchmark
    public int stringEncodeDecode_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(256, 256);
        buf.writeString(testString, StandardCharsets.UTF_8);
        buf.markWriter();

        String decoded = buf.readString(stringBytes.length, StandardCharsets.UTF_8);
        int len = decoded.length();
        buf.free();
        return len;
    }

    // ========================================================================
    // 7. Complex Protocol Frame (multi-field struct encode/decode)
    //    Simulates a binary RPC frame:
    //      magic(2B) + version(1B) + flags(1B) + msgId(4B) + bodyLen(4B) + body(256B)
    //    Total frame = 268 bytes. Encode + decode in one op.
    // ========================================================================

    @Benchmark
    public int stringEncodeDecode_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(256);
        buf.writeBytes(stringBytes);

        byte[] readBack = new byte[stringBytes.length];
        buf.readBytes(readBack);
        String decoded = new String(readBack, StandardCharsets.UTF_8);
        int len = decoded.length();
        buf.release();
        return len;
    }

    @Benchmark
    public long protocolFrame_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(512, 512);
        // Encode
        buf.writeInt16((short) 0xCAFE);  // magic
        buf.writeByte((byte) 1);         // version
        buf.writeByte((byte) 0x03);      // flags
        buf.writeInt32(123456);          // msgId
        buf.writeInt32(SMALL);           // bodyLen
        buf.writeBytes(smallPayload);    // body
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
    // 8. Buffer auto-growth (write beyond initial capacity)
    //    Start with 64-byte buffer, write 1024 bytes to force expansion.
    //    Tests auto-resize efficiency.
    // ========================================================================

    @Benchmark
    public long protocolFrame_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(512);
        // Encode
        buf.writeShort(0xCAFE);
        buf.writeByte(1);
        buf.writeByte(0x03);
        buf.writeInt(123456);
        buf.writeInt(SMALL);
        buf.writeBytes(smallPayload);

        // Decode
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
    public int autoGrowth_neta() {
        // Neta auto-expanding buffer: initial=64, max=Integer.MAX_VALUE
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(64);
        for (int i = 0; i < 16; i++) {
            buf.writeBytes(smallPayload, 0, 64);
        }
        int w = buf.writtenBytes();
        buf.free();
        return w;
    }

    // ========================================================================
    // 9. Byte-by-byte sequential write (worst-case per-byte overhead)
    //    Writes 1024 individual bytes one at a time.
    // ========================================================================

    @Benchmark
    public int autoGrowth_netty() {
        // Netty auto-expanding: initial=64, max=Integer.MAX_VALUE
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(64, Integer.MAX_VALUE);
        for (int i = 0; i < 16; i++) {
            buf.writeBytes(smallPayload, 0, 64);
        }
        int w = buf.writerIndex();
        buf.release();
        return w;
    }

    @Benchmark
    public int byteByByteWrite_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(1024, 1024);
        for (int i = 0; i < 1024; i++) {
            buf.writeByte((byte) i);
        }
        int w = buf.writtenBytes();
        buf.free();
        return w;
    }

    // ========================================================================
    // 10. Bulk NIO ByteBuffer Transfer
    //     Write from java.nio.ByteBuffer into ByteBuf, then read back into
    //     another java.nio.ByteBuffer. Tests NIO interop performance.
    // ========================================================================

    @Benchmark
    public int byteByByteWrite_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(1024);
        for (int i = 0; i < 1024; i++) {
            buf.writeByte(i);
        }
        int w = buf.writerIndex();
        buf.release();
        return w;
    }

    @Benchmark
    public int nioTransfer_neta() {
        net.hasor.neta.bytebuf.ByteBuf buf = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(SMALL, SMALL);
        nioSrcBuffer.clear();
        buf.writeBuffer(nioSrcBuffer, SMALL);
        buf.markWriter();

        nioDstBuffer.clear();
        buf.readBuffer(nioDstBuffer, SMALL);
        buf.free();
        return nioDstBuffer.position();
    }

    // ========================================================================
    // Runner
    // ========================================================================

    @Benchmark
    public int nioTransfer_netty() {
        io.netty.buffer.ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.heapBuffer(SMALL);
        nioSrcBuffer.clear();
        buf.writeBytes(nioSrcBuffer);

        nioDstBuffer.clear();
        buf.readBytes(nioDstBuffer);
        buf.release();
        return nioDstBuffer.position();
    }
}
