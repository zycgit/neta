package net.hasor.neta.bytebuf;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Extended tests for ByteBufInputStream and ByteBufOutputStream.
 * Covers scenarios NOT in ByteBufStreamTest:
 * - readLine() / readLine(Charset)
 * - skip(n) / skipBytes(n)
 * - readUTF / readUnsignedByte / readUnsignedShort
 * - readBoolean / readChar / readFloat / readDouble / readLong / readShort
 * - readMedium (int24)
 * - EOFException on insufficient data
 * - available() after partial reads
 * - ByteBufOutputStream write / writeUTF / flush
 */
public class ByteBufStreamExtendedTest {

    private ByteBuf makeBuf(byte... data) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(data.length + 16);
        buf.writeBytes(data);
        buf.markWriter();
        return buf;
    }

    // ========================================================================
    // readLine() tests — Delegates to ByteBuf.readLine()
    // ========================================================================

    @Test
    public void testInputStream_readLine_basic() {
        ByteBuf buf = makeBuf("Hello\nWorld\n".getBytes());
        ByteBufInputStream in = new ByteBufInputStream(buf);

        String line1 = in.readLine();
        assert "Hello".equals(line1) : "first line: " + line1;

        String line2 = in.readLine();
        assert "World".equals(line2) : "second line: " + line2;

        String line3 = in.readLine();
        assert line3 == null : "no more lines";
        buf.free();
    }

    @Test
    public void testInputStream_readLine_crlf() {
        ByteBuf buf = makeBuf("ABC\r\nDEF\r\n".getBytes());
        ByteBufInputStream in = new ByteBufInputStream(buf);

        assert "ABC".equals(in.readLine());
        assert "DEF".equals(in.readLine());
        assert in.readLine() == null;
        buf.free();
    }

    @Test
    public void testInputStream_readLine_empty() {
        ByteBuf buf = makeBuf();
        buf.markWriter();
        ByteBufInputStream in = new ByteBufInputStream(buf);
        assert in.readLine() == null : "empty buf readLine";
        buf.free();
    }

    @Test
    public void testInputStream_readLine_charset() {
        ByteBuf buf = makeBuf("你好\n世界\n".getBytes(StandardCharsets.UTF_8));
        ByteBufInputStream in = new ByteBufInputStream(buf);

        String line1 = in.readLine(StandardCharsets.UTF_8);
        assert "你好".equals(line1) : "UTF-8 line1: " + line1;

        String line2 = in.readLine(StandardCharsets.UTF_8);
        assert "世界".equals(line2) : "UTF-8 line2: " + line2;
        buf.free();
    }

    // ========================================================================
    // skip / skipBytes tests
    // ========================================================================

    @Test
    public void testInputStream_skip() throws IOException {
        ByteBuf buf = makeBuf(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        ByteBufInputStream in = new ByteBufInputStream(buf);

        long skipped = in.skip(3);
        assert skipped == 3 : "should skip 3";

        byte val = in.readByte();
        assert val == 4 : "after skip 3, next byte should be 4";
        buf.free();
    }

    @Test
    public void testInputStream_skip_beyond_available() throws IOException {
        ByteBuf buf = makeBuf(new byte[] { 1, 2, 3 });
        ByteBufInputStream in = new ByteBufInputStream(buf);

        long skipped = in.skip(100);
        assert skipped == 3 : "should only skip available 3 bytes";
        assert in.available() == 0;
        buf.free();
    }

    @Test
    public void testInputStream_skipBytes() throws IOException {
        ByteBuf buf = makeBuf(new byte[] { 10, 20, 30, 40, 50 });
        ByteBufInputStream in = new ByteBufInputStream(buf);

        int skipped = in.skipBytes(2);
        assert skipped == 2;
        assert in.readByte() == 30;
        buf.free();
    }

    // ========================================================================
    // readBoolean / readChar / readShort / readFloat / readDouble / readLong
    // ========================================================================

    @Test
    public void testInputStream_readBoolean() throws IOException {
        ByteBuf buf = makeBuf(new byte[] { 0, 1, (byte) 0xFF });
        ByteBufInputStream in = new ByteBufInputStream(buf);

        assert !in.readBoolean() : "0 should be false";
        assert in.readBoolean() : "1 should be true";
        assert in.readBoolean() : "0xFF should be true";
        buf.free();
    }

    @Test(expected = EOFException.class)
    public void testInputStream_readBoolean_eof() throws IOException {
        ByteBuf buf = makeBuf();
        ByteBufInputStream in = new ByteBufInputStream(buf);
        in.readBoolean();
    }

    @Test
    public void testInputStream_readShort() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeInt16((short) 12345);
        buf.writeInt16((short) -1);
        buf.markWriter();
        ByteBufInputStream in = new ByteBufInputStream(buf);

        assert in.readShort() == 12345;
        assert in.readShort() == -1;
        buf.free();
    }

    @Test(expected = EOFException.class)
    public void testInputStream_readShort_eof() throws IOException {
        ByteBuf buf = makeBuf(new byte[] { 1 }); // only 1 byte, need 2
        ByteBufInputStream in = new ByteBufInputStream(buf);
        in.readShort();
    }

    @Test
    public void testInputStream_readChar() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeInt16((short) 'A');
        buf.markWriter();
        ByteBufInputStream in = new ByteBufInputStream(buf);

        assert in.readChar() == 'A';
        buf.free();
    }

    @Test
    public void testInputStream_readFloat() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeFloat32(3.14f);
        buf.markWriter();
        ByteBufInputStream in = new ByteBufInputStream(buf);

        assert in.readFloat() == 3.14f;
        buf.free();
    }

    @Test(expected = EOFException.class)
    public void testInputStream_readFloat_eof() throws IOException {
        ByteBuf buf = makeBuf(new byte[] { 1, 2, 3 }); // only 3 bytes, need 4
        ByteBufInputStream in = new ByteBufInputStream(buf);
        in.readFloat();
    }

    @Test
    public void testInputStream_readDouble() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeFloat64(Math.E);
        buf.markWriter();
        ByteBufInputStream in = new ByteBufInputStream(buf);

        assert in.readDouble() == Math.E;
        buf.free();
    }

    @Test(expected = EOFException.class)
    public void testInputStream_readDouble_eof() throws IOException {
        ByteBuf buf = makeBuf(new byte[] { 1, 2, 3, 4, 5, 6, 7 }); // 7 bytes, need 8
        ByteBufInputStream in = new ByteBufInputStream(buf);
        in.readDouble();
    }

    @Test
    public void testInputStream_readLong() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeInt64(Long.MAX_VALUE);
        buf.markWriter();
        ByteBufInputStream in = new ByteBufInputStream(buf);

        assert in.readLong() == Long.MAX_VALUE;
        buf.free();
    }

    // ========================================================================
    // readMedium (int24)
    // ========================================================================

    @Test
    public void testInputStream_readMedium() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeInt24(0x123456);
        buf.markWriter();
        ByteBufInputStream in = new ByteBufInputStream(buf);

        assert in.readMedium() == 0x123456;
        buf.free();
    }

    @Test(expected = EOFException.class)
    public void testInputStream_readMedium_eof() throws IOException {
        ByteBuf buf = makeBuf(new byte[] { 1, 2 }); // 2 bytes, need 3
        ByteBufInputStream in = new ByteBufInputStream(buf);
        in.readMedium();
    }

    // ========================================================================
    // readUnsignedByte / readUnsignedShort
    // ========================================================================

    @Test
    public void testInputStream_readUnsignedByte() throws IOException {
        ByteBuf buf = makeBuf((byte) 0xFF);
        ByteBufInputStream in = new ByteBufInputStream(buf);

        int val = in.readUnsignedByte();
        assert val == 255 : "readUnsignedByte 0xFF should be 255";
        buf.free();
    }

    @Test(expected = EOFException.class)
    public void testInputStream_readUnsignedByte_eof() throws IOException {
        ByteBuf buf = makeBuf();
        ByteBufInputStream in = new ByteBufInputStream(buf);
        in.readUnsignedByte();
    }

    @Test
    public void testInputStream_readUnsignedShort() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        buf.writeInt16((short) 0xFFFF);
        buf.markWriter();
        ByteBufInputStream in = new ByteBufInputStream(buf);

        int val = in.readUnsignedShort();
        assert val == 65535 : "readUnsignedShort 0xFFFF should be 65535";
        buf.free();
    }

    @Test(expected = EOFException.class)
    public void testInputStream_readUnsignedShort_eof() throws IOException {
        ByteBuf buf = makeBuf(new byte[] { 1 });
        ByteBufInputStream in = new ByteBufInputStream(buf);
        in.readUnsignedShort();
    }

    // ========================================================================
    // readUTF — writes length-prefixed modified UTF-8
    // ========================================================================

    @Test
    public void testInputStream_readUTF() throws IOException {
        // Use DataOutputStream to write a proper modified UTF-8 string
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(128);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeUTF("Hello World");
        dos.flush();
        buf.markWriter();

        ByteBufInputStream in = new ByteBufInputStream(buf);
        String result = in.readUTF();
        assert "Hello World".equals(result) : "readUTF: " + result;
        buf.free();
    }

    @Test
    public void testInputStream_readUTF_unicode() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(128);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeUTF("你好世界");
        dos.flush();
        buf.markWriter();

        ByteBufInputStream in = new ByteBufInputStream(buf);
        String result = in.readUTF();
        assert "你好世界".equals(result) : "readUTF unicode: " + result;
        buf.free();
    }

    // ========================================================================
    // readFully edge cases
    // ========================================================================

    @Test
    public void testInputStream_readFully_with_offset() throws IOException {
        ByteBuf buf = makeBuf(new byte[] { 10, 20, 30, 40 });
        ByteBufInputStream in = new ByteBufInputStream(buf);

        byte[] result = new byte[6];
        in.readFully(result, 1, 4);
        assert result[0] == 0 : "offset 0 untouched";
        assert result[1] == 10;
        assert result[2] == 20;
        assert result[3] == 30;
        assert result[4] == 40;
        assert result[5] == 0 : "offset 5 untouched";
        buf.free();
    }

    @Test(expected = EOFException.class)
    public void testInputStream_readFully_eof() throws IOException {
        ByteBuf buf = makeBuf(new byte[] { 1, 2 });
        ByteBufInputStream in = new ByteBufInputStream(buf);
        in.readFully(new byte[5]); // need 5, only have 2
    }

    // ========================================================================
    // available() tracking
    // ========================================================================

    @Test
    public void testInputStream_available() throws IOException {
        ByteBuf buf = makeBuf(new byte[] { 1, 2, 3, 4, 5 });
        ByteBufInputStream in = new ByteBufInputStream(buf);

        assert in.available() == 5 : "initial available";

        in.readByte();
        assert in.available() == 4 : "after 1 read";

        in.skip(2);
        assert in.available() == 2 : "after skip 2";

        in.readByte();
        in.readByte();
        assert in.available() == 0 : "fully consumed";
        buf.free();
    }

    // ========================================================================
    // ByteBufOutputStream extended tests
    // ========================================================================

    @Test
    public void testOutputStream_write_single_byte() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        out.write(0x41);
        out.write(0x42);
        out.flush();
        buf.markWriter();

        assert buf.readableBytes() == 2;
        assert buf.readByte() == 0x41;
        assert buf.readByte() == 0x42;
        buf.free();
    }

    @Test
    public void testOutputStream_write_array() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        out.write(new byte[] { 10, 20, 30, 40 });
        out.flush();
        buf.markWriter();

        assert buf.readableBytes() == 4;
        assert buf.readByte() == 10;
        assert buf.readByte() == 20;
        assert buf.readByte() == 30;
        assert buf.readByte() == 40;
        buf.free();
    }

    @Test
    public void testOutputStream_write_array_with_offset() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        out.write(new byte[] { 0, 0, 10, 20, 30, 0 }, 2, 3);
        out.flush();
        buf.markWriter();

        assert buf.readableBytes() == 3;
        assert buf.readByte() == 10;
        assert buf.readByte() == 20;
        assert buf.readByte() == 30;
        buf.free();
    }

    @Test
    public void testOutputStream_writeInt() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        out.writeInt(0x01020304);
        out.flush();
        buf.markWriter();

        assert buf.readInt32() == 0x01020304;
        buf.free();
    }

    @Test
    public void testOutputStream_writeLong() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        out.writeLong(Long.MIN_VALUE);
        out.flush();
        buf.markWriter();

        assert buf.readInt64() == Long.MIN_VALUE;
        buf.free();
    }

    @Test
    public void testOutputStream_writeShort() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        out.writeShort(0x1234);
        out.flush();
        buf.markWriter();

        assert buf.readInt16() == 0x1234;
        buf.free();
    }

    @Test
    public void testOutputStream_writeFloat() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        out.writeFloat(2.71f);
        out.flush();
        buf.markWriter();

        assert buf.readFloat32() == 2.71f;
        buf.free();
    }

    @Test
    public void testOutputStream_writeDouble() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        out.writeDouble(Math.PI);
        out.flush();
        buf.markWriter();

        assert buf.readFloat64() == Math.PI;
        buf.free();
    }

    @Test
    public void testOutputStream_writeBoolean() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        out.writeBoolean(true);
        out.writeBoolean(false);
        out.flush();
        buf.markWriter();

        assert buf.readByte() == 1 : "true should write 1";
        assert buf.readByte() == 0 : "false should write 0";
        buf.free();
    }

    @Test
    public void testOutputStream_writeChar() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(16);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        out.writeChar('Z');
        out.flush();
        buf.markWriter();

        assert buf.readInt16() == 'Z';
        buf.free();
    }

    @Test
    public void testOutputStream_writeBytes() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(32);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        out.writeBytes("ABC");
        out.flush();
        buf.markWriter();

        assert buf.readableBytes() == 3;
        assert buf.readByte() == 'A';
        assert buf.readByte() == 'B';
        assert buf.readByte() == 'C';
        buf.free();
    }

    @Test
    public void testOutputStream_writeChars() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(32);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        out.writeChars("AB");
        out.flush();
        buf.markWriter();

        // writeChars writes 2 bytes per char
        assert buf.readableBytes() == 4;
        assert buf.readInt16() == 'A';
        assert buf.readInt16() == 'B';
        buf.free();
    }

    // ========================================================================
    // Inter-op: write via OutputStream, read via InputStream
    // ========================================================================

    @Test
    public void testStream_roundTrip() throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.heapBuffer(128);
        ByteBufOutputStream out = new ByteBufOutputStream(buf);

        out.writeInt(42);
        out.writeShort(100);
        out.writeByte(7);
        out.writeFloat(1.5f);
        out.writeDouble(2.5);
        out.writeLong(999L);
        out.flush();
        buf.markWriter();

        ByteBufInputStream in = new ByteBufInputStream(buf);
        assert in.readInt() == 42;
        assert in.readShort() == 100;
        assert in.readByte() == 7;
        assert in.readFloat() == 1.5f;
        assert in.readDouble() == 2.5;
        assert in.readLong() == 999L;
        assert in.available() == 0;
        buf.free();
    }
}
