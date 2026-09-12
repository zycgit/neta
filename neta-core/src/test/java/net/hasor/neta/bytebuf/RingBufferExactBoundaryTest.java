/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;
import org.junit.Test;

/**
 * Tests ring buffer exact boundary conditions (offsetSize + len == maxCapacity).
 * These exercises the fast-path vs wrap-around decision in _putBytes and _getBytes.
 * The boundary fix changed all comparisons from (offsetSize + len) < maxCap
 * to (offsetSize + len) <= maxCap so that data ending exactly at ring boundary
 * uses the efficient single-copy fast path instead of splitting into two copies.
 * Covers both RingArrayByteBuf (heap) and RingByteBuffer (direct).
 */
public class RingBufferExactBoundaryTest {

    // -- Factory helpers --

    private ByteBuf ringHeapBuf(int capacity) {
        return ByteBufAllocator.DEFAULT.ringHeapBuffer(capacity);
    }

    private ByteBuf ringDirectBuf(int capacity) {
        return ByteBufAllocator.DEFAULT.ringDirectBuffer(capacity);
    }

    // ========================================================================
    // Case 1: Write fills buffer exactly to capacity (offset=0, len=cap)
    // This is the simplest exact boundary: offsetSize(0) + len(cap) == cap
    // ========================================================================

    @Test
    public void testExactFill_heap() {
        doExactFill(ringHeapBuf(8));
    }

    @Test
    public void testExactFill_direct() {
        doExactFill(ringDirectBuf(8));
    }

    private void doExactFill(ByteBuf buf) {
        byte[] data = { 1, 2, 3, 4, 5, 6, 7, 8 };
        buf.writeBytes(data);
        buf.markWriter();

        assert buf.readableBytes() == 8 : "should have 8 readable bytes";
        for (int i = 0; i < 8; i++) {
            assert buf.readByte() == (byte) (i + 1) : "byte at index " + i;
        }
        buf.free();
    }

    // ========================================================================
    // Case 2: Write ends exactly at boundary after partial fill
    // Write 3 bytes at offset 0, consume them, then write 8 bytes
    // The internal offset wraps: write starts at offset 3, 3+8=11 > 8 → wrap
    // But if we write 5 bytes: offset(3) + 5 = 8 == cap → exact boundary
    // ========================================================================

    @Test
    public void testExactBoundaryAfterPartialConsume_heap() {
        doExactBoundaryAfterPartialConsume(ringHeapBuf(8));
    }

    @Test
    public void testExactBoundaryAfterPartialConsume_direct() {
        doExactBoundaryAfterPartialConsume(ringDirectBuf(8));
    }

    private void doExactBoundaryAfterPartialConsume(ByteBuf buf) {
        // Write 3 bytes and consume them to advance the internal offset
        buf.writeBytes(new byte[] { 0x10, 0x20, 0x30 });
        buf.markWriter();

        buf.readByte();
        buf.readByte();
        buf.readByte();
        buf.markReader(); // reclaim space, internal offset now at 3

        // Now write exactly 5 bytes: offset(3) + 5 == 8 (cap) → exact boundary
        buf.writeBytes(new byte[] { 0x41, 0x42, 0x43, 0x44, 0x45 });
        buf.markWriter();

        assert buf.readableBytes() == 5 : "should have 5 readable";
        assert buf.readByte() == 0x41;
        assert buf.readByte() == 0x42;
        assert buf.readByte() == 0x43;
        assert buf.readByte() == 0x44;
        assert buf.readByte() == 0x45;
        buf.free();
    }

    // ========================================================================
    // Case 3: Write wraps around (offset + len > cap)
    // Write at offset 5 with 5 bytes: 5+5=10 > 8 → must split into 3+2
    // ========================================================================

    @Test
    public void testWrapAround_heap() {
        doWrapAround(ringHeapBuf(8));
    }

    @Test
    public void testWrapAround_direct() {
        doWrapAround(ringDirectBuf(8));
    }

    private void doWrapAround(ByteBuf buf) {
        // Advance internal offset to position 5
        buf.writeBytes(new byte[] { 1, 2, 3, 4, 5 });
        buf.markWriter();
        for (int i = 0; i < 5; i++) {
            buf.readByte();
        }
        buf.markReader(); // internal offset now at 5

        // Write 5 bytes: offset(5) + 5 = 10 > 8 → splits to partA=3, partB=2
        buf.writeBytes(new byte[] { 0x51, 0x52, 0x53, 0x54, 0x55 });
        buf.markWriter();

        assert buf.readableBytes() == 5;
        assert buf.readByte() == 0x51;
        assert buf.readByte() == 0x52;
        assert buf.readByte() == 0x53;
        assert buf.readByte() == 0x54;
        assert buf.readByte() == 0x55;
        buf.free();
    }

    // ========================================================================
    // Case 4: getBytes exact boundary — read ends exactly at cap
    // ========================================================================

    @Test
    public void testGetBytesExactBoundary_heap() {
        doGetBytesExactBoundary(ringHeapBuf(8));
    }

    @Test
    public void testGetBytesExactBoundary_direct() {
        doGetBytesExactBoundary(ringDirectBuf(8));
    }

    private void doGetBytesExactBoundary(ByteBuf buf) {
        // advance offset to 3
        buf.writeBytes(new byte[] { 0, 0, 0 });
        buf.markWriter();
        buf.readByte();
        buf.readByte();
        buf.readByte();
        buf.markReader();

        // write exactly 5 bytes starting at internal offset 3: 3+5==8
        buf.writeBytes(new byte[] { 10, 20, 30, 40, 50 });
        buf.markWriter();

        // getBytes (random access read) should also use the fast path
        byte[] dst = new byte[5];
        buf.getBytes(0, dst);
        assert dst[0] == 10;
        assert dst[1] == 20;
        assert dst[2] == 30;
        assert dst[3] == 40;
        assert dst[4] == 50;
        buf.free();
    }

    // ========================================================================
    // Case 5: getBytes wrap around — read spans the ring boundary
    // ========================================================================

    @Test
    public void testGetBytesWrapAround_heap() {
        doGetBytesWrapAround(ringHeapBuf(8));
    }

    @Test
    public void testGetBytesWrapAround_direct() {
        doGetBytesWrapAround(ringDirectBuf(8));
    }

    private void doGetBytesWrapAround(ByteBuf buf) {
        // advance offset to 6
        buf.writeBytes(new byte[] { 0, 0, 0, 0, 0, 0 });
        buf.markWriter();
        for (int i = 0; i < 6; i++)
            buf.readByte();
        buf.markReader();

        // write 5 bytes at internal offset 6: 6+5=11 > 8 → wraps
        buf.writeBytes(new byte[] { 0x61, 0x62, 0x63, 0x64, 0x65 });
        buf.markWriter();

        byte[] dst = new byte[5];
        buf.getBytes(0, dst);
        assert dst[0] == 0x61 : "wrap-around byte 0";
        assert dst[1] == 0x62 : "wrap-around byte 1";
        assert dst[2] == 0x63 : "wrap-around byte 2";
        assert dst[3] == 0x64 : "wrap-around byte 3";
        assert dst[4] == 0x65 : "wrap-around byte 4";
        buf.free();
    }

    // ========================================================================
    // Case 6: ByteBuffer source exact boundary
    // ========================================================================

    @Test
    public void testPutByteBufferExactBoundary_heap() {
        doPutByteBufferExactBoundary(ringHeapBuf(8));
    }

    @Test
    public void testPutByteBufferExactBoundary_direct() {
        doPutByteBufferExactBoundary(ringDirectBuf(8));
    }

    private void doPutByteBufferExactBoundary(ByteBuf buf) {
        // advance offset to 4
        buf.writeBytes(new byte[] { 0, 0, 0, 0 });
        buf.markWriter();
        for (int i = 0; i < 4; i++)
            buf.readByte();
        buf.markReader();

        // write exactly 4 bytes via ByteBuffer: offset(4) + 4 == 8
        ByteBuffer src = ByteBuffer.wrap(new byte[] { 0x71, 0x72, 0x73, 0x74 });
        buf.writeBuffer(src);
        buf.markWriter();

        assert buf.readableBytes() == 4;
        assert buf.readByte() == 0x71;
        assert buf.readByte() == 0x72;
        assert buf.readByte() == 0x73;
        assert buf.readByte() == 0x74;
        buf.free();
    }

    // ========================================================================
    // Case 7: ByteBuf source exact boundary
    // ========================================================================

    @Test
    public void testPutByteBufExactBoundary_heap() {
        doPutByteBufExactBoundary(ringHeapBuf(8));
    }

    @Test
    public void testPutByteBufExactBoundary_direct() {
        doPutByteBufExactBoundary(ringDirectBuf(8));
    }

    private void doPutByteBufExactBoundary(ByteBuf buf) {
        // advance offset to 6
        buf.writeBytes(new byte[] { 0, 0, 0, 0, 0, 0 });
        buf.markWriter();
        for (int i = 0; i < 6; i++)
            buf.readByte();
        buf.markReader();

        // write exactly 2 bytes via ByteBuf: offset(6) + 2 == 8
        ByteBuf src = ByteBuf.wrap(new byte[] { (byte) 0xAA, (byte) 0xBB });
        buf.writeBuffer(src);
        buf.markWriter();

        assert buf.readableBytes() == 2;
        assert buf.readByte() == (byte) 0xAA;
        assert buf.readByte() == (byte) 0xBB;
        src.free();
        buf.free();
    }

    // ========================================================================
    // Case 8: getBytes into ByteBuffer exact boundary
    // ========================================================================

    @Test
    public void testGetBytesIntoByteBufferExact_heap() {
        doGetBytesIntoByteBufferExact(ringHeapBuf(8));
    }

    @Test
    public void testGetBytesIntoByteBufferExact_direct() {
        doGetBytesIntoByteBufferExact(ringDirectBuf(8));
    }

    private void doGetBytesIntoByteBufferExact(ByteBuf buf) {
        // advance offset to 2
        buf.writeBytes(new byte[] { 0, 0 });
        buf.markWriter();
        buf.readByte();
        buf.readByte();
        buf.markReader();

        // write exactly 6 bytes: offset(2) + 6 == 8
        buf.writeBytes(new byte[] { 11, 22, 33, 44, 55, 66 });
        buf.markWriter();

        ByteBuffer dst = ByteBuffer.allocate(6);
        buf.readBuffer(dst);
        dst.flip();
        assert dst.get() == 11;
        assert dst.get() == 22;
        assert dst.get() == 33;
        assert dst.get() == 44;
        assert dst.get() == 55;
        assert dst.get() == 66;
        buf.free();
    }

    // ========================================================================
    // Case 9: getBytes into ByteBuf exact boundary
    // ========================================================================

    @Test
    public void testGetBytesIntoByteBufExact_heap() {
        doGetBytesIntoByteBufExact(ringHeapBuf(8));
    }

    @Test
    public void testGetBytesIntoByteBufExact_direct() {
        doGetBytesIntoByteBufExact(ringDirectBuf(8));
    }

    private void doGetBytesIntoByteBufExact(ByteBuf buf) {
        // advance offset to 7
        buf.writeBytes(new byte[] { 0, 0, 0, 0, 0, 0, 0 });
        buf.markWriter();
        for (int i = 0; i < 7; i++)
            buf.readByte();
        buf.markReader();

        // write exactly 1 byte: offset(7) + 1 == 8
        buf.writeBytes(new byte[] { (byte) 0xFF });
        buf.markWriter();

        ByteBuf dst = ByteBufAllocator.DEFAULT.heapBuffer(4);
        buf.readBuffer(dst, 1);
        dst.markWriter();
        assert dst.readByte() == (byte) 0xFF;
        dst.free();
        buf.free();
    }

    // ========================================================================
    // Case 10: Multi-type int reads spanning ring wrap-around
    // Verify that readInt16/32/64 work correctly across the ring boundary
    // ========================================================================

    @Test
    public void testInt32AcrossWrapBoundary_heap() {
        ByteBuf buf = ringHeapBuf(8);
        doInt32AcrossWrapBoundary(buf);
    }

    @Test
    public void testInt32AcrossWrapBoundary_direct() {
        ByteBuf buf = ringDirectBuf(8);
        doInt32AcrossWrapBoundary(buf);
    }

    private void doInt32AcrossWrapBoundary(ByteBuf buf) {
        // advance offset to 6
        buf.writeBytes(new byte[] { 0, 0, 0, 0, 0, 0 });
        buf.markWriter();
        for (int i = 0; i < 6; i++)
            buf.readByte();
        buf.markReader();

        // write 4-byte int at internal offset 6: bytes at [6,7,0,1] → crosses boundary
        buf.writeInt32(0x01020304);
        buf.markWriter();

        assert buf.readInt32() == 0x01020304 : "readInt32 across ring boundary";
        buf.free();
    }

    @Test
    public void testInt16AcrossWrapBoundary_heap() {
        ByteBuf buf = ringHeapBuf(8);
        // advance offset to 7
        buf.writeBytes(new byte[] { 0, 0, 0, 0, 0, 0, 0 });
        buf.markWriter();
        for (int i = 0; i < 7; i++)
            buf.readByte();
        buf.markReader();

        // write 2-byte int at offset 7: bytes at [7,0] → crosses boundary
        buf.writeInt16((short) 0x0A0B);
        buf.markWriter();

        assert buf.readInt16() == 0x0A0B : "readInt16 across ring boundary";
        buf.free();
    }

    // ========================================================================
    // Case 11: Ring buffer wrap-around with float32
    // ========================================================================

    @Test
    public void testFloat32AcrossWrapBoundary_heap() {
        ByteBuf buf = ringHeapBuf(8);
        // advance to offset 5
        buf.writeBytes(new byte[] { 0, 0, 0, 0, 0 });
        buf.markWriter();
        for (int i = 0; i < 5; i++)
            buf.readByte();
        buf.markReader();

        // write float at offset 5: bytes at [5,6,7,0] → crosses boundary
        buf.writeFloat32(3.14f);
        buf.markWriter();
        assert buf.readFloat32() == 3.14f : "float32 across ring boundary";
        buf.free();
    }
}
