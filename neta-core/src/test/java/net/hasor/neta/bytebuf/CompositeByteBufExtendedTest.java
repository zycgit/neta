package net.hasor.neta.bytebuf;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Extended CompositeByteBuf tests covering scenarios NOT in CompositeByteBufTest:
 * - readInt24 spanning 2 components
 * - getInt64 spanning 3 components
 * - getFloat32/64 spanning components
 * - getUInt8/16/24 spanning components
 * - readUInt24/32 spanning components
 * - readLine spanning components
 * - Sequential read cache optimization verification
 * - discardReadBytes resets component cache correctly
 * - many components sequential getByte correctness
 */
public class CompositeByteBufExtendedTest {

    // ========================================================================
    // readInt24 spanning 2 components (not in existing tests)
    // ========================================================================

    @Test
    public void test_readInt24_spanning_2_components() {
        // int24 = 3 bytes: split 1+2
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 0x12 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 0x34, 0x56 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            int value = composite.readInt24();
            assert value == 0x123456 : "expected 0x123456, got " + Integer.toHexString(value);
        } finally {
            composite.free();
        }
    }

    @Test
    public void test_readInt24_spanning_split_2_1() {
        // int24 = 3 bytes: split 2+1
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 0x12, 0x34 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 0x56 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            int value = composite.readInt24();
            assert value == 0x123456 : "expected 0x123456, got " + Integer.toHexString(value);
        } finally {
            composite.free();
        }
    }

    @Test
    public void test_readInt24_spanning_3_components() {
        // int24 = 3 bytes: 1 byte per component
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 0x0A });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 0x0B });
        ByteBuf buf3 = ByteBuf.wrap(new byte[] { 0x0C });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            composite.addComponent(buf3);
            int value = composite.readInt24();
            assert value == 0x0A0B0C : "expected 0x0A0B0C, got " + Integer.toHexString(value);
        } finally {
            composite.free();
        }
    }

    // ========================================================================
    // readUInt24 spanning components (not tested)
    // ========================================================================

    @Test
    public void test_readUInt24_spanning() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { (byte) 0xFF });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { (byte) 0xFF, (byte) 0xFF });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            int value = composite.readUInt24();
            assert value == 0xFFFFFF : "readUInt24 0xFFFFFF should be 16777215, got " + value;
        } finally {
            composite.free();
        }
    }

    // ========================================================================
    // readUInt32 spanning components (not tested)
    // ========================================================================

    @Test
    public void test_readUInt32_spanning() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { (byte) 0xFF, (byte) 0xFF });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { (byte) 0xFF, (byte) 0xFF });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            long value = composite.readUInt32();
            assert value == 4294967295L : "readUInt32 0xFFFFFFFF should be 4294967295, got " + value;
        } finally {
            composite.free();
        }
    }

    // ========================================================================
    // getInt64 spanning 3 components
    // ========================================================================

    @Test
    public void test_getInt64_spanning_3_components() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 0x01, 0x02, 0x03 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 0x04, 0x05 });
        ByteBuf buf3 = ByteBuf.wrap(new byte[] { 0x06, 0x07, 0x08 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            composite.addComponent(buf3);
            long value = composite.getInt64(0);
            assert value == 0x0102030405060708L : "getInt64 spanning 3 components";
        } finally {
            composite.free();
        }
    }

    // ========================================================================
    // getFloat32 spanning components
    // ========================================================================

    @Test
    public void test_getFloat32_spanning() {
        int intBits = Float.floatToRawIntBits(1.5f);
        byte[] bytes = new byte[4];
        bytes[0] = (byte) (intBits >>> 24);
        bytes[1] = (byte) (intBits >>> 16);
        bytes[2] = (byte) (intBits >>> 8);
        bytes[3] = (byte) intBits;

        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { bytes[0] });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { bytes[1], bytes[2], bytes[3] });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            float value = composite.getFloat32(0);
            assert value == 1.5f : "getFloat32 spanning: expected 1.5, got " + value;
        } finally {
            composite.free();
        }
    }

    // ========================================================================
    // getFloat64 spanning components
    // ========================================================================

    @Test
    public void test_getFloat64_spanning() {
        long longBits = Double.doubleToRawLongBits(99.99);
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (longBits >>> (56 - i * 8));
        }

        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { bytes[0], bytes[1], bytes[2], bytes[3] });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { bytes[4], bytes[5], bytes[6], bytes[7] });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            double value = composite.getFloat64(0);
            assert Math.abs(value - 99.99) < 0.001 : "getFloat64 spanning: expected ~99.99, got " + value;
        } finally {
            composite.free();
        }
    }

    // ========================================================================
    // getUInt8/16/24 spanning components
    // ========================================================================

    @Test
    public void test_getUInt8_spanning() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { (byte) 0x80 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { (byte) 0xFF });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            assert composite.getUInt8(0) == 128 : "getUInt8 at component 0";
            assert composite.getUInt8(1) == 255 : "getUInt8 at component 1";
        } finally {
            composite.free();
        }
    }

    @Test
    public void test_getUInt16_spanning() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { (byte) 0x80 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { (byte) 0x00 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            int value = composite.getUInt16(0);
            // 0x8000 unsigned = 32768
            assert value == 32768 : "getUInt16 spanning: expected 32768, got " + value;
        } finally {
            composite.free();
        }
    }

    @Test
    public void test_getUInt24_spanning() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { (byte) 0xFF });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { (byte) 0x00, (byte) 0x01 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            int value = composite.getUInt24(0);
            // 0xFF0001 = 16711681
            assert value == 16711681 : "getUInt24 spanning: expected 16711681, got " + value;
        } finally {
            composite.free();
        }
    }

    // ========================================================================
    // readLine spanning components
    // ========================================================================

    @Test
    public void test_readLine_spanning_components() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap("Hel".getBytes());
        ByteBuf buf2 = ByteBuf.wrap("lo\nWo".getBytes());
        ByteBuf buf3 = ByteBuf.wrap("rld\n".getBytes());
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            composite.addComponent(buf3);

            String line1 = composite.readLine();
            assert "Hello".equals(line1) : "first line should be 'Hello', got: " + line1;

            String line2 = composite.readLine();
            assert "World".equals(line2) : "second line should be 'World', got: " + line2;

            String line3 = composite.readLine();
            assert line3 == null : "no more lines expected";
        } finally {
            composite.free();
        }
    }

    @Test
    public void test_readLine_crlf_spanning() {
        // \r\n split across two components
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap("Hello\r".getBytes());
        ByteBuf buf2 = ByteBuf.wrap("\nWorld\r\n".getBytes());
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            String line1 = composite.readLine();
            assert "Hello".equals(line1) : "first line: " + line1;

            String line2 = composite.readLine();
            assert "World".equals(line2) : "second line: " + line2;
        } finally {
            composite.free();
        }
    }

    // ========================================================================
    // Sequential read cache optimization — correctness verification
    // After optimization, sequential reads should cache the last component
    // index for O(1) lookups. This tests correctness with many components.
    // ========================================================================

    @Test
    public void test_sequential_read_many_components_cache() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf[] bufs = new ByteBuf[50];
        try {
            // Create 50 components each with 3 bytes
            for (int i = 0; i < 50; i++) {
                byte val = (byte) i;
                bufs[i] = ByteBuf.wrap(new byte[] { val, (byte) (val + 100), (byte) (val + 200) });
                composite.addComponent(bufs[i]);
            }

            assert composite.readableBytes() == 150;

            // Sequential read: this exercises the cache fast path
            for (int i = 0; i < 50; i++) {
                byte b0 = composite.readByte();
                byte b1 = composite.readByte();
                byte b2 = composite.readByte();
                assert b0 == (byte) i : "component " + i + " byte 0";
                assert b1 == (byte) (i + 100) : "component " + i + " byte 1";
                assert b2 == (byte) (i + 200) : "component " + i + " byte 2";
            }
        } finally {
            composite.free();
        }
    }

    @Test
    public void test_sequential_getByte_many_components_cache() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf[] bufs = new ByteBuf[30];
        try {
            for (int i = 0; i < 30; i++) {
                bufs[i] = ByteBuf.wrap(new byte[] { (byte) (i * 2), (byte) (i * 2 + 1) });
                composite.addComponent(bufs[i]);
            }

            // Sequential getByte: should also benefit from cache
            for (int i = 0; i < 60; i++) {
                byte val = composite.getByte(i);
                assert val == (byte) i : "getByte(" + i + ") expected " + i + ", got " + val;
            }
        } finally {
            composite.free();
        }
    }

    // ========================================================================
    // Random access getByte after sequential read — cache should not break
    // ========================================================================

    @Test
    public void test_random_access_after_sequential() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 10, 20 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 30, 40 });
        ByteBuf buf3 = ByteBuf.wrap(new byte[] { 50, 60 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            composite.addComponent(buf3);

            // Sequential read moves cache forward
            assert composite.readByte() == 10;
            assert composite.readByte() == 20;
            assert composite.readByte() == 30;

            // getByte offset is relative to current readerIndex (after 3 reads)
            // readerIndex=3, so getByte(0) → absolute position 3 → 40
            assert composite.getByte(0) == 40 : "getByte(0) after 3 reads: expected 40";
            assert composite.getByte(2) == 60 : "getByte(2) after 3 reads: expected 60";
        } finally {
            composite.free();
        }
    }

    // ========================================================================
    // discardReadBytes resets cache — verify no stale index
    // ========================================================================

    @Test
    public void test_discard_resets_cache() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1, 2 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 3, 4 });
        ByteBuf buf3 = ByteBuf.wrap(new byte[] { 5, 6 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            composite.addComponent(buf3);

            // Read all of buf1 + first byte of buf2 (cache at component 1)
            composite.readByte(); // 1
            composite.readByte(); // 2
            composite.readByte(); // 3

            // Discard consumed bytes — removes buf1, may restructure
            composite.discardReadBytes();

            // After discard, component indices shift. Cache should be reset.
            assert composite.readableBytes() == 3;
            assert composite.readByte() == 4;
            assert composite.readByte() == 5;
            assert composite.readByte() == 6;
        } finally {
            composite.free();
        }
    }

    // ========================================================================
    // expect() spanning components
    // ========================================================================

    @Test
    public void test_expect_spanning_components() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap("HEL".getBytes());
        ByteBuf buf2 = ByteBuf.wrap("LO".getBytes());
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            int pos = composite.expect("HELLO", StandardCharsets.US_ASCII);
            assert pos >= 0 : "expect should find 'HELLO' spanning 2 components, got " + pos;
        } finally {
            composite.free();
        }
    }

    @Test
    public void test_expect_not_found() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap("ABCDE".getBytes());
        try {
            composite.addComponent(buf);
            int pos = composite.expect("XYZ", StandardCharsets.US_ASCII);
            assert pos < 0 : "expect should return negative for non-matching, got " + pos;
        } finally {
            composite.free();
        }
    }

    // ========================================================================
    // getInt16 spanning — verify signed result
    // ========================================================================

    @Test
    public void test_getInt16_spanning_signed() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { (byte) 0x80 }); // high byte
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 0x00 });        // low byte
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            // 0x8000 signed = -32768
            short value = composite.getInt16(0);
            assert value == -32768 : "getInt16 spanning signed: expected -32768, got " + value;
        } finally {
            composite.free();
        }
    }

    // ========================================================================
    // getInt24 spanning
    // ========================================================================

    @Test
    public void test_getInt24_spanning() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 0x12, 0x34 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 0x56, 0x78 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            int value = composite.getInt24(0);
            assert value == 0x123456 : "getInt24 at 0: expected 0x123456";
            int value2 = composite.getInt24(1);
            assert value2 == 0x345678 : "getInt24 at 1: expected 0x345678";
        } finally {
            composite.free();
        }
    }

    // ========================================================================
    // getInt32 spanning 3 components
    // ========================================================================

    @Test
    public void test_getInt32_spanning_3_components() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 0x01 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 0x02 });
        ByteBuf buf3 = ByteBuf.wrap(new byte[] { 0x03, 0x04 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            composite.addComponent(buf3);
            int value = composite.getInt32(0);
            assert value == 0x01020304 : "getInt32 spanning 3 components";
        } finally {
            composite.free();
        }
    }
}
