package net.hasor.cobble.bytebuf;
import java.io.*;
import java.nio.charset.Charset;
import java.util.Objects;

/**
 * An {@link InputStream} which reads data from a {@link ByteBuf}.
 * This stream implements {@link DataInput} for your convenience.
 * The endianness of the stream is not always big endian but depends on
 * the endianness of the underlying buffer.
 *
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
        return this.buffer.readByte() & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int available = available();
        if (available == 0) {
            return -1;
        }

        len = Math.min(available, len);
        this.buffer.readBytes(b, off, len);
        return len;
    }

    @Override
    public long skip(long n) {
        if (n > Integer.MAX_VALUE) {
            return this.skipBytes(Integer.MAX_VALUE);
        } else {
            return this.skipBytes((int) n);
        }
    }

    @Override
    public boolean readBoolean() throws IOException {
        return this.read() != 0;
    }

    @Override
    public byte readByte() throws IOException {
        int available = available();
        if (available == 0) {
            throw new EOFException();
        }
        return this.buffer.readByte();
    }

    @Override
    public char readChar() {
        return (char) this.readShort();
    }

    @Override
    public double readDouble() {
        return this.buffer.readFloat64();
    }

    @Override
    public float readFloat() {
        return this.buffer.readFloat32();
    }

    @Override
    public void readFully(byte[] b) {
        this.readFully(b, 0, b.length);
    }

    @Override
    public void readFully(byte[] b, int off, int len) {
        this.buffer.readBytes(b, off, len);
    }

    @Override
    public int readInt() {
        return this.buffer.readInt32();
    }

    @Override
    public String readLine() {
        return this.buffer.readLine();
    }

    public String readLine(Charset charset) {
        return this.buffer.readLine(charset);
    }

    @Override
    public long readLong() {
        return this.buffer.readInt64();
    }

    @Override
    public short readShort() {
        return this.buffer.readInt16();
    }

    @Override
    public String readUTF() throws IOException {
        return DataInputStream.readUTF(this);
    }

    @Override
    public int readUnsignedByte() {
        return this.buffer.readUInt8();
    }

    @Override
    public int readUnsignedShort() {
        return this.buffer.readUInt16();
    }

    @Override
    public int skipBytes(int n) {
        int nBytes = Math.min(available(), n);
        this.buffer.skipReadableBytes(nBytes);
        return nBytes;
    }
}
