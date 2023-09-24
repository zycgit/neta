package net.hasor.cobble.bytebuf;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.codec.MD5;
import org.junit.Test;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

public class DirectByteBufTest {
    @Test
    public void writeByteTest01() {
        ByteBuffer direct = ByteBuffer.allocateDirect(4);
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.wrap(direct);

        byteBuf.writeByte((byte) 1);
        byteBuf.writeByte((byte) 2);
        byteBuf.writeByte((byte) 3);
        byteBuf.writeByte((byte) 4);

        assert direct.get(0) == 1;
        assert direct.get(1) == 2;
        assert direct.get(2) == 3;
        assert direct.get(3) == 4;

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

        assert direct.get(0) == 5;
        assert direct.get(1) == 6;
        assert direct.get(2) == 7;
        assert direct.get(3) == 8;
    }

    @Test
    public void writeByteTest02() {
        ByteBuffer direct = ByteBuffer.allocateDirect(4);
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.wrap(direct);

        byteBuf.writeByte((byte) 1);
        byteBuf.writeByte((byte) 2);
        byteBuf.writeByte((byte) 3);

        assert direct.get(0) == 1;
        assert direct.get(1) == 2;
        assert direct.get(2) == 3;
        assert direct.get(3) == 0;

        byteBuf.markWriter();
        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;

        byteBuf.markReader();
        byteBuf.writeByte((byte) 4);
        byteBuf.writeByte((byte) 5);
        byteBuf.writeByte((byte) 6);

        assert direct.get(0) == 5;
        assert direct.get(1) == 6;
        assert direct.get(2) == 3;
        assert direct.get(3) == 4;

        byteBuf.markWriter();
        assert byteBuf.readByte() == 4;
        assert byteBuf.readByte() == 5;
        assert byteBuf.readByte() == 6;
    }

    @Test
    public void writeBytesTest01() {
        ByteBuffer direct = ByteBuffer.allocateDirect(4);
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.wrap(direct);

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });
        assert direct.get(0) == 1;
        assert direct.get(1) == 2;
        assert direct.get(2) == 3;
        assert direct.get(3) == 4;

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

        assert direct.get(0) == 5;
        assert direct.get(1) == 6;
        assert direct.get(2) == 7;
        assert direct.get(3) == 8;

        byteBuf.markWriter();
        byteBuf.readBytes(arrayRead);
        assert arrayRead[0] == 5;
        assert arrayRead[1] == 6;
        assert arrayRead[2] == 7;
        assert arrayRead[3] == 8;
    }

    @Test
    public void writeBytesTest02() {
        ByteBuffer direct = ByteBuffer.allocateDirect(4);
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.wrap(direct);

        byteBuf.writeBytes(new byte[] { 1, 2, 3 });
        assert direct.get(0) == 1;
        assert direct.get(1) == 2;
        assert direct.get(2) == 3;
        assert direct.get(3) == 0;

        byteBuf.markWriter();
        byte[] arrayRead = new byte[6];
        assert byteBuf.readBytes(arrayRead) == 3;
        assert arrayRead[0] == 1;
        assert arrayRead[1] == 2;
        assert arrayRead[2] == 3;

        byteBuf.markReader();
        byteBuf.writeBytes(new byte[] { 4, 5, 6 });

        assert direct.get(0) == 5;
        assert direct.get(1) == 6;
        assert direct.get(2) == 3;
        assert direct.get(3) == 4;

        byteBuf.markWriter();
        assert byteBuf.readBytes(arrayRead) == 3;
        assert arrayRead[0] == 4;
        assert arrayRead[1] == 5;
        assert arrayRead[2] == 6;
    }

    @Test
    public void writeBytesTest03() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);

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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(256, 512);
        byte[] array1 = byteBuf.array();

        byteBuf.writeBytes(RandomUtils.nextBytes(array1.length));
        array1 = byteBuf.array();
        assert array1 != byteBuf.array();
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
        SliceNioByteBuf byteBuf = (SliceNioByteBuf) ByteBufAllocator.DEFAULT.directBuffer();
        byteBuf.free();
        assert byteBuf.data == null;

        try {
            byteBuf.writeByte((byte) 5);
            assert false;
        } catch (IllegalStateException e) {
            assert e.getMessage().equals("has been released.");
        }
    }

    @Test
    public void freeTest02() {
        byte[] result = new byte[10 * 1024 * 1024];
        Random RANDOM = new Random(System.currentTimeMillis());
        RANDOM.nextBytes(result);

        List<Object> list = new LinkedList<>();
        for (int i = 0; i < 1000; i++) {
            ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer();
            byteBuf.writeBytes(result);
            byteBuf.free();
            list.add(byteBuf);
        }
    }

    @Test
    public void copyTest01() throws NoSuchAlgorithmException {
        ByteBuf byteBuf1 = ByteBufAllocator.DEFAULT.directBuffer();
        byteBuf1.writeBytes(RandomUtils.nextBytes(byteBuf1.capacity()));

        ByteBuf byteBuf2 = byteBuf1.copy();

        assert byteBuf1.array() != byteBuf2.array();

        String hash1 = MD5.encodeMD5(byteBuf1.array());
        String hash2 = MD5.encodeMD5(byteBuf2.array());
        assert hash1.equals(hash2);
    }

    @Test
    public void errorTest01() {
        try {
            ByteBufAllocator.DEFAULT.directBuffer(-1);
            assert false;
        } catch (IllegalArgumentException e) {
            assert e.getMessage().equals("0 > capacity > maxCapacity ( gt 0 or eq -1)");
        }
    }

    @Test
    public void errorTest02() {
        try {
            ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
            byteBuf.getByte(0);
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert e.getMessage().startsWith("read data(1) out of range. readMark(0) <= offset(0) <= writerMark(0)");
        }
    }

    @Test
    public void writeStringTest01() {
        byte[] date = "aaa\nbbb\nccc\n".getBytes(StandardCharsets.US_ASCII);

        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledHeapBuffer();
        byteBuf.writeBytes(date);
        byteBuf.markWriter();

        String line1 = byteBuf.readExpect("\n", StandardCharsets.US_ASCII);
        String line2 = byteBuf.readExpect("\n", StandardCharsets.US_ASCII);
        String line3 = byteBuf.readExpect("\n", StandardCharsets.US_ASCII);

        assert line1.equals("aaa");
        assert line2.equals("bbb");
        assert line3.equals("ccc");
    }
}
