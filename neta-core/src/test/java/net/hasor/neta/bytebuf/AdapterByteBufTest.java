package net.hasor.neta.bytebuf;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.codec.MD5;
import org.junit.Test;

import java.nio.BufferOverflowException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;

public class AdapterByteBufTest {
    @Test
    public void writeByteTest01() {
        byte[] array = new byte[4];
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.wrap(array));
        byteBuf.clear();

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
        byteBuf.writeByte((byte) 5);
        byteBuf.writeByte((byte) 6);
        byteBuf.writeByte((byte) 7);
        byteBuf.writeByte((byte) 8);

        assert array[0] == 5;
        assert array[1] == 6;
        assert array[2] == 7;
        assert array[3] == 8;
    }

    @Test
    public void writeByteTest02() {
        byte[] array = new byte[4];
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.wrap(array));
        byteBuf.clear();

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
        byteBuf.writeByte((byte) 5);
        byteBuf.writeByte((byte) 6);

        assert array[0] == 5;
        assert array[1] == 6;
        assert array[2] == 3;
        assert array[3] == 4;

        byteBuf.markWriter();
        assert byteBuf.readByte() == 4;
        assert byteBuf.readByte() == 5;
        assert byteBuf.readByte() == 6;
    }

    @Test
    public void writeBytesTest01() {
        byte[] array = new byte[4];
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.wrap(array));
        byteBuf.clear();

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
        byteBuf.writeBytes(new byte[] { 5, 6, 7, 8 });

        assert array[0] == 5;
        assert array[1] == 6;
        assert array[2] == 7;
        assert array[3] == 8;

        byteBuf.markWriter();
        byteBuf.readBytes(arrayRead);
        assert arrayRead[0] == 5;
        assert arrayRead[1] == 6;
        assert arrayRead[2] == 7;
        assert arrayRead[3] == 8;
    }

    @Test
    public void writeBytesTest02() {
        byte[] array = new byte[4];
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.wrap(array));
        byteBuf.clear();

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
        byteBuf.writeBytes(new byte[] { 4, 5, 6 });

        assert array[0] == 5;
        assert array[1] == 6;
        assert array[2] == 3;
        assert array[3] == 4;

        byteBuf.markWriter();
        assert byteBuf.readBytes(arrayRead) == 3;
        assert arrayRead[0] == 4;
        assert arrayRead[1] == 5;
        assert arrayRead[2] == 6;
    }

    @Test
    public void writeBytesTest03() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer(4));

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
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer(256, 512));
        byte[] array1 = byteBuf.array();

        byteBuf.writeBytes(RandomUtils.nextBytes(array1.length));
        assert array1 == byteBuf.array();
        byteBuf.writeBytes(RandomUtils.nextBytes(array1.length));
        assert array1 != byteBuf.array();

        byte[] data = byteBuf.array();
        byte[] dataSub = new byte[array1.length];
        System.arraycopy(data, 0, dataSub, 0, array1.length);

        String hash1 = MD5.encodeMD5(array1);
        String hash2 = MD5.encodeMD5(dataSub);
        assert hash1.equals(hash2);
    }

    @Test
    public void freeTest01() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer());
        byteBuf.free();
        assert byteBuf.array() == null;

        try {
            byteBuf.writeByte((byte) 5);
            assert false;
        } catch (IllegalStateException e) {
            assert e.getMessage().equals("has been released.");
        }
    }

    @Test
    public void copyTest01() throws NoSuchAlgorithmException {
        ByteBuf byteBuf1 = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer());
        byteBuf1.writeBytes(RandomUtils.nextBytes(byteBuf1.capacity()));

        ByteBuf byteBuf2 = byteBuf1.copy();

        assert byteBuf1.array() != byteBuf2.array();

        String hash1 = MD5.encodeMD5(byteBuf1.array());
        String hash2 = MD5.encodeMD5(byteBuf2.array());
        assert hash1.equals(hash2);
    }

    @Test
    public void errorTest02() {
        try {
            ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer(4));
            byteBuf.getByte(0);
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert e.getMessage().startsWith("read data(1) out of range. readMark(0) <= offset(0) <= writerMark(0)");
        }
    }

    @Test
    public void intObjectTest01() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer());

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
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer(40));
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
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer());
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
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer(40));
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
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer());

        byteBuf.writeFloat32(999999.999999f);
        byteBuf.writeFloat64(123456789123456789.123456789123456789123456789123456789d);
        byteBuf.markWriter();

        assert byteBuf.readFloat32() == 999999.999999f;
        assert byteBuf.readFloat64() == 123456789123456789.123456789123456789123456789123456789d;
    }

    @Test
    public void floatObjectTest02() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer());
        byteBuf.skipWritableBytes(40);

        byteBuf.setFloat32(0, 999999.999999f);
        byteBuf.setFloat64(10, 123456789123456789.123456789123456789123456789123456789d);
        byteBuf.markWriter();

        assert byteBuf.getFloat32(0) == 999999.999999f;
        assert byteBuf.getFloat64(10) == 123456789123456789.123456789123456789123456789123456789d;
    }

    @Test
    public void floatLEObjectTest01() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer());
        byteBuf.order(ByteOrder.LITTLE_ENDIAN);

        byteBuf.writeFloat32(999999.999999f);
        byteBuf.writeFloat64(123456789123456789.123456789123456789123456789123456789d);
        byteBuf.markWriter();

        assert byteBuf.readFloat32() == 999999.999999f;
        assert byteBuf.readFloat64() == 123456789123456789.123456789123456789123456789123456789d;
    }

    @Test
    public void floatLEObjectTest02() {
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer());
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
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer());

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
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer());
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
        ByteBuf byteBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer());

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
        ByteBuf srcBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer());
        srcBuf.writeBytes(cacheData);
        srcBuf.markWriter();

        ByteBuf dstBuf = ByteBufAllocator.DEFAULT.arrayBuffer();
        srcBuf.read(dstBuf, 5);
        dstBuf.markWriter();

        assert srcBuf.readableBytes() == 95;
        assert dstBuf.readableBytes() == 5;
    }

    @Test
    public void readBufTest2() throws NoSuchAlgorithmException {
        byte[] cacheData = RandomUtils.nextBytes(8192);
        ByteBuf srcBuf = new ByteBufAdapter(ByteBufAllocator.DEFAULT.arrayBuffer());
        srcBuf.writeBytes(cacheData);
        srcBuf.markWriter();

        ByteBuf dstBuf = ByteBufAllocator.DEFAULT.arrayBuffer();
        srcBuf.read(dstBuf, 5000);
        dstBuf.markWriter();

        assert srcBuf.readableBytes() == 8192 - 5000;
        assert dstBuf.readableBytes() == 5000;

        //
        byte[] array1 = new byte[5000];
        System.arraycopy(cacheData, 0, array1, 0, 5000);
        String array1Hash = MD5.encodeMD5(array1);

        byte[] array2 = new byte[5000];
        srcBuf.resetReader();
        srcBuf.readBytes(array2);
        String array2Hash = MD5.encodeMD5(array2);

        byte[] array3 = dstBuf.array();
        String array3Hash = MD5.encodeMD5(array3);

        assert array1Hash.equals(array2Hash);
        assert array2Hash.equals(array3Hash);
    }
}