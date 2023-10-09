package net.hasor.cobble.bytebuf;
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
 * @see ByteBufInputStream
 */
public class ByteBufOutputStream extends OutputStream implements DataOutput {
    private final ByteBuf          buffer;
    private       DataOutputStream utf8out; // lazily-instantiated
    private       boolean          closed;

    /**
     * Creates a new stream which writes data to the specified {@code buffer}.
     */
    public ByteBufOutputStream(ByteBuf buffer) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
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
    }

    @Override
    public void write(byte[] b) throws IOException {
        this.buffer.writeBytes(b);
    }

    @Override
    public void write(int b) throws IOException {
        this.buffer.writeByte((byte) b);
    }

    @Override
    public void writeBoolean(boolean v) {
        this.buffer.writeByte((byte) (v ? 1 : 0));
    }

    @Override
    public void writeByte(int v) throws IOException {
        this.buffer.writeByte((byte) v);
    }

    @Override
    public void writeBytes(String s) throws IOException {
        this.buffer.writeString(s, StandardCharsets.US_ASCII);
    }

    @Override
    public void writeChar(int v) {
        this.buffer.writeInt16((short) v);
    }

    @Override
    public void writeChars(String s) {
        int len = s.length();
        for (int i = 0; i < len; i++) {
            this.buffer.writeInt16((short) s.charAt(i));
        }
    }

    @Override
    public void writeDouble(double v) {
        this.buffer.writeFloat64(v);
    }

    @Override
    public void writeFloat(float v) {
        this.buffer.writeFloat32(v);
    }

    @Override
    public void writeInt(int v) {
        this.buffer.writeInt32(v);
    }

    @Override
    public void writeLong(long v) {
        this.buffer.writeInt64(v);
    }

    @Override
    public void writeShort(int v) {
        this.buffer.writeInt16((short) v);
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
    }

    /**
     * Returns the buffer where this stream is writing data.
     */
    public ByteBuf buffer() {
        return buffer;
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
