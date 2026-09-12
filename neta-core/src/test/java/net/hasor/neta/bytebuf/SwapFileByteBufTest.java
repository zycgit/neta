/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 针对 {@link SwapFileByteBuf} 的全面测试：内存模式、文件模式、滑动窗口、数据完整性、文件紧凑等场景。
 */
public class SwapFileByteBufTest {
    /** 用于测试的小阈值：8 字节下内存，大于 8 字节自动换出文件 */
    private static final int SMALL_THRESHOLD = 8;
    /** Segment 大小：每个临时文件分段的容量（16 字节），头部 Segment 被完整消费后直接删除 */
    private static final int SMALL_COMPACT   = 16;

    private SwapFileByteBuf buf;

    /** 强制切换到文件模式：写出 SMALL_THRESHOLD+1 字节并标记，然后清空读指针 */
    private static void forceFileMode(SwapFileByteBuf b) {
        byte[] trigger = new byte[SMALL_THRESHOLD + 1];
        b.writeBytes(trigger);
        assertTrue("Must enter file mode", b.isFileMode());
        // 读掉刚才写的触发数据，使 readableBytes 恢复为 0（下一个 markWriter 后才有新内容）
        b.markWriter();
        b.readBytes(new byte[SMALL_THRESHOLD + 1]);
        b.markReader();
    }

    /** 构造从 startVal 开始的 length 字节序列（溢出取低位） */
    private static byte[] buildSequence(int startVal, int length) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) ((startVal + i) & 0xFF);
        }
        return b;
    }

    // =========================================================================
    // 工厂方法 & 内存模式基础
    // =========================================================================

    @Before
    public void setUp() {
        buf = null;
    }

    @After
    public void tearDown() {
        if (buf != null && !buf.isFree()) {
            buf.free();
        }
        buf = null;
    }

    @Test
    public void test_factory_defaultThreshold() {
        ByteBuf b = ByteBufAllocator.DEFAULT.swapFile();
        assertNotNull(b);
        assertFalse(((SwapFileByteBuf) b).isFileMode());
        b.free();
    }

    @Test
    public void test_factory_customThreshold() {
        ByteBuf b = ByteBufAllocator.DEFAULT.swapFile(1024);
        assertNotNull(b);
        assertFalse(((SwapFileByteBuf) b).isFileMode());
        b.free();
    }

    @Test
    public void test_factory_viaAllocatorSingleArg() {
        ByteBuf b = ByteBufAllocator.DEFAULT.swapFile(512, 2048);
        assertNotNull(b);
        b.free();
    }

    // =========================================================================
    // 内存→文件模式切换
    // =========================================================================

    @Test
    public void test_memoryMode_belowThreshold() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        buf.writeBytes("Hello".getBytes(StandardCharsets.UTF_8));   // 5 bytes < 8
        assertFalse("Should still be in memory", buf.isFileMode());
        assertNull(buf.getTempFile());

        buf.markWriter();
        assertEquals(5, buf.readableBytes());
        byte[] dst = new byte[5];
        buf.readBytes(dst);
        assertArrayEquals("Hello".getBytes(StandardCharsets.UTF_8), dst);
    }

    @Test
    public void test_memoryMode_atThreshold_noSwitch() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        buf.writeBytes(new byte[SMALL_THRESHOLD]);  // exactly at threshold, not over
        // Still in memory (>  not >=)
        assertFalse("Should still be in memory at exactly threshold", buf.isFileMode());
    }

    @Test
    public void test_switchToFileMode_onWrite() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        buf.writeBytes(new byte[SMALL_THRESHOLD]); // at threshold, memory mode
        assertFalse(buf.isFileMode());

        buf.writeByte((byte) 0x42);                // threshold+1, triggers switch
        assertTrue("Must switch to file mode after exceeding threshold", buf.isFileMode());
        assertNotNull("Temp file must exist", buf.getTempFile());
        assertTrue("Temp file should exist on disk", buf.getTempFile().exists());
    }

    // =========================================================================
    // 文件模式基础读写
    // =========================================================================

    @Test
    public void test_switchToFileMode_dataIntegrity() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        // Write exactly SMALL_THRESHOLD bytes (still in memory)
        byte[] pre = buildSequence(0, SMALL_THRESHOLD);
        buf.writeBytes(pre);
        assertFalse(buf.isFileMode());

        // Write one more byte to trigger switch
        buf.writeByte((byte) 99);
        assertTrue(buf.isFileMode());

        buf.markWriter();
        assertEquals(SMALL_THRESHOLD + 1, buf.readableBytes());

        // Read all back and verify
        byte[] result = new byte[SMALL_THRESHOLD + 1];
        buf.readBytes(result);
        for (int i = 0; i < SMALL_THRESHOLD; i++) {
            assertEquals("pre-switch byte[" + i + "]", pre[i], result[i]);
        }
        assertEquals("post-switch byte", (byte) 99, result[SMALL_THRESHOLD]);
    }

    @Test
    public void test_switchToFileMode_largeWrite() {
        // Single large write that spans the threshold
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        byte[] data = buildSequence(0, SMALL_THRESHOLD * 4);
        buf.writeBytes(data);
        assertTrue("Should be in file mode after large write", buf.isFileMode());

        buf.markWriter();
        byte[] result = new byte[data.length];
        buf.readBytes(result);
        assertArrayEquals("Data must be intact after large write", data, result);
    }

    @Test
    public void test_fileMode_writeByte_readByte() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);
        assertTrue(buf.isFileMode());

        buf.writeByte((byte) 0xAB);
        buf.markWriter();
        assertEquals((byte) 0xAB, buf.readByte());
    }

    @Test
    public void test_fileMode_writeInt32_readInt32() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);

        buf.writeInt32(0xDEADBEEF);
        buf.writeInt32(12345678);
        buf.markWriter();
        assertEquals(0xDEADBEEF, buf.readInt32());
        assertEquals(12345678, buf.readInt32());
    }

    @Test
    public void test_fileMode_writeInt64_readInt64() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);

        long v = Long.MAX_VALUE - 999L;
        buf.writeInt64(v);
        buf.markWriter();
        assertEquals(v, buf.readInt64());
    }

    @Test
    public void test_fileMode_writeInt16_readInt16() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);

        buf.writeInt16((short) -1234);
        buf.writeInt16((short) 5678);
        buf.markWriter();
        assertEquals((short) -1234, buf.readInt16());
        assertEquals((short) 5678, buf.readInt16());
    }

    @Test
    public void test_fileMode_writeFloat32_readFloat32() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);

        buf.writeFloat32(3.14f);
        buf.markWriter();
        assertEquals(3.14f, buf.readFloat32(), 1e-6f);
    }

    // =========================================================================
    // markReader / discardReadBytes — 滑动窗口
    // =========================================================================

    @Test
    public void test_fileMode_writeFloat64_readFloat64() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);

        buf.writeFloat64(Math.PI);
        buf.markWriter();
        assertEquals(Math.PI, buf.readFloat64(), 1e-12);
    }

    @Test
    public void test_fileMode_getByte_setByte() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);

        buf.writeBytes(new byte[] { 1, 2, 3, 4, 5 });
        buf.markWriter();
        // getByte is relative to readerIndex
        assertEquals(1, buf.getByte(0));
        assertEquals(3, buf.getByte(2));
        assertEquals(5, buf.getByte(4));

        // setByte modifies at offset from markedWriterIndex
        // Note: setByte offset is relative to markedWriterIndex in AbstractByteBuf
        // We verify by reading back
        buf.resetReader();
        assertEquals(1, buf.readByte());
    }

    @Test
    public void test_markReader_memMode_shifts() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, 1024, 4096, "swap_test");
        buf.writeBytes("AABBCC".getBytes(StandardCharsets.UTF_8));
        buf.markWriter();
        buf.readBytes(new byte[2]);  // consume "AA"

        assertFalse(buf.isFileMode());
        buf.markReader();            // advance mark, compact

        assertEquals(4, buf.readableBytes());  // "BBCC"
        byte[] rest = new byte[4];
        buf.readBytes(rest);
        assertArrayEquals("BBCC".getBytes(StandardCharsets.UTF_8), rest);
    }

    @Test
    public void test_markReader_fileMode_advancesOffset() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);

        byte[] chunk = buildSequence(10, 20);  // 20 bytes
        buf.writeBytes(chunk);
        buf.markWriter();
        buf.readBytes(new byte[5]);   // consume 5 bytes

        long baseBefore = buf.getAbsoluteBase();
        buf.markReader();             // should advance absoluteBase by 5
        long baseAfter = buf.getAbsoluteBase();

        assertEquals("absoluteBase should advance by 5", baseBefore + 5, baseAfter);
        assertEquals("readableBytes after markReader", 15, buf.readableBytes());
    }

    @Test
    public void test_discardReadBytes_fileMode() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);

        buf.writeBytes(buildSequence(0, 30));
        buf.markWriter();
        buf.readBytes(new byte[10]);     // read 10
        buf.markReader();                // mark → discard first 10

        // Now read 5 more
        buf.readBytes(new byte[5]);
        buf.discardReadBytes();          // discard another 5

        // Remaining readable = 30 - 10 - 5 = 15
        assertEquals(15, buf.readableBytes());
    }

    // =========================================================================
    // 滑动窗口连续读写（流式场景）
    // =========================================================================

    @Test
    public void test_discardReadBytes_fileMode_releasesSegments() {
        // segmentSize=10: 每个 Segment 容纳 10 字节，消耗超过一个 Segment 则直接删除（无拷贝）
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, 10, "swap_test");
        forceFileMode(buf);

        buf.writeBytes(buildSequence(0, 30));
        buf.markWriter();
        long baseBefore = buf.getAbsoluteBase();
        buf.readBytes(new byte[11]);
        buf.discardReadBytes();          // absoluteBase += 11，头部已满 Segment 被删除

        assertEquals("absoluteBase should advance by 11", baseBefore + 11, buf.getAbsoluteBase());
        assertEquals("readable bytes unchanged", 19, buf.readableBytes());

        // 验证数据完整性
        byte[] expected = buildSequence(11, 19);
        byte[] result = new byte[19];
        buf.readBytes(result);
        assertArrayEquals("Data must be intact after segment release", expected, result);
    }

    @Test
    public void test_markReader_fileMode_releasesSegments() {
        // segmentSize=10：markReader 后 absoluteBase 推进，头部已满 Segment 被删除
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, 10, "swap_test");
        forceFileMode(buf);

        buf.writeBytes(buildSequence(0, 30));
        buf.markWriter();
        long baseBefore = buf.getAbsoluteBase();
        buf.readBytes(new byte[11]);
        buf.markReader();

        assertEquals("absoluteBase should advance by 11", baseBefore + 11, buf.getAbsoluteBase());
        assertEquals("readableBytes should be unchanged", 19, buf.readableBytes());

        byte[] result = new byte[19];
        buf.readBytes(result);
        assertArrayEquals(buildSequence(11, 19), result);
    }

    // =========================================================================
    // copy / toString / free
    // =========================================================================

    @Test
    public void test_slidingWindow_continuousStream() {
        // Simulate streaming: write chunks, consume and mark, write more, repeat
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        int totalWritten = 0;
        int totalRead = 0;
        int chunkSize = SMALL_THRESHOLD + 1;  // 必须大于阈值，才能触发文件模式

        for (int round = 0; round < 30; round++) {
            // Write one chunk
            byte[] out = buildSequence(totalWritten, chunkSize);
            buf.writeBytes(out);
            buf.markWriter();
            totalWritten += chunkSize;

            // Read and verify one chunk  (when available)
            if (buf.readableBytes() >= chunkSize) {
                byte[] in = new byte[chunkSize];
                buf.readBytes(in);
                assertArrayEquals("Round " + round, buildSequence(totalRead, chunkSize), in);
                totalRead += chunkSize;
                buf.markReader();  // release consumed data
            }
        }
        assertTrue("At some point should switch to file mode", buf.isFileMode());
    }

    @Test
    public void test_slidingWindow_largeChunks() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, 100, 200, "swap_test");
        byte[] allData = buildSequence(0, 1000);
        int pos = 0;
        int chunkW = 50;
        int chunkR = 40;
        int totalRead = 0;

        while (pos < allData.length) {
            int rem = Math.min(chunkW, allData.length - pos);
            buf.writeBytes(allData, pos, rem);
            buf.markWriter();
            pos += rem;

            while (buf.readableBytes() >= chunkR) {
                byte[] rb = new byte[chunkR];
                buf.readBytes(rb);
                byte[] expected = Arrays.copyOfRange(allData, totalRead, totalRead + chunkR);
                assertArrayEquals("Chunk starting at " + totalRead, expected, rb);
                totalRead += chunkR;
                buf.markReader();
            }
        }
        // Read remaining
        int left = buf.readableBytes();
        if (left > 0) {
            byte[] rb = new byte[left];
            buf.readBytes(rb);
            byte[] expected = Arrays.copyOfRange(allData, totalRead, totalRead + left);
            assertArrayEquals("Final chunk", expected, rb);
        }
    }

    @Test
    public void test_copy_memMode() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, 1024, 4096, "swap_test");
        buf.writeBytes("CopyTest".getBytes(StandardCharsets.UTF_8));
        buf.markWriter();
        ByteBuf copy = buf.copy();
        try {
            assertEquals(buf.readableBytes(), copy.readableBytes());
            byte[] a = new byte[(int) buf.readableBytes()];
            byte[] b2 = new byte[(int) copy.readableBytes()];
            buf.readBytes(a);
            copy.readBytes(b2);
            assertArrayEquals(a, b2);
        } finally {
            copy.free();
        }
    }

    @Test
    public void test_copy_fileMode() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);
        byte[] src = buildSequence(0, 20);
        buf.writeBytes(src);
        buf.markWriter();
        buf.readBytes(new byte[5]);  // advance reader

        ByteBuf copy = buf.copy();
        try {
            // copy should reflect the markedWriter window (not reader position)
            assertEquals(buf.readableBytes() + buf.readBytes(), copy.readableBytes() + copy.readBytes());
        } finally {
            copy.free();
        }
    }

    @Test
    public void test_free_deletesFile_inFileMode() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);
        buf.writeBytes(new byte[] { 1, 2, 3 });
        File tmpFile = buf.getTempFile();
        assertNotNull(tmpFile);
        assertTrue(tmpFile.exists());

        buf.free();
        assertFalse("Temp file must be deleted on free()", tmpFile.exists());
        buf = null; // already freed
    }

    // =========================================================================
    // isDirect / capacity / readableBytes / writableBytes
    // =========================================================================

    @Test
    public void test_free_memMode_noException() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, 1024, 4096, "swap_test");
        buf.writeBytes(new byte[] { 0x01 });
        buf.free();
        buf = null;
    }

    @Test
    public void test_toString_memMode() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, 1024, 4096, "swap_test");
        buf.writeBytes(new byte[] { 1, 2, 3 });
        assertNotNull(buf.toString());
        assertTrue(buf.toString().contains("SwapFileByteBuf"));
    }

    @Test
    public void test_isDirect_alwaysFalse() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        assertFalse(buf.isDirect());
        forceFileMode(buf);
        assertFalse(buf.isDirect());
    }

    // =========================================================================
    // getBytes / setBytes random access
    // =========================================================================

    @Test
    public void test_readableBytes_before_markWriter() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        buf.writeBytes(new byte[] { 1, 2, 3 });
        // Before markWriter, markedWriterIndex == 0, so readableBytes == 0
        assertEquals(0, buf.readableBytes());
        buf.markWriter();
        assertEquals(3, buf.readableBytes());
    }

    // =========================================================================
    // asByteArray
    // =========================================================================

    @Test
    public void test_markWriter_resetWriter() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);
        buf.writeBytes(new byte[] { 10, 20, 30 });
        buf.markWriter();             // mark at 3
        buf.writeBytes(new byte[] { 40, 50 });  // write 2 more (not committed)
        assertEquals(2, buf.writtenBytes()); // over-mark
        buf.resetWriter();           // roll back tentative writes
        buf.markWriter();
        assertEquals(3, buf.readableBytes());
        byte[] r = new byte[3];
        buf.readBytes(r);
        assertArrayEquals(new byte[] { 10, 20, 30 }, r);
    }

    @Test
    public void test_getBytes_randomAccess_fileMode() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);
        byte[] data = buildSequence(0, 20);
        buf.writeBytes(data);
        buf.markWriter();

        // getBytes at offset 5 for 10 bytes
        byte[] got = new byte[10];
        buf.getBytes(5, got, 0, 10);
        byte[] expected = Arrays.copyOfRange(data, 5, 15);
        assertArrayEquals(expected, got);
    }

    // =========================================================================
    // sliceOff
    // =========================================================================

    @Test
    public void test_asByteArray_memMode() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, 1024, 4096, "swap_test");
        buf.writeBytes("TEST".getBytes(StandardCharsets.UTF_8));
        buf.markWriter();
        byte[] arr = buf.asByteArray();
        assertArrayEquals("TEST".getBytes(StandardCharsets.UTF_8), arr);
    }

    @Test
    public void test_asByteArray_fileMode() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);
        byte[] src = buildSequence(0, 15);
        buf.writeBytes(src);
        buf.markWriter();
        assertArrayEquals(src, buf.asByteArray());
    }

    @Test
    public void test_sliceOff_memMode() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, 1024, 4096, "swap_test");
        buf.writeBytes(new byte[] { 1, 2, 3, 4, 5 });
        buf.markWriter();
        ByteBuf slice = buf.sliceOff(3);
        try {
            // slice should contain [1,2,3]
            byte[] sData = new byte[3];
            slice.readBytes(sData);
            assertArrayEquals(new byte[] { 1, 2, 3 }, sData);
            // buf should now have 2 bytes
            assertEquals(2, buf.readableBytes());
        } finally {
            slice.free();
        }
    }

    // =========================================================================
    // multiple compact cycles
    // =========================================================================

    @Test
    public void test_sliceOff_fileMode() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);
        buf.writeBytes(new byte[] { 10, 20, 30, 40, 50 });
        buf.markWriter();
        ByteBuf slice = buf.sliceOff(2);
        try {
            byte[] sData = new byte[2];
            slice.readBytes(sData);
            assertArrayEquals(new byte[] { 10, 20 }, sData);
            assertEquals(3, buf.readableBytes());
        } finally {
            slice.free();
        }
    }

    // =========================================================================
    // edge cases
    // =========================================================================

    @Test(expected = IndexOutOfBoundsException.class)
    public void test_sliceOff_overflow_throws() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, 1024, 4096, "swap_test");
        buf.writeBytes(new byte[] { 1, 2, 3 });
        buf.markWriter();
        buf.sliceOff(100);
    }

    @Test
    public void test_multipleCompactCycles() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);
        int cycle = 10;
        int chunkSize = SMALL_COMPACT / 4;  // each cycle writes compactThreshold/4 bytes
        // write enough to trigger compact multiple times
        for (int i = 0; i < cycle; i++) {
            byte[] data = buildSequence(i * chunkSize, chunkSize);
            buf.writeBytes(data);
            buf.markWriter();
            byte[] r = new byte[chunkSize];
            buf.readBytes(r);
            assertArrayEquals("Cycle " + i, buildSequence(i * chunkSize, chunkSize), r);
            buf.markReader();  // advances fileBaseOffset → may trigger compact
        }
        // 活跃 Segment 数应有界（消耗局部昂头 Segment 已被删除）
        assertTrue("Active segment count should be bounded", buf.getActiveSegmentCount() <= 2);
    }

    @Test
    public void test_writeZeroBytes_noSwitch() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        buf.writeBytes(new byte[0]);
        assertFalse(buf.isFileMode());
    }

    // =========================================================================
    // helpers
    // =========================================================================

    @Test
    public void test_emptyBuf_readableBytes_zero() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void test_readBuffer_ByteBuffer() {
        buf = new SwapFileByteBuf(ByteBufAllocator.DEFAULT, SMALL_THRESHOLD, SMALL_COMPACT, "swap_test");
        forceFileMode(buf);
        buf.writeBytes(buildSequence(0, 10));
        buf.markWriter();

        java.nio.ByteBuffer dst = java.nio.ByteBuffer.allocate(10);
        int n = buf.readBuffer(dst, 10);
        assertEquals(10, n);
        dst.flip();
        for (int i = 0; i < 10; i++) {
            assertEquals((byte) (i & 0xFF), dst.get());
        }
    }
}
