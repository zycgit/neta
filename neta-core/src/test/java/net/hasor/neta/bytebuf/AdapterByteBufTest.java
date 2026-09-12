/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;

import java.nio.BufferOverflowException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;

import org.junit.Test;

import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.codec.MD5;

public class AdapterByteBufTest {
    @Test
    public void writeByteTest01() {
        byte[] array = new byte[4];
        ByteBuf byteBuf = new ByteBufAdapter(ByteBuf.wrap(array, true));

        byteBuf.writeByte((byte) 1);
        byteBuf.writeByte((byte) 2);
        byteBuf.writeByte((byte) 3);
        byteBuf.writeByte((byte) 4);

        assert array[0] == 1;
        assert array[1] == 2;
        assert array[2] == 3;
        assert array[3] == 4;

        // not markIndex yet
        try {
            byteBuf.writeByte((byte) 5);
            assert false;
        } catch (BufferOverflowException e) {
            assert true;
        }
        try {
            byteBuf.readByte();
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert true;
        }

        byteBuf.markWriter();
        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;
        assert byteBuf.readByte() == 4;

        byteBuf.markReader();
    }

    @Test
    public void writeByteTest02() {
        byte[] array = new byte[4];
        ByteBuf byteBuf = new ByteBufAdapter(ByteBuf.wrap(array, true));

        byteBuf.writeByte((byte) 1);
        byteBuf.writeByte((byte) 2);
        byteBuf.writeByte((byte) 3);

        assert array[0] == 1;
        assert array[1] == 2;
        assert array[2] == 3;
        assert array[3] == 0;

        byteBuf.markWriter();
        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;

        byteBuf.markReader();
        byteBuf.writeByte((byte) 4);

        assert array[0] == 1;
        assert array[1] == 2;
        assert array[2] == 3;
        assert array[3] == 4;

        byteBuf.markWriter();
        assert byteBuf.readByte() == 4;
    }

    @Test
    public void writeBytesTest01() {
        byte[] array = new byte[4];
        ByteBuf byteBuf = new ByteBufAdapter(ByteBuf.wrap(array, true));

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });
        assert array[0] == 1;
        assert array[1] == 2;
        assert array[2] == 3;
        assert array[3] == 4;

        // not markIndex yet
        try {
            byteBuf.writeByte((byte) 5);
            assert false;
        } catch (BufferOverflowException e) {
            assert true;
        }
        try {
            byteBuf.readByte();
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert true;
        }

        byteBuf.markWriter();
        byte[] arrayRead = new byte[6];
        byteBuf.readBytes(arrayRead);
        assert arrayRead[0] == 1;
        assert arrayRead[1] == 2;
        assert arrayRead[2] == 3;
        assert arrayRead[3] == 4;

        byteBuf.markReader();
        assert byteBuf.writeBytes(new byte[] { 5, 6, 7, 8 }) == 0;

        assert array[0] == 1;
        assert array[1] == 2;
        assert array[2] == 3;
        assert array[3] == 4;
    }

    @Test
    public void writeBytesTest02() {
        byte[] array = new byte[4];
        ByteBuf byteBuf = new ByteBufAdapter(ByteBuf.wrap(array, true));

        byteBuf.writeBytes(new byte[] { 1, 2, 3 });
        assert array[0] == 1;
        assert array[1] == 2;
        assert array[2] == 3;
        assert array[3] == 0;

        byteBuf.markWriter();
        byte[] arrayRead = new byte[6];
        assert byteBuf.readBytes(arrayRead) == 3;
        assert arrayRead[0] == 1;
        assert arrayRead[1] == 2;
        assert arrayRead[2] == 3;

        byteBuf.markReader();
        assert byteBuf.writeBytes(new byte[] { 4, 5, 6 }) == 1;

        assert array[0] == 1;
        assert array[1] == 2;
        assert array[2] == 3;
        assert array[3] == 4;

        byteBuf.markWriter();
        arrayRead = new byte[6];
        assert byteBuf.readBytes(arrayRead) == 1;
        assert arrayRead[0] == 4;
        assert arrayRead[1] == 0;
        assert arrayRead[2] == 0;
    }

    @Test
    public void writeBytesTest03() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.ringHeapBuffer(4));

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });
        byteBuf.markWriter();

        byte[] arrayRead = new byte[4];
        assert byteBuf.readBytes(arrayRead) == 4;
        byteBuf.markReader();

        byteBuf.writeBytes(new byte[] { 5, 6 });
        byteBuf.markWriter();
        assert byteBuf.readBytes(arrayRead) == 2;
        assert arrayRead[0] == 5;
        assert arrayRead[1] == 6;
    }

    @Test
    public void extendTest01() throws NoSuchAlgorithmException {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(512));
        byte[] array1 = byteBuf.asByteArray();

        byteBuf.writeBytes(RandomUtils.nextBytes(array1.length));
        assert array1 != byteBuf.asByteArray();
        byteBuf.writeBytes(RandomUtils.nextBytes(array1.length));
        assert array1 != byteBuf.asByteArray();

        byte[] data = byteBuf.asByteArray();
        byte[] dataSub = new byte[array1.length];
        System.arraycopy(data, 0, dataSub, 0, array1.length);

        String hash1 = MD5.encodeMD5(array1);
        String hash2 = MD5.encodeMD5(dataSub);
        assert hash1.equals(hash2);
    }

    @Test
    public void freeTest01() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(1024));
        byteBuf.free();

        try {
            assert byteBuf.asByteArray() == null;
            assert false;
        } catch (IllegalStateException e) {
            assert e.getMessage().equals("has been released.");
        }

        try {
            byteBuf.writeByte((byte) 5);
            assert false;
        } catch (IllegalStateException e) {
            assert e.getMessage().equals("has been released.");
        }
    }

    @Test
    public void copyTest01() throws NoSuchAlgorithmException {
        ByteBuf byteBuf1 = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(1024));
        byteBuf1.writeBytes(RandomUtils.nextBytes(byteBuf1.capacity()));

        ByteBuf byteBuf2 = byteBuf1.copy();

        assert byteBuf1.asByteArray() != byteBuf2.asByteArray();

        String hash1 = MD5.encodeMD5(byteBuf1.asByteArray());
        String hash2 = MD5.encodeMD5(byteBuf2.asByteArray());
        assert hash1.equals(hash2);
    }

    @Test
    public void errorTest02() {
        try {
            ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(4));
            byteBuf.getByte(0);
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert e.getMessage().startsWith("read out of range. index: 0, length: 1 (expected: 0 ~ 0)");
        }
    }

    @Test
    public void intObjectTest01() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(1024));

        byteBuf.writeInt16((short) 30047);
        byteBuf.writeInt24(15793921);
        byteBuf.writeInt32(1894780842);
        byteBuf.writeInt64(8071575397336023920L);
        byteBuf.markWriter();

        assert byteBuf.readInt16() == 30047;
        assert byteBuf.readInt24() == 15793921;
        assert byteBuf.readInt32() == 1894780842;
        assert byteBuf.readInt64() == 8071575397336023920L;
    }

    @Test
    public void intObjectTest02() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(40));
        byteBuf.skipWritableBytes(40);

        byteBuf.setInt16(0, (short) 30047);
        byteBuf.setInt24(10, 15793921);
        byteBuf.setInt32(20, 1894780842);
        byteBuf.setInt64(30, 8071575397336023920L);

        byteBuf.markWriter();
        assert byteBuf.getInt16(0) == 30047;
        assert byteBuf.getInt24(10) == 15793921;
        assert byteBuf.getInt32(20) == 1894780842;
        assert byteBuf.getInt64(30) == 8071575397336023920L;
    }

    @Test
    public void intLEObjectTest01() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(1024));
        byteBuf.order(ByteOrder.LITTLE_ENDIAN);

        byteBuf.writeInt16((short) 30047);
        byteBuf.writeInt24(15793921);
        byteBuf.writeInt32(1894780842);
        byteBuf.writeInt64(8071575397336023920L);
        byteBuf.markWriter();

        assert byteBuf.readInt16() == 30047;
        assert byteBuf.readInt24() == 15793921;
        assert byteBuf.readInt32() == 1894780842;
        assert byteBuf.readInt64() == 8071575397336023920L;
    }

    @Test
    public void intLEObjectTest02() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(40));
        byteBuf.order(ByteOrder.LITTLE_ENDIAN);
        byteBuf.skipWritableBytes(40);

        byteBuf.setInt16(0, (short) 30047);
        byteBuf.setInt24(10, 15793921);
        byteBuf.setInt32(20, 1894780842);
        byteBuf.setInt64(30, 8071575397336023920L);

        byteBuf.markWriter();
        assert byteBuf.getInt16(0) == 30047;
        assert byteBuf.getInt24(10) == 15793921;
        assert byteBuf.getInt32(20) == 1894780842;
        assert byteBuf.getInt64(30) == 8071575397336023920L;
    }

    @Test
    public void floatObjectTest01() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(1024));

        byteBuf.writeFloat32(999999.999999f);
        byteBuf.writeFloat64(123456789123456789.123456789123456789123456789123456789d);
        byteBuf.markWriter();

        assert byteBuf.readFloat32() == 999999.999999f;
        assert byteBuf.readFloat64() == 123456789123456789.123456789123456789123456789123456789d;
    }

    @Test
    public void floatObjectTest02() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(1024));
        byteBuf.skipWritableBytes(40);

        byteBuf.setFloat32(0, 999999.999999f);
        byteBuf.setFloat64(10, 123456789123456789.123456789123456789123456789123456789d);
        byteBuf.markWriter();

        assert byteBuf.getFloat32(0) == 999999.999999f;
        assert byteBuf.getFloat64(10) == 123456789123456789.123456789123456789123456789123456789d;
    }

    @Test
    public void floatLEObjectTest01() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(1024));
        byteBuf.order(ByteOrder.LITTLE_ENDIAN);

        byteBuf.writeFloat32(999999.999999f);
        byteBuf.writeFloat64(123456789123456789.123456789123456789123456789123456789d);
        byteBuf.markWriter();

        assert byteBuf.readFloat32() == 999999.999999f;
        assert byteBuf.readFloat64() == 123456789123456789.123456789123456789123456789123456789d;
    }

    @Test
    public void floatLEObjectTest02() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(1024));
        byteBuf.order(ByteOrder.LITTLE_ENDIAN);
        byteBuf.skipWritableBytes(40);

        byteBuf.setFloat32(0, 999999.999999f);
        byteBuf.setFloat64(10, 123456789123456789.123456789123456789123456789123456789d);
        byteBuf.markWriter();

        assert byteBuf.getFloat32(0) == 999999.999999f;
        assert byteBuf.getFloat64(10) == 123456789123456789.123456789123456789123456789123456789d;
    }

    @Test
    public void uIntObjectTest01() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(1024));

        byteBuf.writeByte((byte) -1);
        byteBuf.writeInt16((short) -1);
        byteBuf.writeInt24(-1);
        byteBuf.writeInt32(-1);
        byteBuf.writeUInt32(-1);
        byteBuf.writeInt64(-1);
        byteBuf.markWriter();

        assert byteBuf.getUInt8(0) == 255;
        assert byteBuf.getUInt16(1) == 65535;
        assert byteBuf.getUInt24(3) == 16777215;
        assert byteBuf.getUInt32(5) == 4294967295L;
        assert byteBuf.getInt32(9) == -1;
        assert byteBuf.getUInt32(9) == 4294967295L;
        assert byteBuf.getUInt32(6) == 4294967295L;
        assert byteBuf.getInt64(10) == -1;

        assert byteBuf.readUInt8() == 255;
        assert byteBuf.readUInt16() == 65535;
        assert byteBuf.readUInt24() == 16777215;
        assert byteBuf.readUInt32() == 4294967295L;
        assert byteBuf.readInt64() == -1;
    }

    @Test
    public void uIntObjectTest02() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(1024));
        byteBuf.order(ByteOrder.LITTLE_ENDIAN);

        byteBuf.writeInt16((short) 36848);
        byteBuf.writeInt24(9433258);
        byteBuf.writeInt32((int) 2156916906L);
        byteBuf.writeUInt32(4294967295L);
        byteBuf.markWriter();

        assert byteBuf.getUInt16(0) == 36848;
        assert byteBuf.getUInt24(2) == 9433258;
        assert byteBuf.getUInt32(5) == 2156916906L;
        assert byteBuf.getUInt32(9) == 4294967295L;
        assert byteBuf.getInt32(9) == -1;

        assert byteBuf.readUInt16() == 36848;
        assert byteBuf.readUInt24() == 9433258;
        assert byteBuf.readUInt32() == 2156916906L;
        assert byteBuf.readUInt32() == 4294967295L;
    }

    @Test
    public void stringTest01() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(1024));

        byteBuf.writeString("hello\n", StandardCharsets.UTF_8);
        byteBuf.writeString("word\n", StandardCharsets.UTF_8);
        byteBuf.markWriter();

        assert byteBuf.readExpect('\n', StandardCharsets.UTF_8).equals("hello");
        assert byteBuf.readExpect('\n', StandardCharsets.UTF_8).equals("word");

        byteBuf.resetReader();
        assert byteBuf.readExpectLast('\n', StandardCharsets.UTF_8).equals("hello\nword");
    }

    @Test
    public void readBufTest1() {
        byte[] cacheData = RandomUtils.nextBytes(100);
        ByteBuf srcBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(1024));
        srcBuf.writeBytes(cacheData);
        srcBuf.markWriter();

        ByteBuf dstBuf = ByteBufAllocator.DEFAULT.heapBuffer(1024);
        srcBuf.readBuffer(dstBuf, 5);
        dstBuf.markWriter();

        assert srcBuf.readableBytes() == 95;
        assert dstBuf.readableBytes() == 5;
    }

    @Test
    public void readBufTest2() throws NoSuchAlgorithmException {
        byte[] cacheData = RandomUtils.nextBytes(20);
        ByteBuf srcBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.heapBuffer(10));
        assert srcBuf.writeBytes(cacheData) == 10;
        srcBuf.markWriter();

        ByteBuf dstBuf = ByteBufAllocator.DEFAULT.heapBuffer(10);
        assert srcBuf.readBuffer(dstBuf, 20) == 10;
        dstBuf.markWriter();

        assert srcBuf.readableBytes() == 0;
        assert dstBuf.readableBytes() == 10;

        //
        byte[] array1 = new byte[20];
        System.arraycopy(cacheData, 0, array1, 0, 20);
        String array1Hash = MD5.encodeMD5(array1);

        byte[] array2 = new byte[20];
        srcBuf.resetReader();
        srcBuf.readBytes(array2);
        String array2Hash = MD5.encodeMD5(array2);

        byte[] array3 = dstBuf.asByteArray();
        String array3Hash = MD5.encodeMD5(array3);

        assert !array1Hash.equals(array2Hash);
        assert !array2Hash.equals(array3Hash);

        byte[] array1cut = new byte[10];
        byte[] array2cut = new byte[10];
        byte[] array3cut = new byte[10];
        System.arraycopy(array1, 0, array1cut, 0, 10);
        System.arraycopy(array2, 0, array2cut, 0, 10);
        System.arraycopy(array3, 0, array3cut, 0, 10);
        String array1cutHash = MD5.encodeMD5(array1cut);
        String array2cutHash = MD5.encodeMD5(array2cut);
        String array3cutHash = MD5.encodeMD5(array3cut);
        assert array1cutHash.equals(array2cutHash);
        assert array2cutHash.equals(array3cutHash);
    }
}
