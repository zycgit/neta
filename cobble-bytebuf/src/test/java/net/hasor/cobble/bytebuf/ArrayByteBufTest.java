package net.hasor.cobble.bytebuf;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.codec.MD5;
import org.junit.Test;

import java.nio.BufferOverflowException;
import java.security.NoSuchAlgorithmException;

public class ArrayByteBufTest {
    @Test
    public void writeByteTest01() {
        byte[] array = new byte[4];
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.wrap(array);

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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.wrap(array);

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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.wrap(array);

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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.wrap(array);

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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.arrayBuffer(4);

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
        ArrayByteBuf byteBuf = (ArrayByteBuf) ByteBufAllocator.DEFAULT.arrayBuffer(256, 512);
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
        ArrayByteBuf byteBuf = (ArrayByteBuf) ByteBufAllocator.DEFAULT.arrayBuffer();
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
        ArrayByteBuf byteBuf1 = (ArrayByteBuf) ByteBufAllocator.DEFAULT.arrayBuffer();
        byteBuf1.writeBytes(RandomUtils.nextBytes(byteBuf1.capacity()));

        ArrayByteBuf byteBuf2 = byteBuf1.copy();

        assert byteBuf1.array() != byteBuf2.array();

        String hash1 = MD5.encodeMD5(byteBuf1.array());
        String hash2 = MD5.encodeMD5(byteBuf2.array());
        assert hash1.equals(hash2);
    }

    @Test
    public void errorTest01() {
        try {
            ByteBufAllocator.DEFAULT.arrayBuffer(-1);
            assert false;
        } catch (IllegalArgumentException e) {
            assert e.getMessage().equals("0 > capacity > maxCapacity ( gt 0 or eq -1)");
        }
    }
}
