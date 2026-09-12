/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.io.*;
import java.nio.charset.Charset;
import java.util.Objects;
/**
 * Sequential {@link InputStream}/{@link DataInput} view over a {@link ByteBuf}.
 * <p>The stream reads from the buffer's current readable region and advances the
 * underlying {@code readerIndex}. Primitive reads use the byte order defined by
 * the wrapped buffer.
 * <p>When {@code releaseOnClose} is enabled, closing the stream also frees the
 * wrapped buffer.
 * @see ByteBufOutputStream
 */
public class ByteBufInputStream extends InputStream implements DataInput {
    private final ByteBuf buffer;
    /** Releases the wrapped buffer when the stream is closed. */
    private final boolean releaseOnClose;
    private boolean       closed;

    /**
     * Creates a new stream which reads data from the specified {@code buffer}
     * starting at the current {@code readerIndex} and ending at the current
     * {@code writerIndex}.
     * @param buffer The buffer which provides the content for this {@link InputStream}.
     */
    public ByteBufInputStream(ByteBuf buffer) {
        this(buffer, false);
    }

    /**
     * Creates a new stream which reads data from the specified {@code buffer}
     * starting at the current {@code readerIndex} and ending at the current
     * {@code writerIndex}.
     * @param buffer The buffer which provides the content for this {@link InputStream}.
     * @param releaseOnClose {@code true} means that when {@link #close()} is called then {@link ByteBuf#free()} will
     * be called on {@code buffer}.
     */
    public ByteBufInputStream(ByteBuf buffer, boolean releaseOnClose) {
        Objects.requireNonNull(buffer, "buffer");
        this.releaseOnClose = releaseOnClose;
        this.buffer = buffer;
    }

    /** Returns the readable byte count of the wrapped buffer. */
    public int readBytes() {
        return this.buffer.readableBytes();
    }

    /** Closes this stream and optionally frees the wrapped buffer. */
    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            // The Closable interface says "If the stream is already closed then invoking this method has no effect."
            if (this.releaseOnClose && !this.closed) {
                this.closed = true;
                this.buffer.free();
            }
        }
    }

    /** Mark is unsupported. */
    @Override
    public void mark(int readlimit) {

    }

    /** Returns {@code false}; mark/reset is unsupported. */
    @Override
    public boolean markSupported() {
        return false;
    }

    /** Reset is unsupported. */
    @Override
    public void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    /** Returns the remaining readable bytes. */
    @Override
    public int available() {
        return this.buffer.readableBytes();
    }

    /** Reads one byte, or {@code -1} when no data remains. */
    @Override
    public int read() throws IOException {
        int available = this.available();
        if (available == 0) {
            return -1;
        }

        int len = this.buffer.readByte() & 0xff;
        this.buffer.markReader();
        return len;
    }

    /** Reads up to {@code len} bytes into the target array. */
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int available = available();
        if (available == 0) {
            return -1;
        }

        len = Math.min(available, len);
        this.buffer.readBytes(b, off, len);
        this.buffer.markReader();
        return len;
    }

    /** Skips up to {@code n} bytes. */
    @Override
    public long skip(long n) {
        long len;
        if (n > Integer.MAX_VALUE) {
            len = this.skipBytes(Integer.MAX_VALUE);
        } else {
            len = this.skipBytes((int) n);
        }
        this.buffer.markReader();
        return len;
    }

    /** Reads a boolean value. */
    @Override
    public boolean readBoolean() throws IOException {
        if (available() < 1) {
            throw new EOFException();
        }
        int res = this.read();
        this.buffer.markReader();
        return res != 0;
    }

    /** Reads a signed byte. */
    @Override
    public byte readByte() throws IOException {
        int available = available();
        if (available == 0) {
            throw new EOFException();
        }
        byte res = this.buffer.readByte();
        this.buffer.markReader();
        return res;
    }

    /** Reads a UTF-16 char. */
    @Override
    public char readChar() throws IOException {
        if (available() < 2) {
            throw new EOFException();
        }
        char res = (char) this.readShort();
        this.buffer.markReader();
        return res;
    }

    /** Reads a 64-bit floating-point value. */
    @Override
    public double readDouble() throws IOException {
        if (available() < 8) {
            throw new EOFException();
        }
        double res = this.buffer.readFloat64();
        this.buffer.markReader();
        return res;
    }

    /** Reads a 32-bit floating-point value. */
    @Override
    public float readFloat() throws IOException {
        if (available() < 4) {
            throw new EOFException();
        }
        float res = this.buffer.readFloat32();
        this.buffer.markReader();
        return res;
    }

    /** Reads bytes until the array is full. */
    @Override
    public void readFully(byte[] b) throws IOException {
        this.readFully(b, 0, b.length);
    }

    /** Reads exactly {@code len} bytes into the target array slice. */
    @Override
    public void readFully(byte[] b, int off, int len) throws IOException {
        if (available() < len) {
            throw new EOFException();
        }
        this.buffer.readBytes(b, off, len);
        this.buffer.markReader();
    }

    /** Reads a 32-bit signed integer. */
    @Override
    public int readInt() throws IOException {
        if (available() < 4) {
            throw new EOFException();
        }
        int res = this.buffer.readInt32();
        this.buffer.markReader();
        return res;
    }

    /** Reads a line using the buffer default charset. */
    @Override
    public String readLine() {
        String line = this.buffer.readLine();
        this.buffer.markReader();
        return line;
    }

    /** Reads a line using the specified charset. */
    public String readLine(Charset charset) {
        String line = this.buffer.readLine(charset);
        this.buffer.markReader();
        return line;
    }

    /** Reads a 64-bit signed integer. */
    @Override
    public long readLong() throws IOException {
        if (available() < 8) {
            throw new EOFException();
        }
        long res = this.buffer.readInt64();
        this.buffer.markReader();
        return res;
    }

    /** Reads a 16-bit signed integer. */
    @Override
    public short readShort() throws IOException {
        if (available() < 2) {
            throw new EOFException();
        }
        short res = this.buffer.readInt16();
        this.buffer.markReader();
        return res;
    }

    /** Reads a signed 24-bit integer. */
    public int readMedium() throws IOException {
        if (available() < 3) {
            throw new EOFException();
        }
        int res = this.buffer.readInt24();
        this.buffer.markReader();
        return res;
    }

    /** Reads a modified UTF-8 string. */
    @Override
    public String readUTF() throws IOException {
        String utf = DataInputStream.readUTF(this);
        this.buffer.markReader();
        return utf;
    }

    /** Reads an unsigned byte. */
    @Override
    public int readUnsignedByte() throws IOException {
        if (available() < 1) {
            throw new EOFException();
        }
        int res = this.buffer.readUInt8();
        this.buffer.markReader();
        return res;
    }

    /** Reads an unsigned 16-bit integer. */
    @Override
    public int readUnsignedShort() throws IOException {
        if (available() < 2) {
            throw new EOFException();
        }
        int res = this.buffer.readUInt16();
        this.buffer.markReader();
        return res;
    }

    /** Skips up to {@code n} bytes and returns the skipped length. */
    @Override
    public int skipBytes(int n) {
        int nBytes = Math.min(available(), n);
        this.buffer.skipReadableBytes(nBytes);
        this.buffer.markReader();
        return nBytes;
    }
}
