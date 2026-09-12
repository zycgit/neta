/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.io.EOFException;
import java.io.IOException;
import org.junit.Test;

/**
 * Tests for ByteBufInputStream, ByteBufOutputStream, and ByteBuf.isOpen().
 * Covers bug fixes: #1 isOpen() reversal, #2 writtenBytes(), #3 autoFlush rename, #11 EOF checks.
 */
public class ByteBufStreamTest {

    // ==================== isOpen() tests (fix #1) ====================

    @Test
    public void testIsOpen_newBuffer() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        assert buf.isOpen() : "new buffer should be open";
        buf.release();
    }

    @Test
    public void testIsOpen_afterRelease() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.release();
        assert !buf.isOpen() : "released buffer should not be open";
    }

    @Test
    public void testIsOpen_directBuffer() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.directBuffer(64);
        assert buf.isOpen();
        buf.release();
        assert !buf.isOpen();
    }

    @Test
    public void testIsOpen_wrapBuffer() {
        ByteBuf buf = ByteBuf.wrap(new byte[16], true);
        assert buf.isOpen();
        buf.release();
        assert !buf.isOpen();
    }

    // ==================== writtenBytes() tests (fix #2) ====================

    @Test
    public void testWrittenBytes_basic() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(256);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        out.write(1);
        out.write(2);
        out.write(3);

        // writtenBytes = writerIndex - markedWriterIndex (bytes since last markWriter)
        assert out.writtenBytes() == 3 : "expected 3, got " + out.writtenBytes();

        // after markWriter, writtenBytes resets to 0
        buf.markWriter();
        assert out.writtenBytes() == 0 : "expected 0 after markWriter, got " + out.writtenBytes();
        buf.release();
    }

    @Test
    public void testWrittenBytes_afterMultipleWrites() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(256);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        out.write(new byte[] { 1, 2, 3, 4, 5 });
        assert out.writtenBytes() == 5;

        out.writeInt(42);
        assert out.writtenBytes() == 9 : "expected 9, got " + out.writtenBytes();

        // markWriter resets the counter
        buf.markWriter();
        assert out.writtenBytes() == 0;
        buf.release();
    }

    @Test
    public void testWrittenBytes_differentFromWritableBytes() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(100, 100);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        out.write(new byte[10]);

        // writtenBytes should be 10 (bytes written since last mark)
        // writableBytes should be capacity - 10
        assert out.writtenBytes() == 10;
        assert buf.writableBytes() == buf.capacity() - 10;
        assert out.writtenBytes() != buf.writableBytes();
        buf.release();
    }

    // ==================== autoFlush tests (fix #3) ====================

    @Test
    public void testAutoFlush_enabled() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(256);
        ByteBufOutputStream out = new ByteBufOutputStream(buf, 0); // cacheSize=0 means flush on every write

        out.write(42);
        // autoFlush should mark writer automatically, making data readable
        assert buf.readableBytes() == 1 : "autoFlush should make data readable immediately";
        assert buf.readByte() == 42;
        buf.release();
    }

    @Test
    public void testAutoFlush_disabled() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(256);
        ByteBufOutputStream out = new ByteBufOutputStream(buf); // default cacheSize=-1, no auto-flush

        out.write(42);
        // without autoFlush, data should NOT be readable until markWriter is called
        assert buf.readableBytes() == 0 : "without autoFlush, data should not be readable";
        buf.markWriter();
        assert buf.readableBytes() == 1;
        buf.release();
    }

    // ==================== ByteBufInputStream EOF tests (fix #11) ====================

    private ByteBufInputStream emptyInput() {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(0, 0);
        buf.markWriter();
        return new ByteBufInputStream(buf);
    }

    private ByteBufInputStream inputWithBytes(int count) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(count);
        for (int i = 0; i < count; i++) {
            buf.writeByte((byte) i);
        }
        buf.markWriter();
        return new ByteBufInputStream(buf);
    }

    @Test(expected = EOFException.class)
    public void testEOF_readBoolean() throws IOException {
        emptyInput().readBoolean();
    }

    @Test(expected = EOFException.class)
    public void testEOF_readByte() throws IOException {
        emptyInput().readByte();
    }

    @Test(expected = EOFException.class)
    public void testEOF_readUnsignedByte() throws IOException {
        emptyInput().readUnsignedByte();
    }

    @Test(expected = EOFException.class)
    public void testEOF_readChar() throws IOException {
        emptyInput().readChar();
    }

    @Test(expected = EOFException.class)
    public void testEOF_readShort() throws IOException {
        emptyInput().readShort();
    }

    @Test(expected = EOFException.class)
    public void testEOF_readUnsignedShort() throws IOException {
        emptyInput().readUnsignedShort();
    }

    @Test(expected = EOFException.class)
    public void testEOF_readInt() throws IOException {
        emptyInput().readInt();
    }

    @Test(expected = EOFException.class)
    public void testEOF_readLong() throws IOException {
        emptyInput().readLong();
    }

    @Test(expected = EOFException.class)
    public void testEOF_readFloat() throws IOException {
        emptyInput().readFloat();
    }

    @Test(expected = EOFException.class)
    public void testEOF_readDouble() throws IOException {
        emptyInput().readDouble();
    }

    @Test(expected = EOFException.class)
    public void testEOF_readFully() throws IOException {
        emptyInput().readFully(new byte[4]);
    }

    @Test(expected = EOFException.class)
    public void testEOF_readShort_partialData() throws IOException {
        // Only 1 byte available but short needs 2
        inputWithBytes(1).readShort();
    }

    @Test(expected = EOFException.class)
    public void testEOF_readInt_partialData() throws IOException {
        // Only 2 bytes available but int needs 4
        inputWithBytes(2).readInt();
    }

    @Test(expected = EOFException.class)
    public void testEOF_readLong_partialData() throws IOException {
        // Only 4 bytes available but long needs 8
        inputWithBytes(4).readLong();
    }

    @Test(expected = EOFException.class)
    public void testEOF_readFully_partialData() throws IOException {
        // Only 2 bytes available but need 4
        inputWithBytes(2).readFully(new byte[4]);
    }

    // ==================== ByteBufInputStream normal read tests ====================

    @Test
    public void testInputStream_readInt_success() throws IOException {
        ByteBufInputStream in = inputWithBytes(8);
        // Should not throw
        in.readInt();
        in.readInt();
    }

    @Test
    public void testInputStream_readByte_success() throws IOException {
        ByteBufInputStream in = inputWithBytes(1);
        byte val = in.readByte();
        assert val == 0;
    }

    @Test
    public void testInputStream_readFully_success() throws IOException {
        ByteBufInputStream in = inputWithBytes(4);
        byte[] result = new byte[4];
        in.readFully(result);
        assert result[0] == 0 && result[1] == 1 && result[2] == 2 && result[3] == 3;
    }

    // ==================== allocator validation tests (fix #15) ====================

    @Test(expected = IllegalArgumentException.class)
    public void testAllocator_initCapacityExceedsMax() {
        ByteBufAllocator.DEFAULT.buffer(100, 50);
    }

    @Test
    public void testAllocator_equalCapacity() {
        // Use heapBuffer which creates AutoArrayByteBuf with exact capacity
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(100, 100);
        assert buf.capacity() == 100;
        buf.release();
    }
}
