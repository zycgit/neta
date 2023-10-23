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
import java.io.*;
import java.nio.charset.Charset;
import java.util.Objects;

/**
 * An {@link InputStream} which reads data from a {@link ByteBuf}.
 * This stream implements {@link DataInput} for your convenience.
 * The endianness of the stream is not always big endian but depends on
 * the endianness of the underlying buffer.
 *
 * Implement copy from netty io.netty.buffer.ByteBufInputStream,
 * The ByteBuf implementation is replaced with cobble.bytebuf
 * @see ByteBufOutputStream
 */
public class ByteBufInputStream extends InputStream implements DataInput {
    private final ByteBuf buffer;
    private       boolean closed;
    /**
     * we support a conditional flag which indicates if {@link #buffer} should be released when this {@link InputStream} is closed.
     */
    private final boolean releaseOnClose;

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
     *                       be called on {@code buffer}.
     */
    public ByteBufInputStream(ByteBuf buffer, boolean releaseOnClose) {
        Objects.requireNonNull(buffer, "buffer");
        this.releaseOnClose = releaseOnClose;
        this.buffer = buffer;
    }

    /**
     * Returns the number of read bytes by this stream so far.
     */
    public int readBytes() {
        return this.buffer.readableBytes();
    }

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

    // Suppress a warning since the class is not thread-safe
    @Override
    public void mark(int readlimit) {

    }

    @Override
    public boolean markSupported() {
        return false;
    }

    // Suppress a warning since the class is not thread-safe
    @Override
    public void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    @Override
    public int available() {
        return this.buffer.readableBytes();
    }

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

    @Override
    public boolean readBoolean() throws IOException {
        int res = this.read();
        this.buffer.markReader();
        return res != 0;
    }

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

    @Override
    public char readChar() {
        char res = (char) this.readShort();
        this.buffer.markReader();
        return res;
    }

    @Override
    public double readDouble() {
        double res = this.buffer.readFloat64();
        this.buffer.markReader();
        return res;
    }

    @Override
    public float readFloat() {
        float res = this.buffer.readFloat32();
        this.buffer.markReader();
        return res;
    }

    @Override
    public void readFully(byte[] b) {
        this.readFully(b, 0, b.length);
    }

    @Override
    public void readFully(byte[] b, int off, int len) {
        this.buffer.readBytes(b, off, len);
        this.buffer.markReader();
    }

    @Override
    public int readInt() {
        int res = this.buffer.readInt32();
        this.buffer.markReader();
        return res;
    }

    @Override
    public String readLine() {
        String line = this.buffer.readLine();
        this.buffer.markReader();
        return line;
    }

    public String readLine(Charset charset) {
        String line = this.buffer.readLine(charset);
        this.buffer.markReader();
        return line;
    }

    @Override
    public long readLong() {
        long res = this.buffer.readInt64();
        this.buffer.markReader();
        return res;
    }

    @Override
    public short readShort() {
        short res = this.buffer.readInt16();
        this.buffer.markReader();
        return res;
    }

    public int readMedium() {
        int res = this.buffer.readInt24();
        this.buffer.markReader();
        return res;
    }

    @Override
    public String readUTF() throws IOException {
        String utf = DataInputStream.readUTF(this);
        this.buffer.markReader();
        return utf;
    }

    @Override
    public int readUnsignedByte() {
        int res = this.buffer.readUInt8();
        this.buffer.markReader();
        return res;
    }

    @Override
    public int readUnsignedShort() {
        int res = this.buffer.readUInt16();
        this.buffer.markReader();
        return res;
    }

    @Override
    public int skipBytes(int n) {
        int nBytes = Math.min(available(), n);
        this.buffer.skipReadableBytes(nBytes);
        this.buffer.markReader();
        return nBytes;
    }
}
