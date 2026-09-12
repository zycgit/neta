/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import org.junit.Test;

/**
 * Tests for RingArrayByteBuf and RingByteBuffer focusing on ring-buffer wrap-around behavior,
 * continuous write-read cycles, sliceOff, copy, and index reset via markReader/updateIndex.
 */
public class RingBufferWrapAroundTest {

    // ========================================================================
    // RingArrayByteBuf wrap-around tests
    // ========================================================================

    @Test
    public void ringArray_wrapAround_basicCycle() {
        // capacity=8, write 8 bytes, read all, write 8 more (wrap around), read all
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(8);

        // First fill
        byte[] data1 = { 1, 2, 3, 4, 5, 6, 7, 8 };
        ring.writeBytes(data1);
        ring.markWriter();

        byte[] readBack = new byte[8];
        ring.readBytes(readBack);
        for (int i = 0; i < 8; i++) {
            assert readBack[i] == data1[i] : "first read mismatch at " + i;
        }
        ring.markReader(); // triggers updateIndex

        // Second fill (wrap around)
        byte[] data2 = { 11, 12, 13, 14, 15, 16, 17, 18 };
        ring.writeBytes(data2);
        ring.markWriter();

        byte[] readBack2 = new byte[8];
        ring.readBytes(readBack2);
        for (int i = 0; i < 8; i++) {
            assert readBack2[i] == data2[i] : "wrap-around read mismatch at " + i;
        }

        ring.free();
    }

    @Test
    public void ringArray_wrapAround_partialReadWrite() {
        // capacity=8, write 5, read 3, mark, write 5, read 5, etc.
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(8);

        // Write 5 bytes
        ring.writeBytes(new byte[] { 1, 2, 3, 4, 5 });
        ring.markWriter();

        // Read 3
        assert ring.readByte() == 1;
        assert ring.readByte() == 2;
        assert ring.readByte() == 3;
        ring.markReader(); // discards first 3, now capacity wraps

        // Write 5 more (3 already freed + 3 remaining unused = 6 writable, need 5)
        ring.writeBytes(new byte[] { 6, 7, 8, 9, 10 });
        ring.markWriter();

        // Read all 7 remaining: 4,5,6,7,8,9,10
        assert ring.readByte() == 4;
        assert ring.readByte() == 5;
        assert ring.readByte() == 6;
        assert ring.readByte() == 7;
        assert ring.readByte() == 8;
        assert ring.readByte() == 9;
        assert ring.readByte() == 10;

        ring.free();
    }

    @Test
    public void ringArray_multipleWrapAroundCycles() {
        // Stress the wrap-around by doing many cycles
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(4);

        for (int cycle = 0; cycle < 100; cycle++) {
            byte b = (byte) (cycle & 0xFF);
            ring.writeBytes(new byte[] { b, (byte) (b + 1), (byte) (b + 2), (byte) (b + 3) });
            ring.markWriter();

            assert ring.readByte() == b;
            assert ring.readByte() == (byte) (b + 1);
            assert ring.readByte() == (byte) (b + 2);
            assert ring.readByte() == (byte) (b + 3);
            ring.markReader();
        }

        ring.free();
    }

    @Test
    public void ringArray_fullCapacityOverflow() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(4);
        ring.writeBytes(new byte[] { 1, 2, 3, 4 });

        try {
            ring.writeByte((byte) 5);
            assert false : "should throw BufferOverflowException";
        } catch (BufferOverflowException e) {
            // expected
        }

        ring.free();
    }

    @Test
    public void ringArray_sliceOff_afterWrapAround() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(8);

        // Write 8, read 4, mark, write 4 more (causes wrap)
        ring.writeBytes(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        ring.markWriter();
        ring.readBytes(new byte[4]); // read 4
        ring.markReader();

        ring.writeBytes(new byte[] { 9, 10, 11, 12 });
        ring.markWriter();

        // Now readable: 5,6,7,8,9,10,11,12 — the latter wraps around in the buffer
        // sliceOff first 4
        ByteBuf sliced = ring.sliceOff(4);
        assert sliced instanceof WrapByteBuffer;
        assert sliced.readByte() == 5;
        assert sliced.readByte() == 6;
        assert sliced.readByte() == 7;
        assert sliced.readByte() == 8;
        sliced.free();

        // Remaining should be 9,10,11,12
        assert ring.readByte() == 9;
        assert ring.readByte() == 10;
        assert ring.readByte() == 11;
        assert ring.readByte() == 12;

        ring.free();
    }

    @Test
    public void ringArray_copy_preservesData() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(8);
        ring.writeBytes(new byte[] { 10, 20, 30, 40 });
        ring.markWriter();
        ring.readBytes(new byte[2]); // read 2

        ByteBuf copy = ring.copy();
        assert copy instanceof RingArrayByteBuf;
        // copy preserves the full buffer state including readable portion
        assert copy.readByte() == 30;
        assert copy.readByte() == 40;

        copy.free();
        ring.free();
    }

    @Test
    public void ringArray_discardReadBytes_throwsUnsupported() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(8);
        ring.writeBytes(new byte[] { 1 });
        ring.markWriter();

        try {
            ring.discardReadBytes();
            assert false : "should throw UnsupportedOperationException";
        } catch (UnsupportedOperationException e) {
            // expected
        }

        ring.free();
    }

    @Test
    public void ringArray_sliceOff_zero_returnsEmpty() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(8);

        ByteBuf sliced = ring.sliceOff(0);
        assert sliced == ByteBuf.EMPTY;

        ring.free();
    }

    @Test
    public void ringArray_sliceOff_invalidOffset_throwsIOOBE() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(8);

        try {
            ring.sliceOff(-1);
            assert false : "should throw IndexOutOfBoundsException for negative";
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        try {
            ring.sliceOff(9); // > capacity of 8
            assert false : "should throw IndexOutOfBoundsException for > capacity";
        } catch (IndexOutOfBoundsException e) {
            // expected
        }

        ring.free();
    }

    // ========================================================================
    // RingByteBuffer (direct & heap) wrap-around tests
    // ========================================================================

    @Test
    public void ringDirect_wrapAround_basicCycle() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringDirectBuffer(8);
        assert ring.isDirect();

        byte[] data1 = { 1, 2, 3, 4, 5, 6, 7, 8 };
        ring.writeBytes(data1);
        ring.markWriter();

        byte[] readBack = new byte[8];
        ring.readBytes(readBack);
        for (int i = 0; i < 8; i++) {
            assert readBack[i] == data1[i] : "first read mismatch at " + i;
        }
        ring.markReader();

        byte[] data2 = { 21, 22, 23, 24, 25, 26, 27, 28 };
        ring.writeBytes(data2);
        ring.markWriter();

        byte[] readBack2 = new byte[8];
        ring.readBytes(readBack2);
        for (int i = 0; i < 8; i++) {
            assert readBack2[i] == data2[i] : "wrap-around read mismatch at " + i;
        }

        ring.free();
    }

    @Test
    public void ringDirect_multipleWrapAroundCycles() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringDirectBuffer(4);

        for (int cycle = 0; cycle < 50; cycle++) {
            byte b = (byte) (cycle & 0xFF);
            ring.writeBytes(new byte[] { b, (byte) (b + 1), (byte) (b + 2), (byte) (b + 3) });
            ring.markWriter();

            assert ring.readByte() == b;
            assert ring.readByte() == (byte) (b + 1);
            assert ring.readByte() == (byte) (b + 2);
            assert ring.readByte() == (byte) (b + 3);
            ring.markReader();
        }

        ring.free();
    }

    @Test
    public void ringDirect_sliceOff_returnsDirect() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringDirectBuffer(8);
        ring.writeBytes(new byte[] { 1, 2, 3, 4 });
        ring.markWriter();

        ByteBuf sliced = ring.sliceOff(2);
        assert sliced instanceof WrapByteBuffer;
        assert sliced.isDirect() : "sliceOff from direct ring should return direct";
        assert sliced.readByte() == 1;
        assert sliced.readByte() == 2;

        sliced.free();
        ring.free();
    }

    @Test
    public void ringHeap_sliceOff_returnsHeap() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(8);
        ring.writeBytes(new byte[] { 1, 2, 3, 4 });
        ring.markWriter();

        ByteBuf sliced = ring.sliceOff(2);
        assert sliced instanceof WrapByteBuffer;
        assert !sliced.isDirect() : "sliceOff from heap ring should return heap";
        assert sliced.readByte() == 1;
        assert sliced.readByte() == 2;

        sliced.free();
        ring.free();
    }

    @Test
    public void ringDirect_discardReadBytes_throwsUnsupported() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringDirectBuffer(8);
        ring.writeBytes(new byte[] { 1 });
        ring.markWriter();

        try {
            ring.discardReadBytes();
            assert false : "should throw UnsupportedOperationException";
        } catch (UnsupportedOperationException e) {
            // expected
        }

        ring.free();
    }

    @Test
    public void ringDirect_copy_preservesData() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringDirectBuffer(8);
        ring.writeBytes(new byte[] { 10, 20, 30, 40 });
        ring.markWriter();
        ring.readBytes(new byte[2]); // read 2

        ByteBuf copy = ring.copy();
        assert copy instanceof RingByteBuffer;
        assert copy.readByte() == 30;
        assert copy.readByte() == 40;

        copy.free();
        ring.free();
    }

    // ========================================================================
    // RingBuffer with ByteBuffer/ByteBuf write sources
    // ========================================================================

    @Test
    public void ringArray_writeFromByteBuffer() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(16);
        ByteBuffer src = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });

        ring.writeBuffer(src, 8);
        ring.markWriter();

        byte[] result = new byte[8];
        ring.readBytes(result);
        for (int i = 0; i < 8; i++) {
            assert result[i] == (byte) (i + 1) : "mismatch at " + i;
        }

        ring.free();
    }

    @Test
    public void ringArray_writeFromByteBuf() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(16);
        ByteBuf src = ByteBufAllocator.DEFAULT.heapBuffer(16);
        src.writeBytes(new byte[] { 10, 20, 30, 40 });
        src.markWriter();

        ring.writeBuffer(src, 4);
        ring.markWriter();

        assert ring.readByte() == 10;
        assert ring.readByte() == 20;
        assert ring.readByte() == 30;
        assert ring.readByte() == 40;

        src.free();
        ring.free();
    }

    @Test
    public void ringArray_readIntoByteBuffer() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(16);
        ring.writeBytes(new byte[] { 5, 10, 15, 20 });
        ring.markWriter();

        ByteBuffer dst = ByteBuffer.allocate(4);
        ring.readBuffer(dst, 4);

        dst.flip();
        assert dst.get() == 5;
        assert dst.get() == 10;
        assert dst.get() == 15;
        assert dst.get() == 20;

        ring.free();
    }

    @Test
    public void ringArray_readIntoByteBuf() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(16);
        ring.writeBytes(new byte[] { 5, 10, 15, 20 });
        ring.markWriter();

        ByteBuf dst = ByteBufAllocator.DEFAULT.heapBuffer(16);
        ring.readBuffer(dst, 4);
        dst.markWriter();

        assert dst.readByte() == 5;
        assert dst.readByte() == 10;
        assert dst.readByte() == 15;
        assert dst.readByte() == 20;

        dst.free();
        ring.free();
    }

    // ========================================================================
    // Multi-byte type write/read with ring wrap-around
    // ========================================================================

    @Test
    public void ringArray_int16_wrapAround() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(8);

        // Write 4 shorts (8 bytes = full capacity)
        ring.writeInt16((short) 100);
        ring.writeInt16((short) 200);
        ring.writeInt16((short) 300);
        ring.writeInt16((short) 400);
        ring.markWriter();

        assert ring.readInt16() == 100;
        assert ring.readInt16() == 200;
        assert ring.readInt16() == 300;
        assert ring.readInt16() == 400;
        ring.markReader();

        // Write again (wraps around)
        ring.writeInt16((short) 500);
        ring.writeInt16((short) 600);
        ring.markWriter();

        assert ring.readInt16() == 500;
        assert ring.readInt16() == 600;

        ring.free();
    }

    @Test
    public void ringArray_int32_wrapAround() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(8);

        ring.writeInt32(12345678);
        ring.writeInt32(87654321);
        ring.markWriter();

        assert ring.readInt32() == 12345678;
        assert ring.readInt32() == 87654321;
        ring.markReader();

        // Wrap around
        ring.writeInt32(11111111);
        ring.writeInt32(22222222);
        ring.markWriter();

        assert ring.readInt32() == 11111111;
        assert ring.readInt32() == 22222222;

        ring.free();
    }

    @Test
    public void ringArray_int64_wrapAround() {
        ByteBuf ring = ByteBufAllocator.DEFAULT.ringHeapBuffer(16);

        ring.writeInt64(Long.MAX_VALUE);
        ring.writeInt64(Long.MIN_VALUE);
        ring.markWriter();

        assert ring.readInt64() == Long.MAX_VALUE;
        assert ring.readInt64() == Long.MIN_VALUE;
        ring.markReader();

        ring.writeInt64(0L);
        ring.markWriter();
        assert ring.readInt64() == 0L;

        ring.free();
    }
}
