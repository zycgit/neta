package net.hasor.cobble.bytebuf;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.codec.MD5;
import org.junit.Test;

import java.nio.BufferOverflowException;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

public class PooledDirectByteBufTest {
    @Test
    public void writeByteTest01() {
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
    }

    @Test
    public void writeBytesTest01() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 2);

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });
        byteBuf.markWriter();

        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;
        assert byteBuf.readByte() == 4;

        byte[] arrayRead = new byte[6];
        byteBuf.resetReader();
        byteBuf.readBytes(arrayRead);
        assert arrayRead[0] == 1;
        assert arrayRead[1] == 2;
        assert arrayRead[2] == 3;
        assert arrayRead[3] == 4;
        byteBuf.markReader();

        byteBuf.writeBytes(new byte[] { 5, 6, 7, 8 });
        byteBuf.markWriter();
        assert byteBuf.readByte() == 5;
        assert byteBuf.readByte() == 6;
        assert byteBuf.readByte() == 7;
        assert byteBuf.readByte() == 8;

        byteBuf.resetReader();
        byteBuf.readBytes(arrayRead);
        assert arrayRead[0] == 5;
        assert arrayRead[1] == 6;
        assert arrayRead[2] == 7;
        assert arrayRead[3] == 8;
    }

    @Test
    public void writeBytesTest02() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 2);

        byteBuf.writeBytes(new byte[] { 1, 2, 3 });
        byteBuf.markWriter();

        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;

        byteBuf.resetReader();
        byte[] arrayRead = new byte[6];
        assert byteBuf.readBytes(arrayRead) == 3;
        assert arrayRead[0] == 1;
        assert arrayRead[1] == 2;
        assert arrayRead[2] == 3;
        byteBuf.markReader();

        byteBuf.writeBytes(new byte[] { 4, 5, 6 });
        byteBuf.markWriter();

        assert byteBuf.readByte() == 4;
        assert byteBuf.readByte() == 5;
        assert byteBuf.readByte() == 6;

        byteBuf.resetReader();
        assert byteBuf.readBytes(arrayRead) == 3;
        assert arrayRead[0] == 4;
        assert arrayRead[1] == 5;
        assert arrayRead[2] == 6;
    }

    @Test
    public void writeBytesTest03() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(4, 2);

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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.pooledDirectBuffer(256, 512, 64);
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
        for (int i = 0; i < 1000; i++) {
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
}
