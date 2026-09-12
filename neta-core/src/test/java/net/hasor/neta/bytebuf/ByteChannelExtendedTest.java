/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Extended ByteChannel interface tests: isOpen(), read(), write(), close(),
 * flush(), and interaction with different ByteBuf types.
 */
public class ByteChannelExtendedTest {

    // ========================================================================
    // isOpen() / close()
    // ========================================================================

    @Test
    public void isOpen_newBuffer_returnsTrue() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        assert buf.isOpen();
        buf.release();
    }

    @Test
    public void isOpen_afterRelease_returnsFalse() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        buf.release();
        assert !buf.isOpen();
    }

    @Test
    public void isOpen_afterClose_returnsFalse() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer();
        buf.close();
        assert !buf.isOpen();
    }

    @Test
    public void isOpen_pooledBuffer_works() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.pooledBuffer();
        assert buf.isOpen();
        buf.close();
        assert !buf.isOpen();
    }

    @Test
    public void isOpen_EMPTY_alwaysTrue() {
        assert ByteBuf.EMPTY.isOpen();
    }

    // ========================================================================
    // write(ByteBuffer) — WritableByteChannel
    // ========================================================================

    @Test
    public void write_heapBuffer_writesAll() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        ByteBuffer src = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5 });
        int written = buf.write(src);
        assert written == 5;
        buf.flush();

        assert buf.readableBytes() == 5;
        assert buf.readByte() == 1;
        assert buf.readByte() == 2;
        assert buf.readByte() == 3;
        assert buf.readByte() == 4;
        assert buf.readByte() == 5;
        buf.release();
    }

    @Test
    public void write_directBuffer_writesAll() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer();
        ByteBuffer src = ByteBuffer.allocateDirect(3);
        src.put((byte) 10).put((byte) 20).put((byte) 30);
        ((java.nio.Buffer) src).flip();

        int written = buf.write(src);
        assert written == 3;
        buf.flush();

        assert buf.readByte() == 10;
        assert buf.readByte() == 20;
        assert buf.readByte() == 30;
        buf.release();
    }

    @Test
    public void write_partialByteBuffer_respectsRemaining() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        ByteBuffer src = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5 });
        ((java.nio.Buffer) src).position(2); // remaining = 3

        int written = buf.write(src);
        assert written == 3;
        buf.flush();

        assert buf.readableBytes() == 3;
        assert buf.readByte() == 3;
        assert buf.readByte() == 4;
        assert buf.readByte() == 5;
        buf.release();
    }

    @Test
    public void write_emptyByteBuffer_writesZero() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        ByteBuffer src = ByteBuffer.allocate(0);

        int written = buf.write(src);
        assert written == 0;
        buf.release();
    }

    // ========================================================================
    // read(ByteBuffer) — ReadableByteChannel
    // ========================================================================

    @Test
    public void read_heapBuffer_readsAll() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        buf.writeBytes(new byte[] { 10, 20, 30, 40 });
        buf.flush();

        ByteBuffer dst = ByteBuffer.allocate(4);
        int read = buf.read(dst);
        assert read == 4;
        assert dst.get(0) == 10;
        assert dst.get(1) == 20;
        assert dst.get(2) == 30;
        assert dst.get(3) == 40;
        buf.release();
    }

    @Test
    public void read_smallerDst_readsPartial() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        buf.writeBytes(new byte[] { 1, 2, 3, 4, 5 });
        buf.flush();

        ByteBuffer dst = ByteBuffer.allocate(3);
        int read = buf.read(dst);
        assert read == 3;
        assert dst.get(0) == 1;
        assert dst.get(1) == 2;
        assert dst.get(2) == 3;
        assert buf.readableBytes() == 2;
        buf.release();
    }

    @Test
    public void read_largerDst_readsOnlyAvailable() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        buf.writeBytes(new byte[] { 7, 8 });
        buf.flush();

        ByteBuffer dst = ByteBuffer.allocate(10);
        int read = buf.read(dst);
        assert read == 2;
        assert dst.get(0) == 7;
        assert dst.get(1) == 8;
        buf.release();
    }

    @Test
    public void read_noReadableBytes_readsZero() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        // No data written, no marks — readableBytes = 0
        ByteBuffer dst = ByteBuffer.allocate(5);
        int read = buf.read(dst);
        assert read == 0;
        buf.release();
    }

    // ========================================================================
    // flush() — marks both reader and writer
    // ========================================================================

    @Test
    public void flush_marksWriterAndReader() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();
        buf.writeByte((byte) 1);
        buf.writeByte((byte) 2);

        // Before flush: readableBytes = markedWriterIndex - readerIndex = 0
        assert buf.readableBytes() == 0;

        buf.flush();
        // After flush: markedWriterIndex = writerIndex = 2, readableBytes = 2
        assert buf.readableBytes() == 2;

        buf.readByte(); // read 1
        buf.flush(); // marks reader at current position
        assert buf.readableBytes() == 1; // only byte 2 remains
        buf.release();
    }

    // ========================================================================
    // write → flush → read cycle with pooled buffer
    // ========================================================================

    @Test
    public void pooledBuffer_writeFlushReadCycle() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.pooledBuffer();

        ByteBuffer src = ByteBuffer.wrap("Hello".getBytes(StandardCharsets.UTF_8));
        buf.write(src);
        buf.flush();

        byte[] dst = new byte[5];
        buf.readBytes(dst);
        assert new String(dst, StandardCharsets.UTF_8).equals("Hello");
        buf.close();
    }

    @Test
    public void directBuffer_writeFlushReadCycle() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer();

        ByteBuffer src = ByteBuffer.wrap(new byte[] { 0x0A, 0x0B, 0x0C });
        buf.write(src);
        buf.flush();

        assert buf.readByte() == 0x0A;
        assert buf.readByte() == 0x0B;
        assert buf.readByte() == 0x0C;
        buf.close();
    }

    // ========================================================================
    // Multiple writes before flush
    // ========================================================================

    @Test
    public void multipleWrites_singleFlush_allReadable() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer();

        buf.write(ByteBuffer.wrap(new byte[] { 1, 2 }));
        buf.write(ByteBuffer.wrap(new byte[] { 3, 4 }));
        buf.write(ByteBuffer.wrap(new byte[] { 5 }));
        buf.flush();

        assert buf.readableBytes() == 5;
        for (int i = 1; i <= 5; i++) {
            assert buf.readByte() == i;
        }
        buf.close();
    }

    // ========================================================================
    // Ring buffer ByteChannel integration
    // ========================================================================

    @Test
    public void ringBuffer_writeFlushRead_cycle() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.ringHeapBuffer(8);

        ByteBuffer src = ByteBuffer.wrap(new byte[] { 10, 20, 30 });
        int written = buf.write(src);
        assert written == 3;
        buf.flush();

        assert buf.readableBytes() == 3;
        assert buf.readByte() == 10;
        assert buf.readByte() == 20;
        assert buf.readByte() == 30;
        buf.close();
    }
}
