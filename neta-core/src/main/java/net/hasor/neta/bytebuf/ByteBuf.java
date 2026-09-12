/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Mutable byte buffer abstraction used throughout Neta codecs and transports.
 * <p>A {@code ByteBuf} keeps two moving cursors plus two marks:
 * <ul>
 *   <li>{@code readerIndex}: next readable byte.</li>
 *   <li>{@code writerIndex}: next writable position.</li>
 *   <li>{@code markedReaderIndex}: discard boundary used by {@link #markReader()} and
 *       {@link #resetReader()}.</li>
 *   <li>{@code markedWriterIndex}: committed write boundary used by
 *       {@link #markWriter()} and {@link #resetWriter()}.</li>
 * </ul>
 * <p>The readable region is {@code [readerIndex, markedWriterIndex)} and the
 * writable region is bounded by the current capacity and max capacity rules of
 * the implementation.
 * <p>Implementations may be heap-backed, direct, pooled, wrapped, read-only, or
 * proxy-based, but they all follow the same sequential read/write contract and
 * reference-count lifecycle inherited from {@link ReferenceHolder}.
 */
public interface ByteBuf extends ByteChannel, ReferenceHolder {
    int                 DEFAULT_EXPECT_SCAN_SIZE = 8192;
    ThreadLocal<byte[]> EXPECT_SCAN_BUF          = ThreadLocal.withInitial(() -> new byte[DEFAULT_EXPECT_SCAN_SIZE]);

    ByteBuf EMPTY = new ByteBufProxy(ByteBuf.wrap(new byte[0])) {
        @Override
        public void free() {
        }

        @Override
        public void close() {
        }

        @Override
        public ByteBuf retain() {
            return this;
        }

        @Override
        public ByteBuf retain(int increment) {
            return this;
        }

        @Override
        public boolean release() {
            return false;
        }

        @Override
        public boolean release(int decrement) {
            return false;
        }

        @Override
        public ByteBuf asReadOnly() {
            return this;
        }
    };

    /** Wraps a byte array as a ByteBuf. */
    static ByteBuf wrap(byte[] bytes) {
        return wrap(bytes, false);
    }

    /** Wraps a byte array as a ByteBuf and optionally keeps it writable. */
    static ByteBuf wrap(byte[] bytes, boolean asWrite) {
        Objects.requireNonNull(bytes, "bytes is null.");
        WrapArrayBuffer buf = WrapArrayBuffer.RECYCLER.get();
        buf.initBuffer(bytes, asWrite);
        return buf;
    }

    /** Wraps a ByteBuffer as a ByteBuf. */
    static ByteBuf wrap(ByteBuffer buffer) {
        return wrap(buffer, false);
    }

    /** Wraps a ByteBuffer as a ByteBuf and optionally keeps it writable. */
    static ByteBuf wrap(ByteBuffer buffer, boolean asWrite) {
        Objects.requireNonNull(buffer, "buffer is null.");
        WrapByteBuffer buf = WrapByteBuffer.RECYCLER.get();
        buf.initBuffer(buffer, asWrite);
        return buf;
    }

    /** Returns the {@link ByteBufAllocator} which created this buffer. */
    ByteBufAllocator alloc();

    /** Returns the {@code readerIndex} of this buffer. */
    int readerIndex();

    /** Returns the {@code writerIndex} of this buffer. */
    int writerIndex();

    /** Returns the current capacity limit. */
    int capacity();

    /** Returns the backing byte array when available. */
    byte[] asByteArray();

    /** Returns true when the buffer uses off-heap storage. */
    boolean isDirect();

    /** Returns a copy including the current buffer contents. */
    ByteBuf copy();

    /**
     * Returns a copy of the requested readable range without moving either index.
     */
    ByteBuf copy(int offset, int length);

    /**
     * Returns a slice of the requested readable range without moving either index.
     * The returned slice owns the source storage needed for its lifetime and must
     * be released independently.
     */
    ByteBuf slice(int offset, int length);

    /** Returns a read-only view of this buffer. Write operations on the returned buffer will throw {@link java.nio.ReadOnlyBufferException}. */
    ByteBuf asReadOnly();

    /** Returns the active byte order. */
    ByteOrder order();

    @Override
    ByteBuf retain();

    @Override
    ByteBuf retain(int increment);

    /** Returns a view using the requested byte order. */
    ByteBuf order(ByteOrder newOrder);

    /**
     * Discards bytes before readerIndex.
     */
    void discardReadBytes();

    /**
     * Splits the buffer at the given offset and returns the removed head slice.
     */
    ByteBuf sliceOff(int splitOffset);

    /** Releases the underlying storage. */
    void free();

    default void close() throws IOException {
        this.free();
    }

    /** Returns true when the buffer has been released. */
    boolean isFree();

    /**
     * Returns the number of readable bytes which is equal to
     * {@code (this.markedWriterIndex - this.readerIndex)}.
     */
    int readableBytes();

    /**
     * Returns the number of read bytes which is equal to
     * {@code (readerIndex - markedReaderIndex)}.
     */
    int readBytes();

    /**
     * Returns the number of writable bytes which is equal to
     * {@code (maxCapacity - (writerIndex - markedReaderIndex))}.
     */
    int writableBytes();

    /**
     * Returns the number of Written bytes which is equal to
     * {@code (writerIndex - markedWriterIndex)}.
     */
    int writtenBytes();

    /**
     * Marks the current {@code readerIndex} in this buffer.
     * You can reposition the current {@code readerIndex} to the marked {@code readerIndex} by calling {@link #resetReader()}.
     * The initial value of the marked {@code readerIndex} is {@code 0}.
     */
    ByteBuf markReader();

    /**
     * Marks the current {@code writerIndex} in this buffer.
     * You can reposition the current {@code writerIndex} to the marked {@code writerIndex} by calling {@link #resetWriter()}.
     * The initial value of the marked {@code writerIndex} is {@code 0}.
     */
    ByteBuf markWriter();

    /** Marks both writerIndex and readerIndex. */
    default ByteBuf flush() throws IOException {
        this.markWriter();
        this.markReader();
        return this;
    }

    /** Resets the writer mark and skips all readable bytes. */
    default void clear() {
        this.resetWriter();
        this.skipReadableBytes(this.readableBytes());
        this.markReader();
    }

    /**
     * Repositions the current {@code readerIndex} to the marked
     * {@code readerIndex} in this buffer.
     * @throws IndexOutOfBoundsException if the current {@code writerIndex} is less than the marked {@code readerIndex}
     */
    ByteBuf resetReader();

    /**
     * Repositions the current {@code writerIndex} to the marked
     * {@code writerIndex} in this buffer.
     * @throws IndexOutOfBoundsException if the current {@code readerIndex} is greater than the marked {@code writerIndex}
     */
    ByteBuf resetWriter();

    /** Advances readerIndex by the given length. */
    ByteBuf skipReadableBytes(int length);

    /** Advances writerIndex by the given length. */
    ByteBuf skipWritableBytes(int length);

    /**
     * Writes one byte and advances writerIndex.
     */
    void writeByte(byte n);

    /**
     * Writes the full byte array and advances writerIndex.
     */
    default int writeBytes(byte[] src) {
        return this.writeBytes(src, 0, src.length);
    }

    /**
     * Writes a byte array slice and advances writerIndex.
     */
    int writeBytes(byte[] src, int off, int len);

    /**
     * Writes a 16-bit signed integer.
     */
    void writeInt16(short n);

    /**
     * Writes a 24-bit signed integer.
     */
    void writeInt24(int n);

    /**
     * Writes a 32-bit signed integer.
     */
    void writeInt32(int n);

    /** Writes a 32-bit unsigned integer. */
    void writeUInt32(long n);

    /** Writes a 64-bit signed integer. */
    void writeInt64(long n);

    /** Writes a 32-bit floating point value. */
    void writeFloat32(float n);

    /** Writes a 64-bit floating point value. */
    void writeFloat64(double n);

    /** Writes bytes from the ByteBuffer. */
    default int writeBuffer(ByteBuffer src) {
        return this.writeBuffer(src, src.remaining());
    }

    /** Writes up to len bytes from the ByteBuffer. */
    int writeBuffer(ByteBuffer src, int len);

    /** Writes readable bytes from another ByteBuf. */
    default int writeBuffer(ByteBuf src) {
        return this.writeBuffer(src, src.readableBytes());
    }

    /** Writes up to len bytes from another ByteBuf. */
    int writeBuffer(ByteBuf src, int len);

    /**
     * Encodes a string with the given charset and writes the bytes.
     */
    default int writeString(String string, Charset charset) {
        if (string != null && !string.equals("")) {
            byte[] bytes = string.getBytes(charset);
            writeBytes(bytes);
            return bytes.length;
        } else {
            return 0;
        }
    }

    /** Overwrites one byte at the given offset without changing writerIndex. */
    void setByte(int offset, byte n);

    /** Overwrites bytes at the given offset without changing writerIndex. */
    void setBytes(int offset, byte[] src);

    /** Overwrites a byte array slice at the given offset without changing writerIndex. */
    void setBytes(int offset, byte[] src, int srcOffset, int srcLen);

    /** Overwrites a 16-bit signed integer at the given offset. */
    void setInt16(int offset, short n);

    /** Overwrites a 24-bit signed integer at the given offset. */
    void setInt24(int offset, int n);

    /** Overwrites a 32-bit signed integer at the given offset. */
    void setInt32(int offset, int n);

    /** Overwrites a 64-bit signed integer at the given offset. */
    void setInt64(int offset, long n);

    /** Overwrites a 32-bit floating point value at the given offset. */
    void setFloat32(int offset, float n);

    /** Overwrites a 64-bit floating point value at the given offset. */
    void setFloat64(int offset, double n);

    /** Overwrites bytes from a ByteBuffer at the given offset. */
    default int setBuffer(int offset, ByteBuffer src) {
        return this.setBuffer(offset, src, src.remaining());
    }

    /** Overwrites up to srcLen bytes from a ByteBuffer at the given offset. */
    int setBuffer(int offset, ByteBuffer src, int srcLen);

    /** Overwrites bytes from another ByteBuf at the given offset. */
    default int setBuffer(int offset, ByteBuf src) {
        return this.setBuffer(offset, src, src.readableBytes());
    }

    /** Overwrites up to srcLen bytes from another ByteBuf at the given offset. */
    int setBuffer(int offset, ByteBuf src, int srcLen);

    /**
     * Encodes a string with the given charset and overwrites bytes at the offset.
     */
    default int setString(int offset, String string, Charset charset) {
        if (string != null && !string.equals("")) {
            byte[] bytes = string.getBytes(charset);
            setBytes(offset, bytes);
            return bytes.length;
        } else {
            return 0;
        }
    }

    /** Reads one byte and advances readerIndex. */
    byte readByte();

    /** Reads bytes into the destination array. */
    default int readBytes(byte[] dst) {
        return this.readBytes(dst, 0, dst.length);
    }

    /** Reads bytes into a destination array slice. */
    int readBytes(byte[] dst, int off, int len);

    /** Reads a 16-bit signed integer. */
    short readInt16();

    /** Reads a 24-bit signed integer. */
    int readInt24();

    /** Reads a 32-bit signed integer. */
    int readInt32();

    /** Reads a 64-bit signed integer. */
    long readInt64();

    /** Reads a 32-bit floating point value. */
    float readFloat32();

    /** Reads a 64-bit floating point value. */
    double readFloat64();

    /** Copies readable bytes into the ByteBuffer. */
    default int readBuffer(ByteBuffer dst) {
        return this.readBuffer(dst, Math.min(dst.remaining(), this.readableBytes()));
    }

    /** Copies up to len readable bytes into the ByteBuffer. */
    int readBuffer(ByteBuffer dst, int len);

    /** Copies readable bytes into another ByteBuf. */
    default int readBuffer(ByteBuf dst) {
        return this.readBuffer(dst, Math.min(dst.writableBytes(), this.readableBytes()));
    }

    /** Copies up to len readable bytes into another ByteBuf. */
    int readBuffer(ByteBuf dst, int len);

    /**
     * Reads len bytes, decodes them with the charset, and advances readerIndex.
     */
    default String readString(int len, Charset charset) {
        if (len == 0) {
            return "";
        }

        byte[] b = new byte[len];
        int readBytes = this.readBytes(b);
        if (charset == StandardCharsets.US_ASCII) {
            return new String(b, 0, readBytes);
        } else {
            return new String(b, 0, readBytes, charset);
        }
    }

    /** Reads one byte at the given offset without changing readerIndex. */
    byte getByte(int offset);

    /** Reads bytes at the given offset into the destination array. */
    default int getBytes(int offset, byte[] dst) {
        return getBytes(offset, dst, 0, dst.length);
    }

    /** Reads bytes at the given offset into a destination array slice. */
    int getBytes(int offset, byte[] dst, int dstOffset, int dstLen);

    /** Reads a 16-bit signed integer at the given offset. */
    short getInt16(int offset);

    /** Reads a 24-bit signed integer at the given offset. */
    int getInt24(int offset);

    /** Reads a 32-bit signed integer at the given offset. */
    int getInt32(int offset);

    /** Reads a 64-bit signed integer at the given offset. */
    long getInt64(int offset);

    /** Reads a 32-bit floating point value at the given offset. */
    float getFloat32(int offset);

    /** Reads a 64-bit floating point value at the given offset. */
    double getFloat64(int offset);

    /** Copies bytes at the offset into the ByteBuffer. */
    default int getBuffer(int offset, ByteBuffer dst) {
        return this.getBuffer(offset, dst, Math.min(dst.remaining(), this.readableBytes()));
    }

    /** Copies up to dstLen bytes at the offset into the ByteBuffer. */
    int getBuffer(int offset, ByteBuffer dst, int dstLen);

    /** Copies bytes at the offset into another ByteBuf. */
    default int getBuffer(int offset, ByteBuf dst) {
        return this.getBuffer(offset, dst, Math.min(dst.writableBytes(), this.readableBytes()));
    }

    /** Copies up to dstLen bytes at the offset into another ByteBuf. */
    int getBuffer(int offset, ByteBuf dst, int dstLen);

    /**
     * Decodes len bytes at the offset without changing readerIndex.
     */
    default String getString(int offset, int len, Charset charset) {
        if (len == 0) {
            return "";
        }

        byte[] b = new byte[len];
        int readBytes = this.getBytes(offset, b);
        if (charset == StandardCharsets.US_ASCII) {
            return new String(b, 0, readBytes);
        } else {
            return new String(b, 0, readBytes, charset);
        }
    }

    /** Reads one unsigned byte. */
    short readUInt8();

    /** Reads a 16-bit unsigned integer. */
    int readUInt16();

    /** Reads a 24-bit unsigned integer. */
    int readUInt24();

    /** Reads a 32-bit unsigned integer. */
    long readUInt32();

    /** Reads one unsigned byte at the offset. */
    short getUInt8(int offset);

    /** Reads a 16-bit unsigned integer at the offset. */
    int getUInt16(int offset);

    /** Reads a 24-bit unsigned integer at the offset. */
    int getUInt24(int offset);

    /** Reads a 32-bit unsigned integer at the offset. */
    long getUInt32(int offset);

    /** Finds the next occurrence of the expected string without changing readerIndex. */
    default int expect(String expect, Charset charset) {
        Objects.requireNonNull(expect, "expect is null.");
        return this.expect(expect.getBytes(charset));
    }

    /** Finds the next occurrence of the expected byte sequence without changing readerIndex. */
    default int expect(byte[] expected) {
        return this.expect(expected, this.readableBytes());
    }

    /** Finds the next occurrence of the expected byte sequence within the scan limit. */
    default int expect(byte[] expected, int maxScanBytes) {
        Objects.requireNonNull(expected, "expected is null.");

        int expectedLength = expected.length;
        if (expectedLength == 0) {
            return 0;
        }

        int scanLength = Math.min(this.readableBytes(), Math.max(0, maxScanBytes));
        if (scanLength < expectedLength) {
            return -1;
        }
        if (expectedLength == 1) {
            return this.expect(expected[0], scanLength);
        }

        byte[] scratch = expectScratch(Math.max(DEFAULT_EXPECT_SCAN_SIZE, expectedLength << 1));
        int carryLength = 0;
        int scanned = 0;

        while (scanned < scanLength) {
            int copyLength = Math.min(scanLength - scanned, scratch.length - carryLength);
            this.getBytes(scanned, scratch, carryLength, copyLength);

            int totalLength = carryLength + copyLength;
            int localIndex = firstIndexOf(scratch, totalLength, expected);
            if (localIndex >= 0) {
                return scanned - carryLength + localIndex;
            }
            if (scanned + copyLength >= scanLength) {
                break;
            }

            carryLength = Math.min(expectedLength - 1, totalLength);
            System.arraycopy(scratch, totalLength - carryLength, scratch, 0, carryLength);
            scanned += copyLength;
        }
        return -1;
    }

    /** Finds the next line break without changing readerIndex. */
    default int expectLine() {
        return this.expectLine(this.readableBytes());
    }

    /** Finds the next line break within the scan limit without changing readerIndex. */
    default int expectLine(int maxScanBytes) {
        int lineFeedIndex = this.expect((byte) '\n', maxScanBytes);
        if (lineFeedIndex < 0) {
            return -1;
        }
        return lineFeedIndex > 0 && this.getUInt8(lineFeedIndex - 1) == '\r' ? lineFeedIndex - 1 : lineFeedIndex;
    }

    /** Returns true when a full line terminator is available. */
    default boolean hasLine() {
        return expectLine() >= 0;
    }

    /** Returns true when a full line terminator is available within the scan limit. */
    default boolean hasLine(int maxScanBytes) {
        return expectLine(maxScanBytes) >= 0;
    }

    /** Reads one ASCII line. */
    default String readLine() {
        return this.readLine(StandardCharsets.US_ASCII);
    }

    /** Reads one line using the given charset. */
    default String readLine(Charset charset) {
        return this.readLine(charset, this.readableBytes());
    }

    /** Reads one line using the given charset within the scan limit. */
    default String readLine(Charset charset, int maxScanBytes) {
        int lineFeedIndex = this.expect((byte) '\n', maxScanBytes);
        if (lineFeedIndex < 0) {
            return null;
        }

        boolean hasCarriageReturn = lineFeedIndex > 0 && this.getUInt8(lineFeedIndex - 1) == '\r';
        int lineLength = hasCarriageReturn ? lineFeedIndex - 1 : lineFeedIndex;
        String str = this.readString(lineLength, charset);
        this.skipReadableBytes(hasCarriageReturn ? 2 : 1);
        return str;
    }

    /** Reads one line as a ByteBuf view without decoding characters. */
    default ByteBuf readLineBuffer() {
        return this.readLineBuffer(this.readableBytes());
    }

    /** Reads one line as a ByteBuf view within the scan limit without decoding characters. */
    default ByteBuf readLineBuffer(int maxScanBytes) {
        int lineFeedIndex = this.expect((byte) '\n', maxScanBytes);
        if (lineFeedIndex < 0) {
            return null;
        }

        boolean hasCarriageReturn = lineFeedIndex > 0 && this.getUInt8(lineFeedIndex - 1) == '\r';
        int lineLength = hasCarriageReturn ? lineFeedIndex - 1 : lineFeedIndex;
        ByteBuf line = this.sliceOff(lineLength);
        this.skipReadableBytes(hasCarriageReturn ? 2 : 1);
        return line;
    }

    /** Finds the next occurrence of the expected character without changing readerIndex. */
    default int expect(char expect, Charset charset) {
        return expect(String.valueOf(expect).getBytes(charset));
    }

    /** Finds the next occurrence of the expected byte without changing readerIndex. */
    default int expect(byte expected) {
        return this.expect(expected, this.readableBytes());
    }

    /** Finds the next occurrence of the expected byte within the scan limit. */
    default int expect(byte expected, int maxScanBytes) {
        int scanLength = Math.min(this.readableBytes(), Math.max(0, maxScanBytes));
        if (scanLength <= 0) {
            return -1;
        }

        byte[] scratch = expectScratch(DEFAULT_EXPECT_SCAN_SIZE);
        int scanned = 0;
        while (scanned < scanLength) {
            int copyLength = Math.min(scanLength - scanned, scratch.length);
            this.getBytes(scanned, scratch, 0, copyLength);
            for (int i = 0; i < copyLength; i++) {
                if (scratch[i] == expected) {
                    return scanned + i;
                }
            }
            scanned += copyLength;
        }
        return -1;
    }

    /**
     * Reads from the current position until the first expected string is reached.
     */
    default String readExpect(String expect, Charset charset) {
        byte[] expected = expect.getBytes(charset);
        int readLen;
        if ((readLen = this.expect(expected)) >= 0) {
            String str = readString(readLen, charset);
            this.skipReadableBytes(expected.length);
            return str;
        } else {
            return null;
        }
    }

    /** Reads from the current position until the first expected byte sequence is reached. */
    default ByteBuf readExpect(byte[] expected) {
        Objects.requireNonNull(expected, "expected is null.");
        int readLen = this.expect(expected);
        if (readLen < 0) {
            return null;
        }

        ByteBuf result = this.sliceOff(readLen);
        this.skipReadableBytes(expected.length);
        return result;
    }

    /**
     * Reads from the current position until the first expected character is reached.
     */
    default String readExpect(char expect, Charset charset) {
        return readExpect(String.valueOf(expect), charset);
    }

    /** Finds the last occurrence of the expected string without changing readerIndex. */
    default int expectLast(String expect, Charset charset) {
        Objects.requireNonNull(expect, "expect is null.");
        return this.expectLast(expect.getBytes(charset));
    }

    /** Finds the last occurrence of the expected byte sequence without changing readerIndex. */
    default int expectLast(byte[] expected) {
        return this.expectLast(expected, this.readableBytes());
    }

    /** Finds the last occurrence of the expected byte sequence within the scan limit. */
    default int expectLast(byte[] expected, int maxScanBytes) {
        Objects.requireNonNull(expected, "expected is null.");

        int expectedLength = expected.length;
        if (expectedLength == 0) {
            return 0;
        }

        int scanLength = Math.min(this.readableBytes(), Math.max(0, maxScanBytes));
        if (scanLength < expectedLength) {
            return -1;
        }
        if (expectedLength == 1) {
            return this.expectLast(expected[0], scanLength);
        }

        byte[] scratch = expectScratch(Math.max(DEFAULT_EXPECT_SCAN_SIZE, expectedLength << 1));
        int carryLength = 0;
        int scanned = 0;
        int lastMatch = -1;

        while (scanned < scanLength) {
            int copyLength = Math.min(scanLength - scanned, scratch.length - carryLength);
            this.getBytes(scanned, scratch, carryLength, copyLength);

            int totalLength = carryLength + copyLength;
            int localIndex = lastIndexOf(scratch, totalLength, expected);
            if (localIndex >= 0) {
                lastMatch = scanned - carryLength + localIndex;
            }
            if (scanned + copyLength >= scanLength) {
                break;
            }

            carryLength = Math.min(expectedLength - 1, totalLength);
            System.arraycopy(scratch, totalLength - carryLength, scratch, 0, carryLength);
            scanned += copyLength;
        }
        return lastMatch;
    }

    /** Finds the last occurrence of the expected character without changing readerIndex. */
    default int expectLast(char expect, Charset charset) {
        return expectLast(String.valueOf(expect).getBytes(charset));
    }

    /** Finds the last occurrence of the expected byte without changing readerIndex. */
    default int expectLast(byte expected) {
        return this.expectLast(expected, this.readableBytes());
    }

    /** Finds the last occurrence of the expected byte within the scan limit. */
    default int expectLast(byte expected, int maxScanBytes) {
        int scanLength = Math.min(this.readableBytes(), Math.max(0, maxScanBytes));
        if (scanLength <= 0) {
            return -1;
        }

        byte[] scratch = expectScratch(DEFAULT_EXPECT_SCAN_SIZE);
        int scanned = 0;
        int lastMatch = -1;
        while (scanned < scanLength) {
            int copyLength = Math.min(scanLength - scanned, scratch.length);
            this.getBytes(scanned, scratch, 0, copyLength);
            for (int i = 0; i < copyLength; i++) {
                if (scratch[i] == expected) {
                    lastMatch = scanned + i;
                }
            }
            scanned += copyLength;
        }
        return lastMatch;
    }

    /**
     * Reads from the current position until the last expected string is reached.
     */
    default String readExpectLast(String expect, Charset charset) {
        int readLen = -1;
        byte[] expected = expect.getBytes(charset);
        if ((readLen = this.expectLast(expected)) >= 0) {
            String str = readString(readLen, charset);
            this.skipReadableBytes(expected.length);
            return str;
        } else {
            return null;
        }
    }

    /** Reads from the current position until the last expected byte sequence is reached. */
    default ByteBuf readExpectLast(byte[] expected) {
        Objects.requireNonNull(expected, "expected is null.");
        int readLen = this.expectLast(expected);
        if (readLen < 0) {
            return null;
        }

        ByteBuf result = this.sliceOff(readLen);
        this.skipReadableBytes(expected.length);
        return result;
    }

    /**
     * Reads from the current position until the last expected character is reached.
     */
    default String readExpectLast(char expect, Charset charset) {
        return readExpectLast(String.valueOf(expect), charset);
    }

    static byte[] expectScratch(int minCapacity) {
        byte[] scratch = EXPECT_SCAN_BUF.get();
        return scratch.length >= minCapacity ? scratch : new byte[minCapacity];
    }

    static int firstIndexOf(byte[] source, int sourceLength, byte[] expected) {
        int expectedLength = expected.length;
        int limit = sourceLength - expectedLength;
        byte first = expected[0];
        for (int i = 0; i <= limit; i++) {
            if (source[i] != first) {
                continue;
            }

            boolean match = true;
            for (int j = 1; j < expectedLength; j++) {
                if (source[i + j] != expected[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    static int lastIndexOf(byte[] source, int sourceLength, byte[] expected) {
        int expectedLength = expected.length;
        int limit = sourceLength - expectedLength;
        byte first = expected[0];
        for (int i = limit; i >= 0; i--) {
            if (source[i] != first) {
                continue;
            }

            boolean match = true;
            for (int j = 1; j < expectedLength; j++) {
                if (source[i + j] != expected[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    /** implements {@link ReadableByteChannel} */
    @Override
    default int read(ByteBuffer dst) {
        return this.readBuffer(dst, Math.min(dst.remaining(), this.readableBytes()));
    }

    /** implements {@link WritableByteChannel} */
    @Override
    default int write(ByteBuffer src) {
        return this.writeBuffer(src, src.remaining());
    }

    /** implements {@link Channel} */
    @Override
    default boolean isOpen() {
        return !this.isFree();
    }
}
