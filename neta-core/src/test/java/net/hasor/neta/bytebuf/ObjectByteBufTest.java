package net.hasor.neta.bytebuf;
import org.junit.Test;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class ObjectByteBufTest {
    @Test
    public void intObjectTest01() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer();

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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer();
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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer();
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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer();
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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer();

        byteBuf.writeFloat32(999999.999999f);
        byteBuf.writeFloat64(123456789123456789.123456789123456789123456789123456789d);
        byteBuf.markWriter();

        assert byteBuf.readFloat32() == 999999.999999f;
        assert byteBuf.readFloat64() == 123456789123456789.123456789123456789123456789123456789d;
    }

    @Test
    public void floatObjectTest02() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer();
        byteBuf.skipWritableBytes(40);

        byteBuf.setFloat32(0, 999999.999999f);
        byteBuf.setFloat64(10, 123456789123456789.123456789123456789123456789123456789d);
        byteBuf.markWriter();

        assert byteBuf.getFloat32(0) == 999999.999999f;
        assert byteBuf.getFloat64(10) == 123456789123456789.123456789123456789123456789123456789d;
    }

    @Test
    public void floatLEObjectTest01() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer();
        byteBuf.order(ByteOrder.LITTLE_ENDIAN);

        byteBuf.writeFloat32(999999.999999f);
        byteBuf.writeFloat64(123456789123456789.123456789123456789123456789123456789d);
        byteBuf.markWriter();

        assert byteBuf.readFloat32() == 999999.999999f;
        assert byteBuf.readFloat64() == 123456789123456789.123456789123456789123456789123456789d;
    }

    @Test
    public void floatLEObjectTest02() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer();
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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer();

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
        assert byteBuf.readInt64() == -1L;
    }

    @Test
    public void uIntObjectTest02() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer();
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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer();

        byteBuf.writeString("hello\n", StandardCharsets.UTF_8);
        byteBuf.writeString("word\n", StandardCharsets.UTF_8);
        byteBuf.markWriter();

        assert byteBuf.readExpect('\n', StandardCharsets.UTF_8).equals("hello");
        assert byteBuf.readExpect('\n', StandardCharsets.UTF_8).equals("word");

        byteBuf.resetReader();
        assert byteBuf.readExpectLast('\n', StandardCharsets.UTF_8).equals("hello\nword");
    }

    @Test
    public void stringTest02() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer();

        byteBuf.writeString("hello\n", StandardCharsets.UTF_8);
        byteBuf.writeString("word\n", StandardCharsets.UTF_8);
        byteBuf.markWriter();

        assert byteBuf.readExpect('\n', StandardCharsets.UTF_8).equals("hello");
        assert byteBuf.readExpect('\n', StandardCharsets.UTF_8).equals("word");

        byteBuf.resetReader();
        assert byteBuf.readExpectLast('\n', StandardCharsets.UTF_8).equals("hello\nword");
    }
}