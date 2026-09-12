/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.nio.ByteOrder;
import org.junit.Test;

/**
 * Tests for Bits encoding/decoding correctness - int16, int24, int32, int64,
 * unsigned variants, big-endian vs little-endian, boundary values.
 */
public class BitsEncodingTest {

    // ========================================================================
    // Int16 (short) encoding/decoding
    // ========================================================================

    @Test
    public void int16_bigEndian_positive() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeInt16((short) 0x0102);
        buf.markWriter();
        assert buf.readInt16() == (short) 0x0102;
        buf.free();
    }

    @Test
    public void int16_bigEndian_negative() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeInt16((short) -1);
        buf.markWriter();
        assert buf.readInt16() == (short) -1;
        buf.free();
    }

    @Test
    public void int16_littleEndian() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt16((short) 0x0102);
        buf.markWriter();
        assert buf.readInt16() == (short) 0x0102;
        buf.free();
    }

    @Test
    public void int16_boundaryValues() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeInt16(Short.MAX_VALUE);
        buf.writeInt16(Short.MIN_VALUE);
        buf.writeInt16((short) 0);
        buf.markWriter();

        assert buf.readInt16() == Short.MAX_VALUE;
        assert buf.readInt16() == Short.MIN_VALUE;
        assert buf.readInt16() == 0;
        buf.free();
    }

    // ========================================================================
    // Int24 encoding/decoding
    // ========================================================================

    @Test
    public void int24_bigEndian() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeInt24(0x010203);
        buf.markWriter();
        assert buf.readInt24() == 0x010203;
        buf.free();
    }

    @Test
    public void int24_littleEndian() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt24(0x010203);
        buf.markWriter();
        assert buf.readInt24() == 0x010203;
        buf.free();
    }

    @Test
    public void int24_maxValue() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        int maxInt24 = 0x7FFFFF; // max positive 24-bit
        buf.writeInt24(maxInt24);
        buf.markWriter();
        assert buf.readInt24() == maxInt24;
        buf.free();
    }

    // ========================================================================
    // Int32 encoding/decoding
    // ========================================================================

    @Test
    public void int32_bigEndian_roundtrip() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeInt32(0x01020304);
        buf.markWriter();
        assert buf.readInt32() == 0x01020304;
        buf.free();
    }

    @Test
    public void int32_littleEndian_roundtrip() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt32(0x01020304);
        buf.markWriter();
        assert buf.readInt32() == 0x01020304;
        buf.free();
    }

    @Test
    public void int32_boundaryValues() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeInt32(Integer.MAX_VALUE);
        buf.writeInt32(Integer.MIN_VALUE);
        buf.writeInt32(0);
        buf.writeInt32(-1);
        buf.markWriter();

        assert buf.readInt32() == Integer.MAX_VALUE;
        assert buf.readInt32() == Integer.MIN_VALUE;
        assert buf.readInt32() == 0;
        assert buf.readInt32() == -1;
        buf.free();
    }

    @Test
    public void int32_bigEndian_byteOrder() {
        // Verify actual byte layout for big-endian
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeInt32(0x01020304);
        buf.markWriter();

        assert buf.getByte(0) == 0x01;
        assert buf.getByte(1) == 0x02;
        assert buf.getByte(2) == 0x03;
        assert buf.getByte(3) == 0x04;
        buf.free();
    }

    @Test
    public void int32_littleEndian_byteOrder() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt32(0x01020304);
        buf.markWriter();

        // Little-endian: LSB first
        assert buf.getByte(0) == 0x04;
        assert buf.getByte(1) == 0x03;
        assert buf.getByte(2) == 0x02;
        assert buf.getByte(3) == 0x01;
        buf.free();
    }

    // ========================================================================
    // Int64 encoding/decoding
    // ========================================================================

    @Test
    public void int64_bigEndian_roundtrip() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeInt64(0x0102030405060708L);
        buf.markWriter();
        assert buf.readInt64() == 0x0102030405060708L;
        buf.free();
    }

    @Test
    public void int64_littleEndian_roundtrip() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt64(0x0102030405060708L);
        buf.markWriter();
        assert buf.readInt64() == 0x0102030405060708L;
        buf.free();
    }

    @Test
    public void int64_boundaryValues() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeInt64(Long.MAX_VALUE);
        buf.writeInt64(Long.MIN_VALUE);
        buf.writeInt64(0L);
        buf.writeInt64(-1L);
        buf.markWriter();

        assert buf.readInt64() == Long.MAX_VALUE;
        assert buf.readInt64() == Long.MIN_VALUE;
        assert buf.readInt64() == 0L;
        assert buf.readInt64() == -1L;
        buf.free();
    }

    // ========================================================================
    // Float32 / Float64 encoding/decoding
    // ========================================================================

    @Test
    public void float32_roundtrip() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeFloat32(3.14f);
        buf.writeFloat32(Float.MAX_VALUE);
        buf.writeFloat32(Float.MIN_VALUE);
        buf.writeFloat32(Float.NaN);
        buf.writeFloat32(Float.POSITIVE_INFINITY);
        buf.writeFloat32(Float.NEGATIVE_INFINITY);
        buf.markWriter();

        assert buf.readFloat32() == 3.14f;
        assert buf.readFloat32() == Float.MAX_VALUE;
        assert buf.readFloat32() == Float.MIN_VALUE;
        assert Float.isNaN(buf.readFloat32());
        assert buf.readFloat32() == Float.POSITIVE_INFINITY;
        assert buf.readFloat32() == Float.NEGATIVE_INFINITY;
        buf.free();
    }

    @Test
    public void float64_roundtrip() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(128);
        buf.writeFloat64(3.141592653589793);
        buf.writeFloat64(Double.MAX_VALUE);
        buf.writeFloat64(Double.MIN_VALUE);
        buf.writeFloat64(Double.NaN);
        buf.writeFloat64(Double.POSITIVE_INFINITY);
        buf.writeFloat64(Double.NEGATIVE_INFINITY);
        buf.markWriter();

        assert buf.readFloat64() == 3.141592653589793;
        assert buf.readFloat64() == Double.MAX_VALUE;
        assert buf.readFloat64() == Double.MIN_VALUE;
        assert Double.isNaN(buf.readFloat64());
        assert buf.readFloat64() == Double.POSITIVE_INFINITY;
        assert buf.readFloat64() == Double.NEGATIVE_INFINITY;
        buf.free();
    }

    // ========================================================================
    // Unsigned types
    // ========================================================================

    @Test
    public void uint8_roundtrip() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeByte((byte) 0xFF);
        buf.writeByte((byte) 0x80);
        buf.writeByte((byte) 0x00);
        buf.writeByte((byte) 0x7F);
        buf.markWriter();

        assert buf.readUInt8() == 255;
        assert buf.readUInt8() == 128;
        assert buf.readUInt8() == 0;
        assert buf.readUInt8() == 127;
        buf.free();
    }

    @Test
    public void uint16_bigEndian() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeInt16((short) 0xFFFF);
        buf.writeInt16((short) 0x0001);
        buf.markWriter();

        assert buf.readUInt16() == 65535;
        assert buf.readUInt16() == 1;
        buf.free();
    }

    @Test
    public void uint16_littleEndian() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt16((short) 0xFFFF);
        buf.writeInt16((short) 0x0001);
        buf.markWriter();

        assert buf.readUInt16() == 65535;
        assert buf.readUInt16() == 1;
        buf.free();
    }

    @Test
    public void uint24_roundtrip() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeInt24(0xFFFFFF);
        buf.writeInt24(0x000001);
        buf.markWriter();

        assert buf.readUInt24() == 0xFFFFFF;
        assert buf.readUInt24() == 1;
        buf.free();
    }

    @Test
    public void uint32_roundtrip() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeInt32(0xFFFFFFFF); // -1 as signed int, 4294967295 as unsigned
        buf.writeInt32(0x00000001);
        buf.markWriter();

        assert buf.readUInt32() == 0xFFFFFFFFL; // 4294967295
        assert buf.readUInt32() == 1L;
        buf.free();
    }

    @Test
    public void uint32_littleEndian() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt32(0xFFFFFFFF);
        buf.markWriter();

        assert buf.readUInt32() == 0xFFFFFFFFL;
        buf.free();
    }

    // ========================================================================
    // get (random access) with unsigned
    // ========================================================================

    @Test
    public void getUInt8_atOffset() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeBytes(new byte[] { (byte) 0xFF, 0x00, (byte) 0x80 });
        buf.markWriter();

        assert buf.getUInt8(0) == 255;
        assert buf.getUInt8(1) == 0;
        assert buf.getUInt8(2) == 128;
        buf.free();
    }

    @Test
    public void getUInt16_atOffset() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeInt16((short) 0x1234);
        buf.writeInt16((short) 0xFFFF);
        buf.markWriter();

        assert buf.getUInt16(0) == 0x1234;
        assert buf.getUInt16(2) == 0xFFFF;
        buf.free();
    }

    // ========================================================================
    // set (random access write) tests
    // ========================================================================

    @Test
    public void setByte_and_getByte() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeBytes(new byte[8]); // allocate writable area

        buf.setByte(0, (byte) 0x42);
        buf.setByte(7, (byte) 0xFF);
        buf.markWriter(); // commit writes so data becomes readable

        assert buf.getByte(0) == 0x42;
        assert buf.getByte(7) == (byte) 0xFF;
        buf.free();
    }

    @Test
    public void setInt16_and_getInt16() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeBytes(new byte[8]);

        buf.setInt16(0, (short) 0x1234);
        buf.setInt16(2, (short) -1);
        buf.markWriter();

        assert buf.getInt16(0) == (short) 0x1234;
        assert buf.getInt16(2) == (short) -1;
        buf.free();
    }

    @Test
    public void setInt32_and_getInt32() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeBytes(new byte[8]);

        buf.setInt32(0, 0x12345678);
        buf.markWriter();
        assert buf.getInt32(0) == 0x12345678;
        buf.free();
    }

    @Test
    public void setInt64_and_getInt64() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeBytes(new byte[16]);

        buf.setInt64(0, 0x0102030405060708L);
        buf.markWriter();
        assert buf.getInt64(0) == 0x0102030405060708L;
        buf.free();
    }

    @Test
    public void setFloat32_and_getFloat32() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeBytes(new byte[8]);

        buf.setFloat32(0, 2.718f);
        buf.markWriter();
        assert buf.getFloat32(0) == 2.718f;
        buf.free();
    }

    @Test
    public void setFloat64_and_getFloat64() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeBytes(new byte[16]);

        buf.setFloat64(0, 1.41421356);
        buf.markWriter();
        assert buf.getFloat64(0) == 1.41421356;
        buf.free();
    }

    // ========================================================================
    // Mixed byte order switching
    // ========================================================================

    @Test
    public void byteOrder_switchMidStream() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);

        // Write big-endian int32
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.writeInt32(0xAABBCCDD);

        // Switch to little-endian for next int32
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt32(0x11223344);
        buf.markWriter();

        // Read back
        buf.order(ByteOrder.BIG_ENDIAN);
        assert buf.readInt32() == 0xAABBCCDD;

        buf.order(ByteOrder.LITTLE_ENDIAN);
        assert buf.readInt32() == 0x11223344;

        buf.free();
    }

    // ========================================================================
    // Direct buffer encoding tests
    // ========================================================================

    @Test
    public void directBuffer_int32_roundtrip() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer(64);
        buf.writeInt32(0xDEADBEEF);
        buf.markWriter();
        assert buf.readInt32() == 0xDEADBEEF;
        buf.free();
    }

    @Test
    public void directBuffer_int64_littleEndian() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer(64);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.writeInt64(0x0A0B0C0D0E0F1011L);
        buf.markWriter();
        assert buf.readInt64() == 0x0A0B0C0D0E0F1011L;
        buf.free();
    }
}
