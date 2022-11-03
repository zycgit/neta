package net.hasor.cobble.bytebuf;
import org.junit.Test;

import java.nio.BufferOverflowException;

public class DirectByteBufTest {
    @Test
    public void writeByteTest01() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);

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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);

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
    }

    @Test
    public void writeBytesTest01() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);

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
        assert arrayRead[0] == 1;
        assert arrayRead[1] == 2;
        assert arrayRead[2] == 3;
        assert arrayRead[3] == 4;

        byteBuf.markReader();
        byteBuf.writeBytes(new byte[] { 5, 6, 7, 8 });

        byteBuf.markWriter();
        byteBuf.readBytes(arrayRead);
        assert arrayRead[0] == 5;
        assert arrayRead[1] == 6;
        assert arrayRead[2] == 7;
        assert arrayRead[3] == 8;
    }

    @Test
    public void writeBytesTest02() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);

        byteBuf.writeBytes(new byte[] { 1, 2, 3 });

        byteBuf.markWriter();
        byte[] arrayRead = new byte[6];
        assert byteBuf.readBytes(arrayRead) == 3;
        assert arrayRead[0] == 1;
        assert arrayRead[1] == 2;
        assert arrayRead[2] == 3;

        byteBuf.markReader();
        byteBuf.writeBytes(new byte[] { 4, 5, 6 });

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
}
