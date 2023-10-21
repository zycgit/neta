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
 * An {@link OutputStream} which writes data to a {@link ByteBuf}.
 * This stream implements {@link DataOutput} for your convenience.
 * The endianness of the stream is not always big endian but depends on
 * the endianness of the underlying buffer.
 *
 * Implement copy from netty io.netty.buffer.ByteBufOutputStream,
 * The ByteBuf implementation is replaced with cobble.bytebuf
 * @see ByteBufInputStream
 */
public class ByteBufOutputStream extends OutputStream implements DataOutput {
    private final ByteBuf          buffer;
    private       DataOutputStream utf8out; // lazily-instantiated
    private       boolean          closed;
    private final int              cacheSize;

    /**
     * Creates a new stream which writes data to the specified {@code buffer}. (no cache)
     */
    public ByteBufOutputStream(ByteBuf buffer) {
        this(buffer, -1);
    }

    /**
     * Creates a new stream which writes data to the specified {@code buffer}.
     * @param cacheSize -1 is no cache
     */
    public ByteBufOutputStream(ByteBuf buffer, int cacheSize) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.cacheSize = cacheSize;
    }

    /**
     * Returns the number of written bytes by this stream so far.
     */
    public int writtenBytes() {
        return this.buffer.writableBytes();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return;
        }

        this.buffer.writeBytes(b, off, len);
        autoFlash();
    }

    @Override
    public void write(byte[] b) throws IOException {
        this.buffer.writeBytes(b);
        autoFlash();
    }

    @Override
    public void write(int b) throws IOException {
        this.buffer.writeByte((byte) b);
        autoFlash();
    }

    @Override
    public void writeBoolean(boolean v) throws IOException {
        this.buffer.writeByte((byte) (v ? 1 : 0));
        autoFlash();
    }

    @Override
    public void writeByte(int v) throws IOException {
        this.buffer.writeByte((byte) v);
        autoFlash();
    }

    @Override
    public void writeBytes(String s) throws IOException {
        this.buffer.writeString(s, StandardCharsets.US_ASCII);
        autoFlash();
    }

    @Override
    public void writeChar(int v) throws IOException {
        this.buffer.writeInt16((short) v);
        autoFlash();
    }

    @Override
    public void writeChars(String s) throws IOException {
        int len = s.length();
        for (int i = 0; i < len; i++) {
            this.buffer.writeInt16((short) s.charAt(i));
            autoFlash();
        }
    }

    @Override
    public void writeDouble(double v) throws IOException {
        this.buffer.writeFloat64(v);
        autoFlash();
    }

    @Override
    public void writeFloat(float v) throws IOException {
        this.buffer.writeFloat32(v);
        autoFlash();
    }

    @Override
    public void writeInt(int v) throws IOException {
        this.buffer.writeInt32(v);
        autoFlash();
    }

    @Override
    public void writeLong(long v) throws IOException {
        this.buffer.writeInt64(v);
        autoFlash();
    }

    @Override
    public void writeShort(int v) throws IOException {
        this.buffer.writeInt16((short) v);
        autoFlash();
    }

    public void writeMedium(int v) throws IOException {
        this.buffer.writeInt24(v);
        autoFlash();
    }

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
        autoFlash();
    }

    private void autoFlash() throws IOException {
        if (this.cacheSize < 0) {
            return;
        } else if (this.buffer.writedBytes() >= this.cacheSize) {
            this.flush();
        }
    }

    /**
     * Returns the buffer where this stream is writing data.
     */
    public ByteBuf buffer() {
        return buffer;
    }

    @Override
    public void flush() throws IOException {
        this.buffer.flush();
    }

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
