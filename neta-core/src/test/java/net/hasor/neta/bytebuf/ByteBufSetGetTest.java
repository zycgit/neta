/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

/**
 * Tests set/get operations (random access write/read without index advancement)
 * across all major ByteBuf implementations: heap, direct, pooled.
 * API semantics:
 * - set*(offset, val): writes at markedWriterIndex + offset — into the WRITABLE area
 * - get*(offset):      reads  at readerIndex + offset        — from the READABLE area
 * To do a set/get round-trip:
 * 1. set*() to fill writable area
 * 2. skipWritableBytes(n) + markWriter() to commit (make readable)
 * 3. get*() to read back
 * Also covers: readUInt8/16/24/32, readInt24/writeInt24, order(ByteOrder),
 * readLine(), multiple consecutive sliceOff.
 */
public class ByteBufSetGetTest {

    // -- Helper factory methods --

    private ByteBuf heapBuf(int size) {
        return ByteBufAllocator.DEFAULT.heapBuffer(size);
    }

    private ByteBuf directBuf(int size) {
        return ByteBufAllocator.DEFAULT.directBuffer(size);
    }

    private ByteBuf pooledBuf(int size) {
        return ByteBufAllocator.DEFAULT.pooledBuffer(size);
    }

    // ========================================================================
    // setByte + getByte round-trip for each buffer type
    // Pattern: set into writable area → commit → get from readable area
    // ========================================================================

    @Test
    public void testSetGetByte_heap() {
        doSetGetByte(heapBuf(16));
    }

    @Test
    public void testSetGetByte_direct() {
        doSetGetByte(directBuf(16));
    }

    @Test
    public void testSetGetByte_pooled() {
        doSetGetByte(pooledBuf(16));
    }

    private void doSetGetByte(ByteBuf buf) {
        // set into writable area (markedWriterIndex = 0)
        buf.setByte(0, (byte) 0x41);
        buf.setByte(3, (byte) 0x42);
        buf.setByte(7, (byte) 0x43);
        buf.skipWritableBytes(8);
        buf.markWriter(); // commit 8 bytes as readable

        assert buf.getByte(0) == 0x41 : "setByte/getByte at offset 0";
        assert buf.getByte(3) == 0x42 : "setByte/getByte at offset 3";
        assert buf.getByte(7) == 0x43 : "setByte/getByte at offset 7";
        buf.free();
    }

    // ========================================================================
    // setBytes + getBytes round-trip
    // ========================================================================

    @Test
    public void testSetGetBytes_heap() {
        doSetGetBytes(heapBuf(16));
    }

    @Test
    public void testSetGetBytes_direct() {
        doSetGetBytes(directBuf(16));
    }

    @Test
    public void testSetGetBytes_pooled() {
        doSetGetBytes(pooledBuf(16));
    }

    private void doSetGetBytes(ByteBuf buf) {
        byte[] src = { 10, 20, 30, 40 };
        buf.setBytes(0, src);
        buf.skipWritableBytes(4);
        buf.markWriter();

        byte[] dst = new byte[4];
        buf.getBytes(0, dst);
        assert dst[0] == 10;
        assert dst[1] == 20;
        assert dst[2] == 30;
        assert dst[3] == 40;
        buf.free();
    }

    // ========================================================================
    // setBytes with src offset + getBytes with dst offset
    // ========================================================================

    @Test
    public void testSetGetBytesWithOffset_heap() {
        doSetGetBytesWithOffset(heapBuf(16));
    }

    @Test
    public void testSetGetBytesWithOffset_direct() {
        doSetGetBytesWithOffset(directBuf(16));
    }

    private void doSetGetBytesWithOffset(ByteBuf buf) {
        byte[] src = { 1, 2, 3, 4, 5 };
        buf.setBytes(0, src, 2, 3); // writes {3, 4, 5} starting at writable offset 0
        buf.skipWritableBytes(3);
        buf.markWriter();

        byte[] dst = new byte[5];
        buf.getBytes(0, dst, 1, 3); // reads 3 bytes into dst at position 1
        assert dst[0] == 0 : "dst[0] untouched";
        assert dst[1] == 3 : "dst[1] should be 3";
        assert dst[2] == 4 : "dst[2] should be 4";
        assert dst[3] == 5 : "dst[3] should be 5";
        assert dst[4] == 0 : "dst[4] untouched";
        buf.free();
    }

    // ========================================================================
    // setInt16 + getInt16 round-trip
    // ========================================================================

    @Test
    public void testSetGetInt16_heap() {
        doSetGetInt16(heapBuf(16));
    }

    @Test
    public void testSetGetInt16_direct() {
        doSetGetInt16(directBuf(16));
    }

    private void doSetGetInt16(ByteBuf buf) {
        buf.setInt16(0, (short) 0x1234);
        buf.setInt16(2, (short) -1);
        buf.skipWritableBytes(4);
        buf.markWriter();

        assert buf.getInt16(0) == 0x1234 : "setInt16/getInt16 at offset 0";
        assert buf.getInt16(2) == -1 : "setInt16/getInt16 negative value";
        buf.free();
    }

    // ========================================================================
    // setInt32 + getInt32 round-trip
    // ========================================================================

    @Test
    public void testSetGetInt32_heap() {
        doSetGetInt32(heapBuf(16));
    }

    @Test
    public void testSetGetInt32_direct() {
        doSetGetInt32(directBuf(16));
    }

    @Test
    public void testSetGetInt32_pooled() {
        doSetGetInt32(pooledBuf(16));
    }

    private void doSetGetInt32(ByteBuf buf) {
        buf.setInt32(0, Integer.MAX_VALUE);
        buf.setInt32(4, Integer.MIN_VALUE);
        buf.skipWritableBytes(8);
        buf.markWriter();

        assert buf.getInt32(0) == Integer.MAX_VALUE;
        assert buf.getInt32(4) == Integer.MIN_VALUE;
        buf.free();
    }

    // ========================================================================
    // setInt64 + getInt64 round-trip
    // ========================================================================

    @Test
    public void testSetGetInt64_heap() {
        doSetGetInt64(heapBuf(32));
    }

    @Test
    public void testSetGetInt64_direct() {
        doSetGetInt64(directBuf(32));
    }

    private void doSetGetInt64(ByteBuf buf) {
        buf.setInt64(0, Long.MAX_VALUE);
        buf.setInt64(8, Long.MIN_VALUE);
        buf.skipWritableBytes(16);
        buf.markWriter();

        assert buf.getInt64(0) == Long.MAX_VALUE;
        assert buf.getInt64(8) == Long.MIN_VALUE;
        buf.free();
    }

    // ========================================================================
    // setFloat32/64 + getFloat32/64 round-trip
    // ========================================================================

    @Test
    public void testSetGetFloat32_heap() {
        doSetGetFloat32(heapBuf(16));
    }

    @Test
    public void testSetGetFloat32_direct() {
        doSetGetFloat32(directBuf(16));
    }

    private void doSetGetFloat32(ByteBuf buf) {
        buf.setFloat32(0, 3.14f);
        buf.setFloat32(4, Float.MAX_VALUE);
        buf.skipWritableBytes(8);
        buf.markWriter();

        assert buf.getFloat32(0) == 3.14f;
        assert buf.getFloat32(4) == Float.MAX_VALUE;
        buf.free();
    }

    @Test
    public void testSetGetFloat64_heap() {
        doSetGetFloat64(heapBuf(32));
    }

    @Test
    public void testSetGetFloat64_direct() {
        doSetGetFloat64(directBuf(32));
    }

    private void doSetGetFloat64(ByteBuf buf) {
        buf.setFloat64(0, Math.PI);
        buf.setFloat64(8, Double.MAX_VALUE);
        buf.skipWritableBytes(16);
        buf.markWriter();

        assert buf.getFloat64(0) == Math.PI;
        assert buf.getFloat64(8) == Double.MAX_VALUE;
        buf.free();
    }

    // ========================================================================
    // setInt24 + getInt24 round-trip
    // ========================================================================

    @Test
    public void testSetGetInt24_heap() {
        doSetGetInt24(heapBuf(16));
    }

    @Test
    public void testSetGetInt24_direct() {
        doSetGetInt24(directBuf(16));
    }

    private void doSetGetInt24(ByteBuf buf) {
        buf.setInt24(0, 0x123456);
        buf.setInt24(3, 0x7FFFFF); // max positive int24
        buf.skipWritableBytes(6);
        buf.markWriter();

        assert buf.getInt24(0) == 0x123456;
        assert buf.getInt24(3) == 0x7FFFFF;
        buf.free();
    }

    // ========================================================================
    // setBuffer (ByteBuffer) + getBuffer (ByteBuffer) round-trip
    // ========================================================================

    @Test
    public void testSetGetBuffer_ByteBuffer_heap() {
        doSetGetBuffer_ByteBuffer(heapBuf(16));
    }

    @Test
    public void testSetGetBuffer_ByteBuffer_direct() {
        doSetGetBuffer_ByteBuffer(directBuf(16));
    }

    private void doSetGetBuffer_ByteBuffer(ByteBuf buf) {
        ByteBuffer src = ByteBuffer.wrap(new byte[] { 10, 20, 30, 40 });
        buf.setBuffer(0, src, 4);
        buf.skipWritableBytes(4);
        buf.markWriter();

        ByteBuffer dst = ByteBuffer.allocate(4);
        buf.getBuffer(0, dst, 4);
        dst.flip();
        assert dst.get() == 10;
        assert dst.get() == 20;
        assert dst.get() == 30;
        assert dst.get() == 40;
        buf.free();
    }

    // ========================================================================
    // setBuffer (ByteBuf) + getBuffer (ByteBuf) round-trip
    // ========================================================================

    @Test
    public void testSetGetBuffer_ByteBuf_heap() {
        doSetGetBuffer_ByteBuf(heapBuf(16));
    }

    @Test
    public void testSetGetBuffer_ByteBuf_direct() {
        doSetGetBuffer_ByteBuf(directBuf(16));
    }

    private void doSetGetBuffer_ByteBuf(ByteBuf buf) {
        ByteBuf src = ByteBuf.wrap(new byte[] { 0x11, 0x22, 0x33 });
        buf.setBuffer(0, src, 3);
        buf.skipWritableBytes(3);
        buf.markWriter();

        ByteBuf dst = heapBuf(4);
        buf.getBuffer(0, dst, 3);
        dst.markWriter();
        assert dst.readByte() == 0x11;
        assert dst.readByte() == 0x22;
        assert dst.readByte() == 0x33;
        src.free();
        dst.free();
        buf.free();
    }

    // ========================================================================
    // set after partial read — set into writable region that follows readable
    // ========================================================================

    @Test
    public void testSetAfterPartialRead_heap() {
        ByteBuf buf = heapBuf(16);
        // write initial data and commit
        buf.writeBytes(new byte[] { 1, 2, 3, 4 });
        buf.markWriter();

        // read 2 bytes → readerIndex=2, readable=[2..4)
        buf.readByte();
        buf.readByte();

        // set into writable area (after markedWriterIndex=4)
        buf.setByte(0, (byte) 0xAA); // writes at markedWriterIndex+0 = position 4
        buf.setByte(1, (byte) 0xBB); // writes at markedWriterIndex+1 = position 5
        buf.skipWritableBytes(2);
        buf.markWriter(); // markedWriterIndex=6

        // now readable = [2..6), getByte(0) starts at readerIndex=2
        assert buf.getByte(0) == 3 : "original data at readable offset 0";
        assert buf.getByte(1) == 4 : "original data at readable offset 1";
        assert buf.getByte(2) == (byte) 0xAA : "set data at readable offset 2";
        assert buf.getByte(3) == (byte) 0xBB : "set data at readable offset 3";
        buf.free();
    }

    // ========================================================================
    // readInt24 / writeInt24 sequential round-trip
    // ========================================================================

    @Test
    public void testWriteReadInt24_heap() {
        doWriteReadInt24(heapBuf(16));
    }

    @Test
    public void testWriteReadInt24_direct() {
        doWriteReadInt24(directBuf(16));
    }

    @Test
    public void testWriteReadInt24_pooled() {
        doWriteReadInt24(pooledBuf(16));
    }

    private void doWriteReadInt24(ByteBuf buf) {
        buf.writeInt24(0x123456);
        buf.writeInt24(0);
        buf.writeInt24(0x7FFFFF);
        buf.markWriter();

        assert buf.readInt24() == 0x123456;
        assert buf.readInt24() == 0;
        assert buf.readInt24() == 0x7FFFFF;
        buf.free();
    }

    // ========================================================================
    // readUInt8/16/24/32 (unsigned reads)
    // ========================================================================

    @Test
    public void testReadUnsigned_heap() {
        doReadUnsigned(heapBuf(32));
    }

    @Test
    public void testReadUnsigned_direct() {
        doReadUnsigned(directBuf(32));
    }

    @Test
    public void testReadUnsigned_pooled() {
        doReadUnsigned(pooledBuf(32));
    }

    private void doReadUnsigned(ByteBuf buf) {
        // readUInt8: 0xFF → 255
        buf.writeByte((byte) 0xFF);
        // readUInt16: 0xFFFF → 65535
        buf.writeInt16((short) 0xFFFF);
        // readUInt24: 0xFFFFFF → 16777215
        buf.writeInt24(0xFFFFFF);
        // readUInt32: 0xFFFFFFFF → 4294967295
        buf.writeInt32(0xFFFFFFFF);
        buf.markWriter();

        assert buf.readUInt8() == 255 : "readUInt8 of 0xFF should be 255";
        assert buf.readUInt16() == 65535 : "readUInt16 of 0xFFFF should be 65535";
        assert buf.readUInt24() == 16777215 : "readUInt24 of 0xFFFFFF";
        assert buf.readUInt32() == 4294967295L : "readUInt32 of 0xFFFFFFFF";
        buf.free();
    }

    // ========================================================================
    // getUInt8/16/24 (unsigned get — positional)
    // ========================================================================

    @Test
    public void testGetUnsigned_heap() {
        doGetUnsigned(heapBuf(32));
    }

    @Test
    public void testGetUnsigned_direct() {
        doGetUnsigned(directBuf(32));
    }

    private void doGetUnsigned(ByteBuf buf) {
        buf.writeByte((byte) 0x80);       // offset 0: signed=-128, unsigned=128
        buf.writeInt16((short) 0x8000);   // offset 1: signed=-32768, unsigned=32768
        buf.writeInt24(0x800000);         // offset 3: unsigned=8388608
        buf.markWriter();

        assert buf.getUInt8(0) == 128 : "getUInt8 of 0x80";
        assert buf.getUInt16(1) == 32768 : "getUInt16 of 0x8000";
        assert buf.getUInt24(3) == 8388608 : "getUInt24 of 0x800000";

        // signed reads give negative
        assert buf.getByte(0) == -128 : "getByte of 0x80 should be -128";
        assert buf.getInt16(1) == -32768 : "getInt16 of 0x8000 should be -32768";
        buf.free();
    }

    // ========================================================================
    // order() / order(ByteOrder) tests
    // ========================================================================

    @Test
    public void testByteOrder_default() {
        ByteBuf buf = heapBuf(16);
        assert buf.order() == ByteOrder.BIG_ENDIAN : "Default order should be BIG_ENDIAN";
        buf.free();
    }

    @Test
    public void testByteOrder_switchToLE() {
        ByteBuf buf = heapBuf(16);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        assert buf.order() == ByteOrder.LITTLE_ENDIAN;

        buf.writeInt32(0x01020304);
        buf.markWriter();

        // In LE, 0x01020304 is stored as [04, 03, 02, 01]
        assert buf.getByte(0) == 0x04 : "LE int32 byte 0";
        assert buf.getByte(1) == 0x03 : "LE int32 byte 1";
        assert buf.getByte(2) == 0x02 : "LE int32 byte 2";
        assert buf.getByte(3) == 0x01 : "LE int32 byte 3";

        assert buf.readInt32() == 0x01020304 : "readInt32 in LE should decode correctly";
        buf.free();
    }

    @Test
    public void testByteOrder_switchBackToBE() {
        ByteBuf buf = heapBuf(16);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.order(ByteOrder.BIG_ENDIAN);
        assert buf.order() == ByteOrder.BIG_ENDIAN;

        buf.writeInt32(0x01020304);
        buf.markWriter();

        // In BE, 0x01020304 is stored as [01, 02, 03, 04]
        assert buf.getByte(0) == 0x01 : "BE int32 byte 0";
        assert buf.getByte(1) == 0x02 : "BE int32 byte 1";
        assert buf.getByte(2) == 0x03 : "BE int32 byte 2";
        assert buf.getByte(3) == 0x04 : "BE int32 byte 3";
        buf.free();
    }

    @Test
    public void testByteOrder_direct_LE() {
        ByteBuf buf = directBuf(16);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        buf.writeInt16((short) 0x0102);
        buf.writeInt64(0x0102030405060708L);
        buf.markWriter();

        assert buf.getByte(0) == 0x02 : "LE int16 byte 0";
        assert buf.getByte(1) == 0x01 : "LE int16 byte 1";

        assert buf.readInt16() == 0x0102 : "LE readInt16";
        assert buf.readInt64() == 0x0102030405060708L : "LE readInt64";
        buf.free();
    }

    // ========================================================================
    // readLine()
    // ========================================================================

    @Test
    public void testReadLine_heap() {
        ByteBuf buf = heapBuf(64);
        buf.writeBytes("Hello\nWorld\r\nEnd".getBytes());
        buf.markWriter();

        String line1 = buf.readLine();
        assert "Hello".equals(line1) : "readLine should return 'Hello', got: " + line1;

        String line2 = buf.readLine();
        assert "World".equals(line2) : "readLine should return 'World', got: " + line2;

        // "End" has no trailing newline — readLine should return null
        String line3 = buf.readLine();
        assert line3 == null : "readLine with no newline should return null";
        buf.free();
    }

    @Test
    public void testReadLine_emptyBuffer() {
        ByteBuf buf = heapBuf(16);
        buf.markWriter();
        String line = buf.readLine();
        assert line == null : "readLine on empty buffer should return null";
        buf.free();
    }

    @Test
    public void testReadLine_onlyNewlines() {
        ByteBuf buf = heapBuf(16);
        buf.writeBytes("\n\n\n".getBytes());
        buf.markWriter();

        assert "".equals(buf.readLine()) : "first line should be empty string";
        assert "".equals(buf.readLine()) : "second line should be empty string";
        assert "".equals(buf.readLine()) : "third line should be empty string";
        assert buf.readLine() == null : "no more lines";
        buf.free();
    }

    // ========================================================================
    // Multiple consecutive sliceOff
    // ========================================================================

    @Test
    public void testMultipleSliceOff_heap() {
        ByteBuf buf = heapBuf(32);
        buf.writeBytes(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 });
        buf.markWriter();

        ByteBuf s1 = buf.sliceOff(4);
        assert s1.readableBytes() == 4;
        assert s1.readByte() == 1;
        assert s1.readByte() == 2;
        assert s1.readByte() == 3;
        assert s1.readByte() == 4;
        s1.free();

        assert buf.readableBytes() == 8;

        ByteBuf s2 = buf.sliceOff(4);
        assert s2.readableBytes() == 4;
        assert s2.readByte() == 5;
        assert s2.readByte() == 6;
        assert s2.readByte() == 7;
        assert s2.readByte() == 8;
        s2.free();

        assert buf.readableBytes() == 4;

        ByteBuf s3 = buf.sliceOff(4);
        assert s3.readableBytes() == 4;
        assert s3.readByte() == 9;
        assert s3.readByte() == 10;
        assert s3.readByte() == 11;
        assert s3.readByte() == 12;
        s3.free();

        assert buf.readableBytes() == 0;
        buf.free();
    }

    @Test
    public void testMultipleSliceOff_direct() {
        ByteBuf buf = directBuf(32);
        buf.writeBytes(new byte[] { 10, 20, 30, 40, 50, 60 });
        buf.markWriter();

        ByteBuf s1 = buf.sliceOff(2);
        assert s1.readByte() == 10;
        assert s1.readByte() == 20;
        s1.free();

        ByteBuf s2 = buf.sliceOff(2);
        assert s2.readByte() == 30;
        assert s2.readByte() == 40;
        s2.free();

        ByteBuf s3 = buf.sliceOff(2);
        assert s3.readByte() == 50;
        assert s3.readByte() == 60;
        s3.free();

        assert buf.readableBytes() == 0;
        buf.free();
    }
}
