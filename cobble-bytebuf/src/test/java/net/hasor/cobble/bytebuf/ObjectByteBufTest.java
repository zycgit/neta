package net.hasor.cobble.bytebuf;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class ObjectByteBufTest {
    @Test
    public void intObjectTest01() throws IOException {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.arrayBuffer();

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
    public void intObjectTest02() throws IOException {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.arrayBuffer(40);
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
    public void intLEObjectTest01() throws IOException {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.arrayBuffer();

        byteBuf.writeInt16LE((short) 30047);
        byteBuf.writeInt24LE(15793921);
        byteBuf.writeInt32LE(1894780842);
        byteBuf.writeInt64LE(8071575397336023920L);
        byteBuf.markWriter();

        assert byteBuf.readInt16LE() == 30047;
        assert byteBuf.readInt24LE() == 15793921;
        assert byteBuf.readInt32LE() == 1894780842;
        assert byteBuf.readInt64LE() == 8071575397336023920L;
    }

    @Test
    public void intLEObjectTest02() throws IOException {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.arrayBuffer(40);
        byteBuf.skipWritableBytes(40);

        byteBuf.setInt16LE(0, (short) 30047);
        byteBuf.setInt24LE(10, 15793921);
        byteBuf.setInt32LE(20, 1894780842);
        byteBuf.setInt64(30, 8071575397336023920L);

        byteBuf.markWriter();
        assert byteBuf.getInt16LE(0) == 30047;
        assert byteBuf.getInt24LE(10) == 15793921;
        assert byteBuf.getInt32LE(20) == 1894780842;
        assert byteBuf.getInt64LE(30) == 8071575397336023920L;
    }

    @Test
    public void floatObjectTest01() throws IOException {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.arrayBuffer();

        byteBuf.writeFloat32(999999.999999f);
        byteBuf.writeFloat64(123456789123456789.123456789123456789123456789123456789d);
        byteBuf.markWriter();

        assert byteBuf.readFloat32() == 999999.999999f;
        assert byteBuf.readFloat64() == 123456789123456789.123456789123456789123456789123456789d;
    }

    @Test
    public void floatObjectTest02() throws IOException {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.arrayBuffer();
        byteBuf.skipWritableBytes(40);

        byteBuf.setFloat32(0, 999999.999999f);
        byteBuf.setFloat64(10, 123456789123456789.123456789123456789123456789123456789d);
        byteBuf.markWriter();

        assert byteBuf.getFloat32(0) == 999999.999999f;
        assert byteBuf.getFloat64(10) == 123456789123456789.123456789123456789123456789123456789d;
    }

    @Test
    public void floatLEObjectTest01() throws IOException {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.arrayBuffer();

        byteBuf.writeFloat32LE(999999.999999f);
        byteBuf.writeFloat64LE(123456789123456789.123456789123456789123456789123456789d);
        byteBuf.markWriter();

        assert byteBuf.readFloat32LE() == 999999.999999f;
        assert byteBuf.readFloat64LE() == 123456789123456789.123456789123456789123456789123456789d;
    }

    @Test
    public void floatLEObjectTest02() throws IOException {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.arrayBuffer();
        byteBuf.skipWritableBytes(40);

        byteBuf.setFloat32LE(0, 999999.999999f);
        byteBuf.setFloat64LE(10, 123456789123456789.123456789123456789123456789123456789d);
        byteBuf.markWriter();

        assert byteBuf.getFloat32LE(0) == 999999.999999f;
        assert byteBuf.getFloat64LE(10) == 123456789123456789.123456789123456789123456789123456789d;
    }

    @Test
    public void uIntObjectTest01() throws IOException {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.arrayBuffer();

        byteBuf.writeByte((byte) -1);
        byteBuf.writeInt16((short) -1);
        byteBuf.writeInt24(-1);
        byteBuf.writeInt32(-1);
        byteBuf.writeUInt32(-1);
        byteBuf.writeInt64(-1);
        byteBuf.markWriter();

        assert byteBuf.readUInt8() == 255;
        assert byteBuf.readUInt16() == 65535;
        assert byteBuf.readUInt24() == 16777215;
        assert byteBuf.readUInt32() == 4294967295L;
        assert byteBuf.readUInt32() == 4294967295L;
        assert byteBuf.readInt64() == -1L;

        assert byteBuf.getUInt8(0) == 255;
        assert byteBuf.getUInt16(1) == 65535;
        assert byteBuf.getUInt24(3) == 16777215;
        assert byteBuf.getUInt32(5) == 4294967295L;
        assert byteBuf.getInt32(9) == -1;
        assert byteBuf.getUInt32(9) == 4294967295L;
    }

    @Test
    public void uIntObjectTest02() throws IOException {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.arrayBuffer();

        byteBuf.writeInt16LE((short) 36848);
        byteBuf.writeInt24LE(9433258);
        byteBuf.writeInt32LE((int) 2156916906L);
        byteBuf.writeUInt32LE(4294967295L);
        byteBuf.markWriter();

        assert byteBuf.readUInt16LE() == 36848;
        assert byteBuf.readUInt24LE() == 9433258;
        assert byteBuf.readUInt32LE() == 2156916906L;
        assert byteBuf.readUInt32LE() == 4294967295L;

        assert byteBuf.getUInt16LE(0) == 36848;
        assert byteBuf.getUInt24LE(2) == 9433258;
        assert byteBuf.getUInt32LE(5) == 2156916906L;
        assert byteBuf.getUInt32LE(9) == 4294967295L;
        assert byteBuf.getInt32(9) == -1;
    }

    @Test
    public void stringTest01() throws IOException {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.arrayBuffer();

        byteBuf.writeString("hello\n", StandardCharsets.UTF_8);
        byteBuf.writeString("word\n", StandardCharsets.UTF_8);
        byteBuf.markWriter();

        assert byteBuf.readExpect('\n', StandardCharsets.UTF_8).equals("hello");
        assert byteBuf.readExpect('\n', StandardCharsets.UTF_8).equals("word");

        byteBuf.resetReader();
        assert byteBuf.readExpectLast('\n', StandardCharsets.UTF_8).equals("hello\nword");
    }
}