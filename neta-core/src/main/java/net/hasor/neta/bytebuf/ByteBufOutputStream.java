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
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
/**
 * Sequential {@link OutputStream}/{@link DataOutput} view over a {@link ByteBuf}.
 * <p>Writes append to the wrapped buffer by advancing its {@code writerIndex}.
 * Primitive values follow the current byte order of the target buffer.
 * <p>An optional cache threshold can be used to call {@link ByteBuf#flush()}
 * automatically after enough bytes have been written.
 * @see ByteBufInputStream
 */
public class ByteBufOutputStream extends OutputStream implements DataOutput {
    private final ByteBuf    buffer;
    private final int        cacheSize;
    private DataOutputStream utf8out; // lazily-instantiated
    private boolean          closed;

    /**
     * Creates a stream with auto flush disabled.
     */
    public ByteBufOutputStream(ByteBuf buffer) {
        this(buffer, -1);
    }

    /**
     * Creates a stream with an optional auto flush threshold.
     */
    public ByteBufOutputStream(ByteBuf buffer, int cacheSize) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.cacheSize = cacheSize;
    }

    /**
     * Returns the number of written bytes by this stream so far.
     */
    public int writtenBytes() {
        return this.buffer.writtenBytes();
    }

    /** Writes a byte array slice to the buffer. */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return;
        }

        this.buffer.writeBytes(b, off, len);
        autoFlush();
    }

    /** Writes a full byte array to the buffer. */
    @Override
    public void write(byte[] b) throws IOException {
        this.buffer.writeBytes(b);
        autoFlush();
    }

    /** Writes a single byte to the buffer. */
    @Override
    public void write(int b) throws IOException {
        this.buffer.writeByte((byte) b);
        autoFlush();
    }

    /** Writes a boolean as one byte. */
    @Override
    public void writeBoolean(boolean v) throws IOException {
        this.buffer.writeByte((byte) (v ? 1 : 0));
        autoFlush();
    }

    /** Writes the low byte of the value. */
    @Override
    public void writeByte(int v) throws IOException {
        this.buffer.writeByte((byte) v);
        autoFlush();
    }

    /** Writes ASCII bytes for the string. */
    @Override
    public void writeBytes(String s) throws IOException {
        this.buffer.writeString(s, StandardCharsets.US_ASCII);
        autoFlush();
    }

    /** Writes a 16-bit character value. */
    @Override
    public void writeChar(int v) throws IOException {
        this.buffer.writeInt16((short) v);
        autoFlush();
    }

    /** Writes each character as a 16-bit value. */
    @Override
    public void writeChars(String s) throws IOException {
        int len = s.length();
        for (int i = 0; i < len; i++) {
            this.buffer.writeInt16((short) s.charAt(i));
            autoFlush();
        }
    }

    /** Writes a 64-bit floating point value. */
    @Override
    public void writeDouble(double v) throws IOException {
        this.buffer.writeFloat64(v);
        autoFlush();
    }

    /** Writes a 32-bit floating point value. */
    @Override
    public void writeFloat(float v) throws IOException {
        this.buffer.writeFloat32(v);
        autoFlush();
    }

    /** Writes a 32-bit integer value. */
    @Override
    public void writeInt(int v) throws IOException {
        this.buffer.writeInt32(v);
        autoFlush();
    }

    /** Writes a 64-bit integer value. */
    @Override
    public void writeLong(long v) throws IOException {
        this.buffer.writeInt64(v);
        autoFlush();
    }

    /** Writes the low 16 bits of the value. */
    @Override
    public void writeShort(int v) throws IOException {
        this.buffer.writeInt16((short) v);
        autoFlush();
    }

    /** Writes a 24-bit integer value. */
    public void writeMedium(int v) throws IOException {
        this.buffer.writeInt24(v);
        autoFlush();
    }

    /** Writes a modified UTF-8 string. */
    @Override
    public void writeUTF(String s) throws IOException {
        DataOutputStream out = this.utf8out;
        if (out == null) {
            if (closed) {
                throw new IOException("The stream is closed");
            }
            // Suppress a warning since the stream is closed in the close() method
            this.utf8out = out = new DataOutputStream(this);
        }
        out.writeUTF(s);
        autoFlush();
    }

    private void autoFlush() throws IOException {
        if (this.cacheSize < 0) {
        } else if (this.buffer.writtenBytes() >= this.cacheSize) {
            this.flush();
        }
    }

    /** Returns the target buffer. */
    public ByteBuf buffer() {
        return buffer;
    }

    /** Flushes buffered writes to the underlying ByteBuf. */
    @Override
    public void flush() throws IOException {
        this.buffer.flush();
    }

    /** Closes this stream and the cached UTF helper stream. */
    @Override
    public void close() throws IOException {
        if (this.closed) {
            return;
        }
        this.closed = true;

        try {
            super.close();
        } finally {
            if (this.utf8out != null) {
                this.utf8out.close();
            }
        }
    }
}
