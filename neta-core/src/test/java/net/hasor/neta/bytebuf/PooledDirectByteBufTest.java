package net.hasor.neta.bytebuf;
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

public class PooledDirectByteBufTest {
    @Test
    public void writeByteTest01() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 4);

        byteBuf.writeByte((byte) 1);
        byteBuf.writeByte((byte) 2);
        byteBuf.writeByte((byte) 3);
        byteBuf.writeByte((byte) 4);

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
        byteBuf.markWriter();

        assert byteBuf.readByte() == 5;
        assert byteBuf.readByte() == 6;
        assert byteBuf.readByte() == 7;
        assert byteBuf.readByte() == 8;
    }

    @Test
    public void writeByteTest01_1() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 2);

        byteBuf.writeByte((byte) 1);
        byteBuf.writeByte((byte) 2);
        byteBuf.writeByte((byte) 3);
        byteBuf.writeByte((byte) 4);

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
        byteBuf.markWriter();

        assert byteBuf.readByte() == 5;
        assert byteBuf.readByte() == 6;
        assert byteBuf.readByte() == 7;
        assert byteBuf.readByte() == 8;
    }

    @Test
    public void writeByteTest02() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 4);

        byteBuf.writeByte((byte) 1);
        byteBuf.writeByte((byte) 2);
        byteBuf.writeByte((byte) 3);
        byteBuf.markWriter();

        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;
        byteBuf.markReader();

        byteBuf.writeByte((byte) 4);
        byteBuf.writeByte((byte) 5);
        byteBuf.writeByte((byte) 6);
        byteBuf.markWriter();

        assert byteBuf.readByte() == 4;
        assert byteBuf.readByte() == 5;
        assert byteBuf.readByte() == 6;
        byteBuf.markReader();

        byteBuf.writeByte((byte) 7);
        byteBuf.writeByte((byte) 8);
        byteBuf.writeByte((byte) 9);
        byteBuf.markWriter();

        assert byteBuf.readByte() == 7;
        assert byteBuf.readByte() == 8;
        assert byteBuf.readByte() == 9;
        byteBuf.markReader();
    }

    @Test
    public void writeByteTest02_1() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 2);

        byteBuf.writeByte((byte) 1);
        byteBuf.writeByte((byte) 2);
        byteBuf.writeByte((byte) 3);
        byteBuf.markWriter();

        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;
        byteBuf.markReader();

        byteBuf.writeByte((byte) 4);
        byteBuf.writeByte((byte) 5);
        byteBuf.writeByte((byte) 6);
        byteBuf.markWriter();

        assert byteBuf.readByte() == 4;
        assert byteBuf.readByte() == 5;
        assert byteBuf.readByte() == 6;
        byteBuf.markReader();

        byteBuf.writeByte((byte) 7);
        byteBuf.writeByte((byte) 8);
        byteBuf.writeByte((byte) 9);
        byteBuf.markWriter();

        assert byteBuf.readByte() == 7;
        assert byteBuf.readByte() == 8;
        assert byteBuf.readByte() == 9;
        byteBuf.markReader();
    }

    @Test
    public void writeBytesTest01() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 4);

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });

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
        byteBuf.markReader();
        assert arrayRead[0] == 1;
        assert arrayRead[1] == 2;
        assert arrayRead[2] == 3;
        assert arrayRead[3] == 4;

        byteBuf.writeBytes(new byte[] { 5, 6, 7, 8 });
        byteBuf.markWriter();

        byteBuf.readBytes(arrayRead);
        byteBuf.markReader();
        assert arrayRead[0] == 5;
        assert arrayRead[1] == 6;
        assert arrayRead[2] == 7;
        assert arrayRead[3] == 8;
    }

    @Test
    public void writeBytesTest01_1() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 2);

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });

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
        byteBuf.markReader();
        assert arrayRead[0] == 1;
        assert arrayRead[1] == 2;
        assert arrayRead[2] == 3;
        assert arrayRead[3] == 4;

        byteBuf.writeBytes(new byte[] { 5, 6, 7, 8 });
        byteBuf.markWriter();

        byteBuf.readBytes(arrayRead);
        byteBuf.markReader();
        assert arrayRead[0] == 5;
        assert arrayRead[1] == 6;
        assert arrayRead[2] == 7;
        assert arrayRead[3] == 8;
    }

    @Test
    public void writeBytesTest02() {
        byte[] arrayRead = new byte[6];
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 4);

        byteBuf.writeBytes(new byte[] { 1, 2, 3 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(arrayRead) == 3;
        byteBuf.markReader();
        assert arrayRead[0] == 1;
        assert arrayRead[1] == 2;
        assert arrayRead[2] == 3;

        byteBuf.writeBytes(new byte[] { 4, 5, 6 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(arrayRead) == 3;
        byteBuf.markReader();
        assert arrayRead[0] == 4;
        assert arrayRead[1] == 5;
        assert arrayRead[2] == 6;

        byteBuf.writeBytes(new byte[] { 7, 8, 9 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(arrayRead) == 3;
        byteBuf.markReader();
        assert arrayRead[0] == 7;
        assert arrayRead[1] == 8;
        assert arrayRead[2] == 9;
    }

    @Test
    public void writeBytesTest02_1() {
        byte[] arrayRead = new byte[6];
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 2);

        byteBuf.writeBytes(new byte[] { 1, 2, 3 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(arrayRead) == 3;
        byteBuf.markReader();
        assert arrayRead[0] == 1;
        assert arrayRead[1] == 2;
        assert arrayRead[2] == 3;

        byteBuf.writeBytes(new byte[] { 4, 5, 6 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(arrayRead) == 3;
        byteBuf.markReader();
        assert arrayRead[0] == 4;
        assert arrayRead[1] == 5;
        assert arrayRead[2] == 6;

        byteBuf.writeBytes(new byte[] { 7, 8, 9 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(arrayRead) == 3;
        byteBuf.markReader();
        assert arrayRead[0] == 7;
        assert arrayRead[1] == 8;
        assert arrayRead[2] == 9;
    }

    @Test
    public void writeBytesTest03() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 4);
        byte[] arrayRead = new byte[4];

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(arrayRead) == 4;
        byteBuf.markReader();
        assert arrayRead[0] == 1;
        assert arrayRead[1] == 2;
        assert arrayRead[2] == 3;
        assert arrayRead[3] == 4;

        byteBuf.writeBytes(new byte[] { 5, 6 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(arrayRead) == 2;
        assert arrayRead[0] == 5;
        assert arrayRead[1] == 6;
    }

    @Test
    public void writeBytesTest03_1() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 2);
        byte[] arrayRead = new byte[4];

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(arrayRead) == 4;
        byteBuf.markReader();
        assert arrayRead[0] == 1;
        assert arrayRead[1] == 2;
        assert arrayRead[2] == 3;
        assert arrayRead[3] == 4;

        byteBuf.writeBytes(new byte[] { 5, 6 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(arrayRead) == 2;
        assert arrayRead[0] == 5;
        assert arrayRead[1] == 6;
    }

    @Test
    public void writeBytesTest04() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 4);
        byte[] array = new byte[2];

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(array) == 2;
        byteBuf.markReader();
        assert array[0] == 1;
        assert array[1] == 2;

        assert byteBuf.readBytes(array) == 2;
        byteBuf.markReader();
        assert array[0] == 3;
        assert array[1] == 4;

        //

        byteBuf.writeBytes(new byte[] { 5, 6 });
        byteBuf.markWriter();

        byte[] array2 = new byte[4];
        assert byteBuf.readBytes(array2) == 2;
        byteBuf.markReader();
        assert array2[0] == 5;
        assert array2[1] == 6;
    }

    @Test
    public void writeBytesTest04_1() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 2);
        byte[] array = new byte[2];

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(array) == 2;
        byteBuf.markReader();
        assert array[0] == 1;
        assert array[1] == 2;

        assert byteBuf.readBytes(array) == 2;
        byteBuf.markReader();
        assert array[0] == 3;
        assert array[1] == 4;

        //

        byteBuf.writeBytes(new byte[] { 5, 6 });
        byteBuf.markWriter();

        byte[] array2 = new byte[4];
        assert byteBuf.readBytes(array2) == 2;
        byteBuf.markReader();
        assert array2[0] == 5;
        assert array2[1] == 6;
    }

    @Test
    public void writeBytesTest05() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 4);

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });
        byteBuf.markWriter();

        assert byteBuf.readByte() == 1;
        byteBuf.markReader();
        assert byteBuf.readByte() == 2;
        byteBuf.markReader();
        assert byteBuf.readByte() == 3;
        byteBuf.markReader();
        assert byteBuf.readByte() == 4;
        byteBuf.markReader();

        byteBuf.writeBytes(new byte[] { 5, 6, 7, 8 });
        byteBuf.markWriter();

        assert byteBuf.readByte() == 5;
        assert byteBuf.readByte() == 6;
        assert byteBuf.readByte() == 7;
        assert byteBuf.readByte() == 8;
        byteBuf.markReader();

        byteBuf.writeBytes(new byte[] { 9, 0, 1, 2 });
        byteBuf.markWriter();

        assert byteBuf.readByte() == 9;
        assert byteBuf.readByte() == 0;
        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        byteBuf.markReader();
    }

    @Test
    public void writeBytesTest05_1() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 2);

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });
        byteBuf.markWriter();

        assert byteBuf.readByte() == 1;
        byteBuf.markReader();
        assert byteBuf.readByte() == 2;
        byteBuf.markReader();
        assert byteBuf.readByte() == 3;
        byteBuf.markReader();
        assert byteBuf.readByte() == 4;
        byteBuf.markReader();

        byteBuf.writeBytes(new byte[] { 5, 6, 7, 8 });
        byteBuf.markWriter();

        assert byteBuf.readByte() == 5;
        assert byteBuf.readByte() == 6;
        assert byteBuf.readByte() == 7;
        assert byteBuf.readByte() == 8;
        byteBuf.markReader();

        byteBuf.writeBytes(new byte[] { 9, 0, 1, 2 });
        byteBuf.markWriter();

        assert byteBuf.readByte() == 9;
        assert byteBuf.readByte() == 0;
        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        byteBuf.markReader();
    }

    @Test
    public void writeBytesTest06() {
        byte[] array = new byte[4];
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 4);

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(array, 2, 2) == 2;
        byteBuf.markReader();
        assert array[0] == 0;
        assert array[1] == 0;
        assert array[2] == 1;
        assert array[3] == 2;

        byteBuf.writeBytes(new byte[] { 5, 6 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(array, 1, 3) == 3;
        byteBuf.markReader();
        assert array[0] == 0;
        assert array[1] == 3;
        assert array[2] == 4;
        assert array[3] == 5;

        assert byteBuf.readByte() == 6;
        byteBuf.markReader();
    }

    @Test
    public void writeBytesTest06_1() {
        byte[] array = new byte[4];
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 2);

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(array, 2, 2) == 2;
        byteBuf.markReader();
        assert array[0] == 0;
        assert array[1] == 0;
        assert array[2] == 1;
        assert array[3] == 2;

        byteBuf.writeBytes(new byte[] { 5, 6 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(array, 1, 3) == 3;
        byteBuf.markReader();
        assert array[0] == 0;
        assert array[1] == 3;
        assert array[2] == 4;
        assert array[3] == 5;

        assert byteBuf.readByte() == 6;
        byteBuf.markReader();
    }

    @Test
    public void writeBytesTest07() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(10, 10);

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 });
        byteBuf.markWriter();

        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;
        assert byteBuf.readByte() == 4;
        assert byteBuf.readByte() == 5;
        assert byteBuf.readByte() == 6;
        assert byteBuf.readByte() == 7;
        assert byteBuf.readByte() == 8;
        assert byteBuf.readByte() == 9;
        byteBuf.markReader();

        byteBuf.write(ByteBuffer.wrap(new byte[] { 10, 11, 12 }));
        byteBuf.markWriter();

        byteBuf.write(ByteBuffer.wrap(new byte[] { 13, 14, 15 }));
        byteBuf.markWriter();

        assert byteBuf.getByte(0) == 10;
        assert byteBuf.getByte(1) == 11;
        assert byteBuf.getByte(2) == 12;
        assert byteBuf.getByte(3) == 13;
        assert byteBuf.getByte(4) == 14;
        assert byteBuf.getByte(5) == 15;

        assert byteBuf.readByte() == 10;
        assert byteBuf.readByte() == 11;
        assert byteBuf.readByte() == 12;
        assert byteBuf.readByte() == 13;
        assert byteBuf.readByte() == 14;
        assert byteBuf.readByte() == 15;
        byteBuf.markReader();
    }

    @Test
    public void writeBytesTest07_1() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(10, 2);

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 });
        byteBuf.markWriter();

        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;
        assert byteBuf.readByte() == 4;
        assert byteBuf.readByte() == 5;
        assert byteBuf.readByte() == 6;
        assert byteBuf.readByte() == 7;
        assert byteBuf.readByte() == 8;
        assert byteBuf.readByte() == 9;
        byteBuf.markReader();

        byteBuf.writeByte((byte) 10);
        byteBuf.writeByte((byte) 11);
        byteBuf.writeByte((byte) 12);
        byteBuf.markWriter();

        byteBuf.writeByte((byte) 13);
        byteBuf.writeByte((byte) 14);
        byteBuf.writeByte((byte) 15);
        byteBuf.markWriter();

        assert byteBuf.getByte(0) == 10;
        assert byteBuf.getByte(1) == 11;
        assert byteBuf.getByte(2) == 12;
        assert byteBuf.getByte(3) == 13;
        assert byteBuf.getByte(4) == 14;
        assert byteBuf.getByte(5) == 15;

        assert byteBuf.readByte() == 10;
        assert byteBuf.readByte() == 11;
        assert byteBuf.readByte() == 12;
        assert byteBuf.readByte() == 13;
        assert byteBuf.readByte() == 14;
        assert byteBuf.readByte() == 15;
        byteBuf.markReader();
    }

    @Test
    public void writeBytesTest08() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(10, 10);

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 });
        byteBuf.markWriter();

        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;
        assert byteBuf.readByte() == 4;
        assert byteBuf.readByte() == 5;
        assert byteBuf.readByte() == 6;
        assert byteBuf.readByte() == 7;
        assert byteBuf.readByte() == 8;
        assert byteBuf.readByte() == 9;
        byteBuf.markReader();

        byteBuf.writeByte((byte) 10);
        byteBuf.writeByte((byte) 11);
        byteBuf.writeByte((byte) 12);
        byteBuf.markWriter();

        byteBuf.writeByte((byte) 13);
        byteBuf.writeByte((byte) 14);
        byteBuf.writeByte((byte) 15);
        byteBuf.markWriter();

        assert byteBuf.getByte(0) == 10;
        assert byteBuf.getByte(1) == 11;
        assert byteBuf.getByte(2) == 12;
        assert byteBuf.getByte(3) == 13;
        assert byteBuf.getByte(4) == 14;
        assert byteBuf.getByte(5) == 15;

        assert byteBuf.readByte() == 10;
        assert byteBuf.readByte() == 11;
        assert byteBuf.readByte() == 12;
        assert byteBuf.readByte() == 13;
        assert byteBuf.readByte() == 14;
        assert byteBuf.readByte() == 15;
        byteBuf.markReader();
    }

    @Test
    public void writeBytesTest08_1() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(10, 2);

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 });
        byteBuf.markWriter();

        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;
        assert byteBuf.readByte() == 4;
        assert byteBuf.readByte() == 5;
        assert byteBuf.readByte() == 6;
        assert byteBuf.readByte() == 7;
        assert byteBuf.readByte() == 8;
        assert byteBuf.readByte() == 9;
        byteBuf.markReader();

        byteBuf.writeByte((byte) 10);
        byteBuf.writeByte((byte) 11);
        byteBuf.writeByte((byte) 12);
        byteBuf.markWriter();

        byteBuf.writeByte((byte) 13);
        byteBuf.writeByte((byte) 14);
        byteBuf.writeByte((byte) 15);
        byteBuf.markWriter();

        assert byteBuf.getByte(0) == 10;
        assert byteBuf.getByte(1) == 11;
        assert byteBuf.getByte(2) == 12;
        assert byteBuf.getByte(3) == 13;
        assert byteBuf.getByte(4) == 14;
        assert byteBuf.getByte(5) == 15;

        assert byteBuf.readByte() == 10;
        assert byteBuf.readByte() == 11;
        assert byteBuf.readByte() == 12;
        assert byteBuf.readByte() == 13;
        assert byteBuf.readByte() == 14;
        assert byteBuf.readByte() == 15;
        byteBuf.markReader();
    }

    @Test
    public void extendTest01() throws NoSuchAlgorithmException {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(256, 512, 64);
        byte[] array1 = byteBuf.array();

        byteBuf.writeBytes(RandomUtils.nextBytes(array1.length));
        array1 = byteBuf.array();
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
        PooledNioByteBuf byteBuf = (PooledNioByteBuf) ByteBufAllocator.DEFAULT.pooledDirectBuffer();
        byteBuf.free();
        assert byteBuf.buffers.isEmpty();

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
        for (int i = 0; i < 500; i++) {
            ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer();
            byteBuf.writeBytes(result);
            byteBuf.free();
            list.add(byteBuf);
        }
    }

    @Test
    public void copyTest01() throws NoSuchAlgorithmException {
        ByteBuf byteBuf1 = ByteBufAllocator.DEFAULT.pooledDirectBuffer();
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
            ByteBufAllocator.DEFAULT.pooledDirectBuffer(-1, 123, 64);
            assert false;
        } catch (IllegalArgumentException e) {
            assert e.getMessage().equals("0 > capacity > maxCapacity ( gt 0 or eq -1)");
        }
    }

    @Test
    public void errorTest02() {
        try {
            ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer();
            byteBuf.getByte(0);
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert e.getMessage().startsWith("read data(1) out of range. readMark(0) <= offset(0) <= writerMark(0)");
        }
    }

    @Test
    public void writeStringTest01() {
        byte[] date = "aaa\nbbb\nccc\n".getBytes(StandardCharsets.US_ASCII);

        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer();
        byteBuf.writeBytes(date);
        byteBuf.markWriter();

        String line1 = byteBuf.readExpect("\n", StandardCharsets.US_ASCII);
        String line2 = byteBuf.readExpect("\n", StandardCharsets.US_ASCII);
        String line3 = byteBuf.readExpect("\n", StandardCharsets.US_ASCII);

        assert line1.equals("aaa");
        assert line2.equals("bbb");
        assert line3.equals("ccc");
    }

    @Test
    public void writeStringTest02() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(16, 4);

        byteBuf.writeBytes("abc1\r\n".getBytes());
        byteBuf.markWriter();

        byteBuf.writeBytes("abc2\r\n".getBytes());
        byteBuf.markWriter();

        String line1 = byteBuf.readExpect("\r\n", StandardCharsets.US_ASCII);
        byteBuf.markReader();

        String line2 = byteBuf.readExpect("\r\n", StandardCharsets.US_ASCII);
        byteBuf.markReader();

        byteBuf.writeBytes("abc3\r\n".getBytes());
        byteBuf.markWriter();

        String line3 = byteBuf.readExpect("\r\n", StandardCharsets.US_ASCII);
        byteBuf.markReader();

        assert line1.equals("abc1");
        assert line2.equals("abc2");
        assert line3.equals("abc3");
    }

    @Test
    public void writeStringTest03() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer(10, 10);

        byteBuf.writeBytes("1234\r\n".getBytes());
        byteBuf.markWriter();
        readTest(byteBuf);

        byteBuf.writeBytes("1234\r\n".getBytes());
        byteBuf.markWriter();
        readTest(byteBuf);

        byteBuf.writeBytes("1234\r\n".getBytes());
        byteBuf.markWriter();
        readTest(byteBuf);

        byteBuf.writeBytes("1234\r\n".getBytes());
        byteBuf.markWriter();
        readTest(byteBuf);
    }

    private void readTest(ByteBuf byteBuf) {
        if (byteBuf.expect("\r\n", StandardCharsets.US_ASCII) >= 0) {
            String str = byteBuf.readExpect("\r\n", StandardCharsets.US_ASCII);
            byteBuf.markReader();
            assert str.equals("1234");
        }
    }
}
