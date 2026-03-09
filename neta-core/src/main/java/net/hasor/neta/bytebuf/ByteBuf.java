/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
import net.hasor.cobble.ref.RecycleObjectPool;

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
        WrapArrayBuffer buf = RecycleObjectPool.get(WrapArrayBuffer.RECYCLE_INDEX, WrapArrayBuffer.RECYCLE_HANDLER);
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
        WrapByteBuffer buf = RecycleObjectPool.get(WrapByteBuffer.RECYCLE_INDEX, WrapByteBuffer.RECYCLE_HANDLER);
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
        int len = expect.getBytes(charset).length;
        int readableBytes = this.readableBytes();

        if (readableBytes >= len) {
            int loopCount = readableBytes - len;
            for (int i = 0; i <= loopCount; i++) {
                String dat = this.getString(i, len, charset);
                if (dat.equals(expect)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Finds the next line break without changing readerIndex. */
    default int expectLine() {
        int available = this.readableBytes();
        if (available == 0) {
            return -1;
        }

        int findIndex = -1;
        for (int i = 0; i < available; i++) {
            if (this.getUInt8(i) == '\n') {
                if (i > 0 && this.getUInt8(i - 1) == '\r') {
                    findIndex = i - 1;
                } else {
                    findIndex = i;
                }
                break;
            }
        }

        return findIndex;
    }

    /** Returns true when a full line terminator is available. */
    default boolean hasLine() {
        return expectLine() >= 0;
    }

    /** Reads one ASCII line. */
    default String readLine() {
        return this.readLine(StandardCharsets.US_ASCII);
    }

    /** Reads one line using the given charset. */
    default String readLine(Charset charset) {
        int available = this.readableBytes();
        if (available == 0) {
            return null;
        }

        int findIndex = -1;
        int skipLength = -1;
        for (int i = 0; i < available; i++) {
            if (this.getUInt8(i) == '\n') {
                if (i > 0 && this.getUInt8(i - 1) == '\r') {
                    findIndex = i - 1;
                    skipLength = 2;
                } else {
                    findIndex = i;
                    skipLength = 1;
                }
                break;
            }
        }

        if (findIndex >= 0) {
            String str = this.readString(findIndex, charset);
            this.skipReadableBytes(skipLength);
            return str;
        } else {
            return null;
        }
    }

    /** Finds the next occurrence of the expected character without changing readerIndex. */
    default int expect(char expect, Charset charset) {
        return expect(String.valueOf(expect), charset);
    }

    /**
     * Reads from the current position until the first expected string is reached.
     */
    default String readExpect(String expect, Charset charset) {
        int readLen;
        if ((readLen = this.expect(expect, charset)) >= 0) {
            String str = readString(readLen, charset);
            this.skipReadableBytes(expect.getBytes(charset).length);
            return str;
        } else {
            return null;
        }
    }

    /**
     * Reads from the current position until the first expected character is reached.
     */
    default String readExpect(char expect, Charset charset) {
        return readExpect(String.valueOf(expect), charset);
    }

    /** Finds the last occurrence of the expected string without changing readerIndex. */
    default int expectLast(String expect, Charset charset) {
        int len = expect.getBytes(charset).length;
        int readableBytes = this.readableBytes();

        if (readableBytes >= len) {
            int loopCount = readableBytes - len;
            for (int i = loopCount; i >= 0; i--) {
                String dat = this.getString(i, len, charset);
                if (dat.equals(expect)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Finds the last occurrence of the expected character without changing readerIndex. */
    default int expectLast(char expect, Charset charset) {
        return expectLast(String.valueOf(expect), charset);
    }

    /**
     * Reads from the current position until the last expected string is reached.
     */
    default String readExpectLast(String expect, Charset charset) {
        int readLen = -1;
        if ((readLen = this.expectLast(expect, charset)) >= 0) {
            String str = readString(readLen, charset);
            this.skipReadableBytes(expect.getBytes(charset).length);
            return str;
        } else {
            return null;
        }
    }

    /**
     * Reads from the current position until the last expected character is reached.
     */
    default String readExpectLast(char expect, Charset charset) {
        return readExpectLast(String.valueOf(expect), charset);
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
