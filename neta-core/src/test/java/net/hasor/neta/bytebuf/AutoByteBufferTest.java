/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;

import org.junit.Test;

import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.codec.MD5;

public class AutoByteBufferTest {
    @Test
    public void basicTest01() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(111);
        assert byteBuf.capacity() == 111;
        assert byteBuf.isDirect();
        assert byteBuf.toString().startsWith("AutoByteBuffer[rMark=");
    }

    @Test
    public void basicTest02() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });
        byteBuf.markWriter();

        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;
        assert byteBuf.readByte() == 4;
        byteBuf.markReader();

        byteBuf.resetReader();
        assert byteBuf.readableBytes() == 0;
    }

    @Test
    public void basicTest03() {
        byte[] cacheData = RandomUtils.nextBytes(8192);
        ByteBuf srcBuf = ByteBufAllocator.DEFAULT.directBuffer(1024);
        assert srcBuf.writeBytes(cacheData) == 1024;
    }

    @Test
    public void basicTest04() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
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
    public void basicTest05() {
        AutoByteBuffer byteBuf = AutoByteBuffer.RECYCLER.get();
        byteBuf.initBuffer(ByteBufUtils.DEFAULT_ALLOCATOR, 10, 5, ByteBufUtils.DEFAULT_ALLOCATOR.jvmBuffer(4));

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });
        assert byteBuf.capacity() == 4;
        assert byteBuf.getMaxCapacity() == 10;

        byteBuf.writeBytes(new byte[] { 5, 6, 7, 8 });
        assert byteBuf.capacity() == 10; // after writer target size is 8, --> final target size is ((8 / 5) + 1) * 5
        assert byteBuf.getMaxCapacity() == 10;

        byteBuf.markWriter();

        assert byteBuf.readByte() == 1;
        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;
        assert byteBuf.readByte() == 4;
        assert byteBuf.capacity() == 10;
        byteBuf.markReader();
        assert byteBuf.capacity() == 5;
        assert byteBuf.getMaxCapacity() == 10;

        assert byteBuf.readByte() == 5;
        assert byteBuf.readByte() == 6;
        assert byteBuf.readByte() == 7;
        assert byteBuf.readByte() == 8;
        byteBuf.markReader();
        assert byteBuf.capacity() == 5;
        assert byteBuf.getMaxCapacity() == 10;
    }

    @Test
    public void writeByte_1_1() {
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
    public void writeByte_1_2() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
        byteBuf.skipWritableBytes(2);
        byteBuf.markWriter();
        byteBuf.skipReadableBytes(2);
        byteBuf.markReader();

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
    public void writeBytes_1_1() {
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
    public void writeBytes_1_2() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
        byteBuf.skipWritableBytes(2);
        byteBuf.markWriter();
        byteBuf.skipReadableBytes(2);
        byteBuf.markReader();

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
    public void writeBytes_2_1() {
        byte[] arrayRead = new byte[6];
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);

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
    public void writeBytes_2_2() {
        byte[] arrayRead = new byte[6];
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
        byteBuf.skipWritableBytes(2);
        byteBuf.markWriter();
        byteBuf.skipReadableBytes(2);
        byteBuf.markReader();

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
    public void writeBytes_3_1() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);

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

        assert byteBuf.writeBytes(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, 1, 4) == 4;
        byteBuf.markWriter();

        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;
        assert byteBuf.readByte() == 4;
        assert byteBuf.readByte() == 5;
        byteBuf.markReader();
    }

    @Test
    public void writeBytes_3_2() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
        byteBuf.skipWritableBytes(2);
        byteBuf.markWriter();
        byteBuf.skipReadableBytes(2);
        byteBuf.markReader();

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

        assert byteBuf.writeBytes(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, 1, 4) == 4;
        byteBuf.markWriter();

        assert byteBuf.readByte() == 2;
        assert byteBuf.readByte() == 3;
        assert byteBuf.readByte() == 4;
        assert byteBuf.readByte() == 5;
        byteBuf.markReader();
    }

    @Test
    public void writeBytes_4_1() {
        byte[] array = new byte[4];
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);

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
    public void writeBytes_4_2() {
        byte[] array = new byte[4];
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
        byteBuf.skipWritableBytes(2);
        byteBuf.markWriter();
        byteBuf.skipReadableBytes(2);
        byteBuf.markReader();

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
    public void writeBuffer_1_1() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
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
    public void writeBuffer_1_2() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
        byteBuf.skipWritableBytes(2);
        byteBuf.markWriter();
        byteBuf.skipReadableBytes(2);
        byteBuf.markReader();

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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
        byteBuf.writeBuffer(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }));
        byteBuf.markWriter();

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
    public void writeBuffer_2_2() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
        byteBuf.skipWritableBytes(2);
        byteBuf.markWriter();
        byteBuf.skipReadableBytes(2);
        byteBuf.markReader();

        byteBuf.writeBuffer(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }));
        byteBuf.markWriter();

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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
        byteBuf.writeBuffer(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }));
        byteBuf.markWriter();

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
    public void writeBuffer_3_2() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
        byteBuf.skipWritableBytes(2);
        byteBuf.markWriter();
        byteBuf.skipReadableBytes(2);
        byteBuf.markReader();

        byteBuf.writeBuffer(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 }));
        byteBuf.markWriter();

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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
        ByteBuf data = ByteBuf.wrap(new byte[] { 1, 2, 3, 4 });

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
    public void writeBuf_1_2() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
        byteBuf.skipWritableBytes(2);
        byteBuf.markWriter();
        byteBuf.skipReadableBytes(2);
        byteBuf.markReader();

        ByteBuf data = ByteBuf.wrap(new byte[] { 1, 2, 3, 4 });

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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
        byteBuf.writeBuffer(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        byteBuf.markWriter();

        ByteBuf alloc1 = ByteBufAllocator.DEFAULT.directBuffer(4);
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

        ByteBuf alloc2 = ByteBufAllocator.DEFAULT.directBuffer(4);
        assert byteBuf.readBuffer(alloc2) == 4;
        alloc2.markWriter();
        assert alloc2.asByteArray()[0] == 1;
        assert alloc2.asByteArray()[1] == 2;
        assert alloc2.asByteArray()[2] == 3;
        assert alloc2.asByteArray()[3] == 4;

        byteBuf.resetReader();
        ByteBuf alloc3 = ByteBufAllocator.DEFAULT.directBuffer(4);
        assert alloc3.readableBytes() == 0;
        assert alloc3.writableBytes() == 4;
        alloc3.skipWritableBytes(1);
        assert byteBuf.readBuffer(alloc3, 2) == 2;
        assert alloc3.readableBytes() == 0;
        assert alloc3.writableBytes() == 1;
        alloc3.markWriter();
        assert alloc3.readableBytes() == 3;
        assert alloc3.writableBytes() == 1;

        alloc3.readByte();
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
    public void writeBuf_2_2() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
        byteBuf.skipWritableBytes(2);
        byteBuf.markWriter();
        byteBuf.skipReadableBytes(2);
        byteBuf.markReader();

        byteBuf.writeBuffer(ByteBuf.wrap(new byte[] { 1, 2, 3, 4 }));
        byteBuf.markWriter();

        ByteBuf alloc1 = ByteBufAllocator.DEFAULT.directBuffer(4);
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

        ByteBuf alloc2 = ByteBufAllocator.DEFAULT.directBuffer(4);
        assert byteBuf.readBuffer(alloc2) == 4;
        alloc2.markWriter();
        assert alloc2.asByteArray()[0] == 1;
        assert alloc2.asByteArray()[1] == 2;
        assert alloc2.asByteArray()[2] == 3;
        assert alloc2.asByteArray()[3] == 4;

        byteBuf.resetReader();
        ByteBuf alloc3 = ByteBufAllocator.DEFAULT.directBuffer(4);
        assert alloc3.readableBytes() == 0;
        assert alloc3.writableBytes() == 4;
        alloc3.skipWritableBytes(1);
        assert byteBuf.readBuffer(alloc3, 2) == 2;
        assert alloc3.readableBytes() == 0;
        assert alloc3.writableBytes() == 1;
        alloc3.markWriter();
        assert alloc3.readableBytes() == 3;
        assert alloc3.writableBytes() == 1;

        alloc3.readByte();
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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(111);
        byteBuf.free();

        try {
            byteBuf.asByteArray();
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
        AutoByteBuffer byteBuf1 = (AutoByteBuffer) ByteBufAllocator.DEFAULT.directBuffer(12);
        byteBuf1.writeBytes(new byte[] { 1, 2, 3, 4 });

        AutoByteBuffer byteBuf2 = byteBuf1.copy();

        assert byteBuf1.target != byteBuf2.target;
        assert byteBuf1.target.capacity() == byteBuf2.target.capacity();

        String hash1 = MD5.encodeMD5(byteBuf1.asByteArray());
        String hash2 = MD5.encodeMD5(byteBuf2.asByteArray());
        assert hash1.equals(hash2);
    }

    @Test
    public void copyTest02() {
        AutoByteBuffer byteBuf1 = (AutoByteBuffer) ByteBufAllocator.DEFAULT.directBuffer(12);
        byteBuf1.writeBytes(new byte[] { 1, 2, 3, 4 });
        byteBuf1.markWriter();

        assert byteBuf1.readByte() == 1;
        assert byteBuf1.readByte() == 2;

        AutoByteBuffer byteBuf2 = byteBuf1.copy();
        assert byteBuf2.readByte() == 3;
        assert byteBuf2.readByte() == 4;

        assert byteBuf1.readByte() == 3;
        assert byteBuf1.readByte() == 4;
    }

    @Test
    public void errorTest01() {
        try {
            ByteBufAllocator.DEFAULT.directBuffer(-1);
            assert false;
        } catch (IllegalArgumentException e) {
            assert e.getMessage().equals("capacity: -1 (expected: >= 0)");
        }
    }

    @Test
    public void errorTest02() {
        try {
            ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
            byteBuf.getByte(0);
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert e.getMessage().startsWith("read out of range. index: 0, length: 1 (expected: 0 ~ 0)");
        }

        try {
            ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
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
            ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(4);
            byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });
            byteBuf.markWriter();
            byteBuf.readInt64();
            assert false;
        } catch (IndexOutOfBoundsException e) {
            assert e.getMessage().startsWith("read out of range. length: 8 (expected: 0 ~ 4)");
        }
    }

    @Test
    public void writeStringTest01() {
        byte[] date = "aaa\nbbb\nccc\n".getBytes(StandardCharsets.US_ASCII);

        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(1024);
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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(1024);

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
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.directBuffer(10);

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

    @Test
    public void expWriteBytes_1() {
        ByteBuf byteBuf = ByteBufAllocator.DEFAULT.heapBuffer(4, 10);

        byteBuf.writeBytes(new byte[] { 1, 2, 3, 4 });
        byteBuf.markWriter();
        byteBuf.writeBytes(new byte[] { 5, 6, 7, 8 });
        byteBuf.markWriter();

        byte[] array = new byte[8];
        assert byteBuf.readBytes(array) == 8;
        byteBuf.markReader();
        assert array[0] == 1;
        assert array[1] == 2;
        assert array[2] == 3;
        assert array[3] == 4;
        assert array[4] == 5;
        assert array[5] == 6;
        assert array[6] == 7;
        assert array[7] == 8;
    }
}
