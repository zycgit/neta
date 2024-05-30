package net.hasor.neta.bytebuf;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.codec.MD5;
import org.junit.Test;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;

public class WrapByteBufferTest {
    @Test
    public void basicTest01() {
        ByteBuf byteBuf1 = ByteBuf.wrap(ByteBuffer.allocate(111));
        assert byteBuf1.capacity() == 111;
        assert !byteBuf1.isDirect();
        assert byteBuf1.toString().startsWith("WrapByteBuffer[rMark=");
        byteBuf1.free();

        ByteBuf byteBuf2 = ByteBuf.wrap(ByteBuffer.allocateDirect(111));
        assert byteBuf2.capacity() == 111;
        assert byteBuf2.isDirect();
        assert byteBuf2.toString().startsWith("WrapByteBuffer[rMark=");
        byteBuf2.free();
    }

    @Test
    public void basicTest02() {
        ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }));

        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;
        assert byteBuf.readByte() == 4;
        byteBuf.markReader();

        byteBuf.resetReader();
        assert byteBuf.readableBytes() == 4;
    }

    @Test
    public void basicTest03() {
        byte[] cacheData = RandomUtils.nextBytes(8192);
        ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.wrap(new byte[1024]), true);
        assert byteBuf.writeBytes(cacheData) == 1024;
    }

    @Test
    public void basicTest04() {
        ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.wrap(new byte[4]), true);
        ByteBuffer data = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 });
        data.flip();

        assert data.position() == 0;
        assert data.limit() == 0;
        assert data.capacity() == 4;
        assert byteBuf.writeBuffer(data) == 0;
        byteBuf.markWriter();
        assert data.position() == 0;
        assert data.limit() == 0;
        assert data.capacity() == 4;
    }

    @Test
    public void writeByte_1_1() {
        ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }), true);

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
    }

    @Test
    public void writeBytes_1_1() {
        ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }));

        // not markIndex yet
        try {
            byteBuf.writeByte((byte) 5);
            assert false;
        } catch (BufferOverflowException e) {
            assert true;
        }

        byte[] arrayRead = new byte[6];
        byteBuf.readBytes(arrayRead);
        byteBuf.markReader();
        assert arrayRead[0] == 1;
        assert arrayRead[1] == 2;
        assert arrayRead[2] == 3;
        assert arrayRead[3] == 4;
    }

    @Test
    public void writeBytes_2_1() {
        byte[] arrayRead = new byte[6];
        ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.wrap(new byte[4]), true);

        byteBuf.writeBytes(new byte[] { 1, 2, 3 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(arrayRead) == 3;
        byteBuf.markReader();
        assert arrayRead[0] == 1;
        assert arrayRead[1] == 2;
        assert arrayRead[2] == 3;

        byteBuf.writeBytes(new byte[] { 4 });
        byteBuf.markWriter();

        assert byteBuf.readBytes(arrayRead) == 1;
        byteBuf.markReader();
        assert arrayRead[0] == 4;
    }

    @Test
    public void writeBytes_3_1() {
        ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.wrap(new byte[4]), true);

        assert byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 }, 1, 2) == 2;
        byteBuf.markWriter();

        assert byteBuf.readByte() == 2;
        byteBuf.markReader();
        assert byteBuf.readByte() == 3;

        assert byteBuf.writeBytes(new byte[] { 5, 6, 7, 8 }, 1, 2) == 2;
        byteBuf.markWriter();

        assert byteBuf.readByte() == 6;
        assert byteBuf.readByte() == 7;
        byteBuf.markReader();
    }

    @Test
    public void writeBuffer_1_1() {
        ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.wrap(new byte[4]), true);
        ByteBuffer data = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 });

        assert data.position() == 0;
        assert data.limit() == 4;
        assert data.capacity() == 4;
        byteBuf.writeBuffer(data);
        byteBuf.markWriter();
        assert data.position() == 4;
        assert data.limit() == 4;
        assert data.capacity() == 4;

        assert byteBuf.getByte(0) == 1;
        assert byteBuf.getByte(1) == 2;
        assert byteBuf.getByte(2) == 3;
        assert byteBuf.getByte(3) == 4;

        assert byteBuf.asByteArray()[0] == 1;
        assert byteBuf.asByteArray()[1] == 2;
        assert byteBuf.asByteArray()[2] == 3;
        assert byteBuf.asByteArray()[3] == 4;

        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;
        assert byteBuf.readByte() == 4;
        byteBuf.resetReader();
    }

    @Test
    public void writeBuffer_2_1() {
        ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }));

        ByteBuffer alloc1 = ByteBuffer.allocate(4);
        assert alloc1.limit() == 4;
        assert alloc1.position() == 0;
        assert byteBuf.getBuffer(0, alloc1) == 4;
        assert alloc1.limit() == 4;
        assert alloc1.position() == 4;

        assert alloc1.array()[0] == 1;
        assert alloc1.array()[1] == 2;
        assert alloc1.array()[2] == 3;
        assert alloc1.array()[3] == 4;

        try {
            alloc1.get();
            assert false;
        } catch (BufferUnderflowException e) {
            assert true;
        }
        assert alloc1.remaining() == 0;
        alloc1.flip();
        assert alloc1.remaining() == 4;
        assert alloc1.get() == 1;
        assert alloc1.get() == 2;
        assert alloc1.get() == 3;
        assert alloc1.get() == 4;
        assert alloc1.remaining() == 0;

        assert byteBuf.asByteArray()[0] == 1;
        assert byteBuf.asByteArray()[1] == 2;
        assert byteBuf.asByteArray()[2] == 3;
        assert byteBuf.asByteArray()[3] == 4;

        ByteBuffer alloc2 = ByteBuffer.allocate(4);
        assert byteBuf.readBuffer(alloc2) == 4;
        assert alloc2.array()[0] == 1;
        assert alloc2.array()[1] == 2;
        assert alloc2.array()[2] == 3;
        assert alloc2.array()[3] == 4;

        byteBuf.resetReader();
        ByteBuffer alloc3 = ByteBuffer.allocate(4);
        assert alloc3.limit() == 4;
        assert alloc3.position() == 0;
        assert alloc3.remaining() == 4;
        alloc3.position(1);
        assert byteBuf.readBuffer(alloc3, 2) == 2;
        assert alloc3.limit() == 4;
        assert alloc3.position() == 3;
        assert alloc3.remaining() == 1;
        alloc3.flip();
        assert alloc3.limit() == 3;
        assert alloc3.position() == 0;
        assert alloc3.remaining() == 3;

        assert alloc3.get() == 0;
        assert alloc3.get() == 1;
        assert alloc3.get() == 2;
        try {
            alloc3.get();
            assert false;
        } catch (BufferUnderflowException e) {
            assert true;
        }
    }

    @Test
    public void writeBuffer_3_1() {
        ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }));

        ByteBuffer alloc1 = ByteBuffer.allocate(4);
        assert byteBuf.readableBytes() == 4;
        assert byteBuf.getBuffer(0, alloc1) == 4;
        assert byteBuf.readableBytes() == 4;

        ByteBuffer alloc2 = ByteBuffer.allocate(4);
        assert byteBuf.readableBytes() == 4;
        assert byteBuf.readBuffer(alloc2) == 4;
        assert byteBuf.readableBytes() == 0;
    }

    @Test
    public void writeBuf_1_1() {
        ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.wrap(new byte[4]), true);
        ByteBuf data = ByteBuf.wrap(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }));

        assert data.readableBytes() == 4;
        assert data.writableBytes() == 0;
        assert byteBuf.readableBytes() == 0;
        assert byteBuf.writableBytes() == 4;
        byteBuf.writeBuffer(data);
        assert byteBuf.readableBytes() == 0;
        assert byteBuf.writableBytes() == 0;
        byteBuf.markWriter();
        assert data.readableBytes() == 0;
        assert data.writableBytes() == 0;
        assert byteBuf.readableBytes() == 4;
        assert byteBuf.writableBytes() == 0;

        assert byteBuf.getByte(0) == 1;
        assert byteBuf.getByte(1) == 2;
        assert byteBuf.getByte(2) == 3;
        assert byteBuf.getByte(3) == 4;

        assert byteBuf.asByteArray()[0] == 1;
        assert byteBuf.asByteArray()[1] == 2;
        assert byteBuf.asByteArray()[2] == 3;
        assert byteBuf.asByteArray()[3] == 4;

        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;
        assert byteBuf.readByte() == 4;
        byteBuf.resetReader();
    }

    @Test
    public void writeBuf_2_1() {
        ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }));

        ByteBuf alloc1 = ByteBufAllocator.DEFAULT.ringHeapBuffer(4);
        assert alloc1.readableBytes() == 0;
        assert alloc1.writableBytes() == 4;
        assert byteBuf.getBuffer(0, alloc1) == 4;
        assert alloc1.readableBytes() == 0;
        assert alloc1.writableBytes() == 0;
        alloc1.markWriter();
        assert alloc1.readableBytes() == 4;
        assert alloc1.writableBytes() == 0;

        assert alloc1.readByte() == 1;
        assert alloc1.readByte() == 2;
        assert alloc1.readByte() == 3;
        assert alloc1.readByte() == 4;

        try {
            alloc1.readByte();
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert true;
        }

        assert alloc1.readableBytes() == 0;
        assert alloc1.writableBytes() == 0;
        alloc1.markReader();
        assert alloc1.readableBytes() == 0;
        assert alloc1.writableBytes() == 4;

        assert byteBuf.asByteArray()[0] == 1;
        assert byteBuf.asByteArray()[1] == 2;
        assert byteBuf.asByteArray()[2] == 3;
        assert byteBuf.asByteArray()[3] == 4;

        ByteBuf alloc2 = ByteBufAllocator.DEFAULT.ringHeapBuffer(4);
        assert byteBuf.readBuffer(alloc2) == 4;
        alloc2.markWriter();
        assert alloc2.asByteArray()[0] == 1;
        assert alloc2.asByteArray()[1] == 2;
        assert alloc2.asByteArray()[2] == 3;
        assert alloc2.asByteArray()[3] == 4;

        byteBuf.resetReader();
        ByteBuf alloc3 = ByteBufAllocator.DEFAULT.ringHeapBuffer(4);
        assert alloc3.readableBytes() == 0;
        assert alloc3.writableBytes() == 4;
        alloc3.skipWritableBytes(1);
        assert byteBuf.readBuffer(alloc3, 2) == 2;
        assert alloc3.readableBytes() == 0;
        assert alloc3.writableBytes() == 1;
        alloc3.markWriter();
        assert alloc3.readableBytes() == 3;
        assert alloc3.writableBytes() == 1;

        assert alloc3.readByte() == 0;
        assert alloc3.readByte() == 1;
        assert alloc3.readByte() == 2;
        try {
            alloc3.readByte();
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert true;
        }
    }

    @Test
    public void freeTest01() {
        WrapByteBuffer byteBuf = (WrapByteBuffer) ByteBuf.wrap(ByteBuffer.allocate(111), true);
        byteBuf.free();

        assert byteBuf.target == null;

        try {
            byteBuf.writeByte((byte) 5);
            assert false;
        } catch (IllegalStateException e) {
            assert e.getMessage().equals("has been released.");
        }
    }

    @Test
    public void copyTest01() throws NoSuchAlgorithmException {
        WrapByteBuffer byteBuf1 = (WrapByteBuffer) ByteBuf.wrap(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }));
        WrapByteBuffer byteBuf2 = byteBuf1.copy();

        assert byteBuf1.target != byteBuf2.target;
        assert byteBuf1.target.capacity() == byteBuf2.target.capacity();

        String hash1 = MD5.encodeMD5(byteBuf1.asByteArray());
        String hash2 = MD5.encodeMD5(byteBuf2.asByteArray());
        assert hash1.equals(hash2);
    }

    @Test
    public void copyTest02() {
        ByteBuf byteBuf1 = ByteBuf.wrap(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }));

        assert byteBuf1.readByte() == 1;
        assert byteBuf1.readByte() == 2;

        ByteBuf byteBuf2 = byteBuf1.copy();
        assert byteBuf2.readByte() == 3;
        assert byteBuf2.readByte() == 4;

        assert byteBuf1.readByte() == 3;
        assert byteBuf1.readByte() == 4;
    }

    @Test
    public void errorTest01() {
        try {
            ByteBuf.wrap((ByteBuffer) null);
            assert false;
        } catch (NullPointerException e) {
            assert e.getMessage().equals("buffer is null.");
        }
    }

    @Test
    public void errorTest02() {
        try {
            ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.allocate(4), true);
            byteBuf.getByte(0);
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert e.getMessage().startsWith("read out of range. index: 0, length: 1 (expected: 0 ~ 0)");
        }

        try {
            ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.allocate(4), true);
            byteBuf.writeByte((byte) 1);
            byteBuf.getByte(0);
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert e.getMessage().startsWith("read out of range. index: 0, length: 1 (expected: 0 ~ 0)");
        }
    }

    @Test
    public void errorTest03() {
        try {
            ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }));
            byteBuf.readInt64();
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert e.getMessage().startsWith("read out of range. length: 8 (expected: 0 ~ 4)");
        }
    }

    @Test
    public void errorTest06() {
        ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }));

        try {
            byteBuf.getByte(6);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().startsWith("read out of range. index: 6, length: 1 (expected: 0 ~ 4)");
        }

        try {
            byteBuf.getBytes(2, new byte[6], 1, 5);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().startsWith("read out of range. index: 2, length: 5 (expected: 0 ~ 4)");
        }
    }

    @Test
    public void writeStringTest01() {
        byte[] date = "aaa\nbbb\nccc\n".getBytes(StandardCharsets.US_ASCII);

        ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.wrap(new byte[1024]), true);
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
        ByteBuf byteBuf = ByteBuf.wrap(ByteBuffer.wrap(new byte[1024]), true);

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
}