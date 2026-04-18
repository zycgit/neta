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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Comprehensive tests for {@link CompositeByteBuf}.
 */
public class CompositeByteBufTest {

    private static void freeIfOwned(ByteBuf... bufs) {
        if (bufs == null) {
            return;
        }
        for (ByteBuf buf : bufs) {
            if (buf != null && buf.refCnt() > 0) {
                buf.free();
            }
        }
    }

    // ========== Factory Tests ==========

    @Test
    public void test_compositeBuffer_empty() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        try {
            assert composite != null;
            assert composite.numComponents() == 0;
            assert composite.readableBytes() == 0;
            assert composite.capacity() == 0;
            assert !composite.isFree();
        } finally {
            composite.free();
        }
    }

    @Test
    public void test_compositeBuffer_with_allocator() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer(ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR);
        try {
            assert composite != null;
            assert composite.alloc() == ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR;
        } finally {
            composite.free();
        }
    }

    @Test
    public void test_compositeBuffer_with_buffers() {
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 4, 5, 6 });
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer(buf1, buf2);
        try {
            assert composite.numComponents() == 2;
            assert composite.readableBytes() == 6;
        } finally {
            composite.free();
        }
    }

    // ========== addComponent Tests ==========

    @Test
    public void test_addComponent_single() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 10, 20, 30 });
        try {
            composite.addComponent(buf);
            assert composite.numComponents() == 1;
            assert composite.readableBytes() == 3;
            assert composite.capacity() == 3;
        } finally {
            composite.free();
            freeIfOwned(buf);
        }
    }

    @Test
    public void test_addComponent_multiple() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1, 2 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 3, 4, 5 });
        ByteBuf buf3 = ByteBuf.wrap(new byte[] { 6 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            composite.addComponent(buf3);
            assert composite.numComponents() == 3;
            assert composite.readableBytes() == 6;
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
            freeIfOwned(buf3);
        }
    }

    @Test
    public void test_addComponent_null_ignored() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        try {
            composite.addComponent(null);
            assert composite.numComponents() == 0;
        } finally {
            composite.free();
        }
    }

    @Test
    public void test_addComponent_empty_buf_ignored() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        try {
            composite.addComponent(ByteBuf.EMPTY);
            assert composite.numComponents() == 0;
        } finally {
            composite.free();
        }
    }

    @Test
    public void test_addComponent_transfers_ownership() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        int refCntBefore = buf.refCnt();
        boolean transferred = false;
        try {
            composite.addComponent(buf);
            transferred = true;
            assert buf.refCnt() == refCntBefore : "addComponent should not retain the buffer";
        } finally {
            composite.free();
            if (!transferred) {
                freeIfOwned(buf);
            }
        }
        assert buf.refCnt() == 0 : "component should be released by the composite";
    }

    @Test
    public void test_addComponent_accepts_empty_and_releases_it() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[0]);
        try {
            composite.addComponent(buf);
            assert composite.numComponents() == 0;
        } finally {
            composite.free();
        }
        assert buf.refCnt() == 0 : "empty component ownership should still be consumed";
    }

    @Test
    public void test_addComponent_chaining() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 2 });
        try {
            CompositeByteBuf result = composite.addComponent(buf1).addComponent(buf2);
            assert result == composite;
            assert composite.numComponents() == 2;
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    @Test
    public void test_addComponents_varargs() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 2 });
        ByteBuf buf3 = ByteBuf.wrap(new byte[] { 3 });
        try {
            composite.addComponents(buf1, buf2, buf3);
            assert composite.numComponents() == 3;
            assert composite.readableBytes() == 3;
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
            freeIfOwned(buf3);
        }
    }

    // ========== Read Operations ==========

    @Test
    public void test_readByte_single_component() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 10, 20, 30 });
        try {
            composite.addComponent(buf);
            assert composite.readByte() == 10;
            assert composite.readByte() == 20;
            assert composite.readByte() == 30;
            assert composite.readableBytes() == 0;
        } finally {
            composite.free();
            freeIfOwned(buf);
        }
    }

    @Test
    public void test_readByte_across_components() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1, 2 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 3, 4 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            assert composite.readByte() == 1;
            assert composite.readByte() == 2;
            assert composite.readByte() == 3; // crosses component boundary
            assert composite.readByte() == 4;
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    @Test
    public void test_readBytes_within_component() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5 });
        try {
            composite.addComponent(buf);
            byte[] dst = new byte[3];
            composite.readBytes(dst);
            assert Arrays.equals(dst, new byte[] { 1, 2, 3 });
        } finally {
            composite.free();
            freeIfOwned(buf);
        }
    }

    @Test
    public void test_readBytes_spanning_components() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1, 2 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 3, 4, 5 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            byte[] dst = new byte[4];
            composite.readBytes(dst);
            assert Arrays.equals(dst, new byte[] { 1, 2, 3, 4 });
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    @Test
    public void test_readBytes_spanning_three_components() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 2 });
        ByteBuf buf3 = ByteBuf.wrap(new byte[] { 3 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            composite.addComponent(buf3);

            byte[] dst = new byte[3];
            composite.readBytes(dst);
            assert Arrays.equals(dst, new byte[] { 1, 2, 3 });
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
            freeIfOwned(buf3);
        }
    }

    @Test
    public void test_readInt16_spanning_components() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 0x01 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 0x02 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            short value = composite.readInt16();
            assert value == 0x0102 : "expected 0x0102, got " + value;
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    @Test
    public void test_readInt32_spanning_components() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 0x00, 0x00 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 0x01, 0x00 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            int value = composite.readInt32();
            assert value == 256 : "expected 256, got " + value;
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    @Test
    public void test_readInt64_spanning_components() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 0, 0, 0, 0 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 0, 0, 0, 1 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            long value = composite.readInt64();
            assert value == 1L : "expected 1, got " + value;
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    // ========== Get Operations ==========

    @Test
    public void test_getByte_single_component() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 10, 20, 30 });
        try {
            composite.addComponent(buf);
            assert composite.getByte(0) == 10;
            assert composite.getByte(1) == 20;
            assert composite.getByte(2) == 30;
            // readerIndex should not change
            assert composite.readableBytes() == 3;
        } finally {
            composite.free();
            freeIfOwned(buf);
        }
    }

    @Test
    public void test_getByte_across_components() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 'A', 'B' });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 'C', 'D' });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            assert composite.getByte(0) == 'A';
            assert composite.getByte(1) == 'B';
            assert composite.getByte(2) == 'C';
            assert composite.getByte(3) == 'D';
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    @Test
    public void test_getBytes_spanning_components() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 4, 5, 6 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            byte[] dst = new byte[4];
            composite.getBytes(1, dst);
            assert Arrays.equals(dst, new byte[] { 2, 3, 4, 5 });
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    @Test
    public void test_getInt32_at_boundary() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        // int value 0x01020304 split across two components
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 0x01 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 0x02, 0x03, 0x04 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            int value = composite.getInt32(0);
            assert value == 0x01020304 : "expected 0x01020304, got " + Integer.toHexString(value);
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    // ========== Read Buffer Operations ==========

    @Test
    public void test_readBuffer_ByteBuffer() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1, 2 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 3, 4 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            ByteBuffer dst = ByteBuffer.allocate(4);
            int read = composite.readBuffer(dst);
            assert read == 4;
            dst.flip();
            assert dst.get() == 1;
            assert dst.get() == 2;
            assert dst.get() == 3;
            assert dst.get() == 4;
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    @Test
    public void test_readBuffer_ByteBuf() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 10, 20 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 30, 40 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            ByteBuf dst = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(10, 10);
            try {
                int read = composite.readBuffer(dst, 4);
                dst.markWriter();
                assert read == 4;
                assert dst.readByte() == 10;
                assert dst.readByte() == 20;
                assert dst.readByte() == 30;
                assert dst.readByte() == 40;
            } finally {
                dst.free();
            }
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    // ========== String & Line Operations ==========

    @Test
    public void test_readString_spanning_components() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap("Hel".getBytes(StandardCharsets.US_ASCII));
        ByteBuf buf2 = ByteBuf.wrap("lo!".getBytes(StandardCharsets.US_ASCII));
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            String str = composite.readString(6, StandardCharsets.US_ASCII);
            assert "Hello!".equals(str) : "expected 'Hello!', got '" + str + "'";
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    @Test
    public void test_expectLine_spanning_components() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap("Hello".getBytes(StandardCharsets.US_ASCII));
        ByteBuf buf2 = ByteBuf.wrap("\r\n".getBytes(StandardCharsets.US_ASCII));
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            assert composite.hasLine();
            String line = composite.readLine(StandardCharsets.US_ASCII);
            assert "Hello".equals(line) : "expected 'Hello', got '" + line + "'";
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    // ========== Write Operations (Should Fail) ==========

    @Test(expected = Exception.class)
    public void test_writeByte_throws() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        try {
            composite.writeByte((byte) 1);
        } finally {
            composite.free();
        }
    }

    @Test(expected = Exception.class)
    public void test_writeBytes_throws() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        try {
            composite.writeBytes(new byte[] { 1, 2 });
        } finally {
            composite.free();
        }
    }

    @Test
    public void test_writableBytes_is_zero() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        try {
            composite.addComponent(buf);
            assert composite.writableBytes() == 0;
        } finally {
            composite.free();
            freeIfOwned(buf);
        }
    }

    // ========== Lifecycle Tests ==========

    @Test
    public void test_free_releases_components() {
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1, 2 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 3, 4 });
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();

        composite.addComponent(buf1.retain());
        composite.addComponent(buf2.retain());

        // Each buf has refCnt=2 (original + explicit shared retain for composite)
        assert buf1.refCnt() == 2;
        assert buf2.refCnt() == 2;

        composite.free();

        // After composite free, each buf has refCnt=1 (only original)
        assert buf1.refCnt() == 1;
        assert buf2.refCnt() == 1;
        assert composite.isFree();

        freeIfOwned(buf1, buf2);
    }

    @Test
    public void test_retain_and_release() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1 });
        composite.addComponent(buf);

        assert composite.refCnt() == 1;
        composite.retain();
        assert composite.refCnt() == 2;
        composite.release();
        assert composite.refCnt() == 1;
        assert !composite.isFree();

        composite.free();
        freeIfOwned(buf);
    }

    // ========== Copy ==========

    @Test
    public void test_copy_returns_flat_buffer() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1, 2 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 3, 4 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            ByteBuf copy = composite.copy();
            try {
                assert !(copy instanceof CompositeByteBuf) : "copy should be a flat buffer";
                assert copy.readableBytes() == 4;
                assert copy.readByte() == 1;
                assert copy.readByte() == 2;
                assert copy.readByte() == 3;
                assert copy.readByte() == 4;
            } finally {
                copy.free();
            }
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    @Test
    public void test_copy_after_partial_read() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5 });
        try {
            composite.addComponent(buf);
            composite.readByte(); // skip first byte
            composite.readByte(); // skip second byte

            ByteBuf copy = composite.copy();
            try {
                assert copy.readableBytes() == 3;
                assert copy.readByte() == 3;
                assert copy.readByte() == 4;
                assert copy.readByte() == 5;
            } finally {
                copy.free();
            }
        } finally {
            composite.free();
            freeIfOwned(buf);
        }
    }

    @Test
    public void test_copy_empty() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        try {
            ByteBuf copy = composite.copy();
            assert copy == ByteBuf.EMPTY;
        } finally {
            composite.free();
        }
    }

    // ========== asByteArray ==========

    @Test
    public void test_asByteArray() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 10, 20 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 30, 40 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            byte[] array = composite.asByteArray();
            assert Arrays.equals(array, new byte[] { 10, 20, 30, 40 });
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    // ========== DiscardReadBytes ==========

    @Test
    public void test_discardReadBytes_removes_consumed() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1, 2 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 3, 4 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            // Read all of buf1's data
            composite.readByte(); // 1
            composite.readByte(); // 2

            // Now discard the read data
            composite.discardReadBytes();

            assert composite.numComponents() == 1 : "first component should be removed";
            assert composite.readableBytes() == 2;
            assert composite.capacity() == 2;
            assert composite.readByte() == 3;
            assert composite.readByte() == 4;
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    @Test
    public void test_discardReadBytes_partial_component() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1, 2, 3, 4 });
        try {
            composite.addComponent(buf1);

            composite.readByte(); // 1
            composite.readByte(); // 2

            composite.discardReadBytes();

            assert composite.numComponents() == 1;
            assert composite.readableBytes() == 2;
            assert composite.readByte() == 3;
            assert composite.readByte() == 4;
        } finally {
            composite.free();
            freeIfOwned(buf1);
        }
    }

    @Test
    public void test_discardReadBytes_no_op_when_nothing_read() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        try {
            composite.addComponent(buf);
            composite.discardReadBytes(); // should be no-op
            assert composite.readableBytes() == 3;
            assert composite.numComponents() == 1;
        } finally {
            composite.free();
            freeIfOwned(buf);
        }
    }

    @Test
    public void test_discardReadBytes_releases_consumed_component() {
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1, 2 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 3, 4 });
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();

        composite.addComponent(buf1.retain());
        composite.addComponent(buf2.retain());

        // buf1 refCnt should be 2 (original + explicit shared retain for composite)
        assert buf1.refCnt() == 2;

        // Read all of buf1
        composite.readByte();
        composite.readByte();

        // Discard should release buf1 from composite
        composite.discardReadBytes();
        assert buf1.refCnt() == 1 : "buf1 should be released from composite";
        assert buf2.refCnt() == 2 : "buf2 should still be retained";

        composite.free();
        freeIfOwned(buf1, buf2);
    }

    // ========== SliceOff ==========

    @Test
    public void test_sliceOff_basic() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5 });
        try {
            composite.addComponent(buf);

            ByteBuf sliced = composite.sliceOff(3);
            try {
                assert sliced.readableBytes() == 3;
                assert sliced.readByte() == 1;
                assert sliced.readByte() == 2;
                assert sliced.readByte() == 3;

                // Remaining in composite
                assert composite.readableBytes() == 2;
                assert composite.readByte() == 4;
                assert composite.readByte() == 5;
            } finally {
                sliced.free();
            }
        } finally {
            composite.free();
            freeIfOwned(buf);
        }
    }

    @Test
    public void test_sliceOff_zero() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2 });
        try {
            composite.addComponent(buf);
            ByteBuf sliced = composite.sliceOff(0);
            assert sliced == ByteBuf.EMPTY;
            assert composite.readableBytes() == 2;
        } finally {
            composite.free();
            freeIfOwned(buf);
        }
    }

    // ========== isDirect ==========

    @Test
    public void test_isDirect_false() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        try {
            assert !composite.isDirect();
        } finally {
            composite.free();
        }
    }

    // ========== Byte Order ==========

    @Test
    public void test_order_default_big_endian() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        try {
            assert composite.order() == ByteOrder.BIG_ENDIAN;
        } finally {
            composite.free();
        }
    }

    // ========== Dynamic Append After Read ==========

    @Test
    public void test_addComponent_after_partial_read() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1, 2 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 3, 4 });
        try {
            composite.addComponent(buf1);
            composite.readByte(); // read 1

            // Dynamically append more data
            composite.addComponent(buf2);

            assert composite.readableBytes() == 3;
            assert composite.readByte() == 2;
            assert composite.readByte() == 3;
            assert composite.readByte() == 4;
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    @Test
    public void test_addComponent_after_discard() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1, 2 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 3, 4 });
        ByteBuf buf3 = ByteBuf.wrap(new byte[] { 5, 6 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            // Read and discard buf1
            composite.readByte();
            composite.readByte();
            composite.discardReadBytes();

            // Append new data
            composite.addComponent(buf3);

            assert composite.readableBytes() == 4;
            assert composite.readByte() == 3;
            assert composite.readByte() == 4;
            assert composite.readByte() == 5;
            assert composite.readByte() == 6;
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
            freeIfOwned(buf3);
        }
    }

    // ========== decompose ==========

    @Test
    public void test_decompose() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { 1 });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { 2 });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            List<ByteBuf> components = composite.decompose();
            assert components.size() == 2;
            assert components.get(0) == buf1;
            assert components.get(1) == buf2;
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    @Test(expected = UnsupportedOperationException.class)
    public void test_decompose_unmodifiable() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1 });
        try {
            composite.addComponent(buf);
            List<ByteBuf> components = composite.decompose();
            components.add(buf);
        } finally {
            composite.free();
            freeIfOwned(buf);
        }
    }

    // ========== Mark / Reset ==========

    @Test
    public void test_markReader_resetReader() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3, 4 });
        try {
            composite.addComponent(buf);

            composite.readByte(); // 1
            composite.markReader();
            composite.readByte(); // 2
            composite.readByte(); // 3
            composite.resetReader();

            assert composite.readableBytes() == 3;
            assert composite.readByte() == 2;
        } finally {
            composite.free();
            freeIfOwned(buf);
        }
    }

    // ========== SkipReadableBytes ==========

    @Test
    public void test_skipReadableBytes() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3, 4, 5 });
        try {
            composite.addComponent(buf);
            composite.skipReadableBytes(3);
            assert composite.readableBytes() == 2;
            assert composite.readByte() == 4;
        } finally {
            composite.free();
            freeIfOwned(buf);
        }
    }

    // ========== toString ==========

    @Test
    public void test_toString() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        try {
            composite.addComponent(buf);
            String str = composite.toString();
            assert str.contains("CompositeByteBuf");
        } finally {
            composite.free();
            freeIfOwned(buf);
        }
    }

    // ========== Unsigned Read Operations ==========

    @Test
    public void test_readUInt8() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { (byte) 0xFF });
        try {
            composite.addComponent(buf);
            short value = composite.readUInt8();
            assert value == 255 : "expected 255, got " + value;
        } finally {
            composite.free();
            freeIfOwned(buf);
        }
    }

    @Test
    public void test_readUInt16_spanning() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { (byte) 0xFF });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { (byte) 0xFF });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);
            int value = composite.readUInt16();
            assert value == 65535 : "expected 65535, got " + value;
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    // ========== asReadOnly ==========

    @Test
    public void test_asReadOnly() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf = ByteBuf.wrap(new byte[] { 1, 2, 3 });
        try {
            composite.addComponent(buf.retain()); // buf refCnt: 1 -> 2 via explicit shared retain

            ByteBuf readOnly = composite.asReadOnly();
            // ReadOnlyByteBuf delegates free() to composite,
            // so readOnly.free() == composite.free()
            assert readOnly != null;
            assert readOnly.readableBytes() == 3;
            assert readOnly.readByte() == 1;

            // free via readOnly (which frees composite internally)
            readOnly.free(); // composite refCnt: 1 -> 0, releases the shared retain, buf refCnt: 2 -> 1
        } finally {
            freeIfOwned(buf); // buf refCnt: 1 -> 0
        }
    }

    // ========== Large Data Test ==========

    @Test
    public void test_large_number_of_components() {
        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf[] bufs = new ByteBuf[100];
        try {
            for (int i = 0; i < 100; i++) {
                bufs[i] = ByteBuf.wrap(new byte[] { (byte) i });
                composite.addComponent(bufs[i]);
            }

            assert composite.numComponents() == 100;
            assert composite.readableBytes() == 100;

            for (int i = 0; i < 100; i++) {
                assert composite.readByte() == (byte) i;
            }
        } finally {
            composite.free();
            for (ByteBuf b : bufs) {
                if (b != null)
                    freeIfOwned(b);
            }
        }
    }

    // ========== Edge Case: ReadableBytes from partially-read component ==========

    @Test
    public void test_addComponent_partially_read_source() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 10, 20, 30, 40, 50 });
        buf.readByte(); // skip first byte, readableBytes=4
        buf.readByte(); // skip second byte, readableBytes=3

        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        try {
            composite.addComponent(buf);
            assert composite.readableBytes() == 3;
            assert composite.readByte() == 30;
            assert composite.readByte() == 40;
            assert composite.readByte() == 50;
        } finally {
            composite.free();
            freeIfOwned(buf);
        }
    }

    // ========== Float/Double spanning ==========

    @Test
    public void test_readFloat32_spanning() {
        int intBits = Float.floatToRawIntBits(3.14f);
        byte[] bytes = new byte[4];
        bytes[0] = (byte) (intBits >>> 24);
        bytes[1] = (byte) (intBits >>> 16);
        bytes[2] = (byte) (intBits >>> 8);
        bytes[3] = (byte) intBits;

        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { bytes[0], bytes[1] });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { bytes[2], bytes[3] });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            float value = composite.readFloat32();
            assert Math.abs(value - 3.14f) < 0.001f : "expected ~3.14, got " + value;
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }

    @Test
    public void test_readFloat64_spanning() {
        long longBits = Double.doubleToRawLongBits(2.718281828);
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (longBits >>> (56 - i * 8));
        }

        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        ByteBuf buf1 = ByteBuf.wrap(new byte[] { bytes[0], bytes[1], bytes[2] });
        ByteBuf buf2 = ByteBuf.wrap(new byte[] { bytes[3], bytes[4], bytes[5], bytes[6], bytes[7] });
        try {
            composite.addComponent(buf1);
            composite.addComponent(buf2);

            double value = composite.readFloat64();
            assert Math.abs(value - 2.718281828) < 0.000001 : "expected ~2.718281828, got " + value;
        } finally {
            composite.free();
            freeIfOwned(buf1);
            freeIfOwned(buf2);
        }
    }
}
