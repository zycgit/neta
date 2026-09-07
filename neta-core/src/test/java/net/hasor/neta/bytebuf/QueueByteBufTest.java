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
import java.nio.charset.StandardCharsets;
import net.hasor.neta.channel.data.ProtoQueue;
import org.junit.Test;

/**
 * Comprehensive tests for {@link QueueByteBuf}.
 */
public class QueueByteBufTest {

    @Test
    public void test_search_respects_component_boundaries_and_reader_offsets() {
        byte[] data = "a\nbc\nd\nef\ng".getBytes(StandardCharsets.US_ASCII);
        for (int mode = 0; mode < 3; mode++) {
            for (int split = 1; split < data.length; split++) {
                ProtoQueue<ByteBuf> queue = newQueue();
                for (int part = 0; part < 2; part++) {
                    int from = part == 0 ? 0 : split;
                    int length = part == 0 ? split : data.length - split;
                    ByteBuf input;
                    if (mode == 0) {
                        byte[] padded = new byte[length + 2];
                        System.arraycopy(data, from, padded, 2, length);
                        input = ByteBuf.wrap(padded);
                    } else {
                        ByteBuffer buffer = mode == 1 ? ByteBuffer.allocate(length + 2) : ByteBuffer.allocateDirect(length + 2);
                        buffer.position(2);
                        buffer.put(data, from, length).flip();
                        input = ByteBuf.wrap(buffer.asReadOnlyBuffer());
                    }
                    input.skipReadableBytes(2);
                    queue.offerMessage(input);
                    queue.offerMessage(ByteBuf.EMPTY);
                }
                QueueByteBuf buffer = new QueueByteBuf(queue);
                try {
                    for (int start = 0; start <= data.length; start++) {
                        for (int limit = -1; limit <= data.length + 1; limit++) {
                            int first = -1;
                            int last = -1;
                            for (int i = start; i < Math.min(data.length, start + Math.max(0, limit)); i++) {
                                if (data[i] == '\n') {
                                    if (first < 0) {
                                        first = i - start;
                                    }
                                    last = i - start;
                                }
                            }
                            org.junit.Assert.assertEquals(first, buffer.expect((byte) '\n', limit));
                            org.junit.Assert.assertEquals(last, buffer.expectLast((byte) '\n', limit));
                            org.junit.Assert.assertEquals(start, buffer.readerIndex());
                        }
                        if (start < data.length) {
                            buffer.skipReadableBytes(1);
                        }
                    }
                    buffer.markReader();
                    offer(queue, "x\ny");
                    buffer.refresh();
                    org.junit.Assert.assertEquals(1, buffer.expect((byte) '\n', 3));
                    org.junit.Assert.assertEquals(1, buffer.expectLast((byte) '\n', 3));
                } finally {
                    buffer.free();
                    queue.clearAndRelease();
                }
            }
        }
    }

    // ---- Helper ----

    private ProtoQueue<ByteBuf> newQueue() {
        return new ProtoQueue<>(-1);
    }

    private void offer(ProtoQueue<ByteBuf> queue, byte[] data) {
        queue.offerMessage(ByteBuf.wrap(data));
    }

    private void offer(ProtoQueue<ByteBuf> queue, String text) {
        offer(queue, text.getBytes(StandardCharsets.UTF_8));
    }

    // ========== Factory Tests ==========

    @Test
    public void test_factory_creates_instance() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2, 3 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf != null;
            assert buf.readableBytes() == 3;
        } finally {
            buf.free();
        }
    }

    @Test(expected = NullPointerException.class)
    public void test_factory_null_throws() {
        ByteBufUtils.queueBuffer(null);
    }

    // ========== Empty Queue Tests ==========

    @Test
    public void test_empty_queue() {
        ProtoQueue<ByteBuf> queue = newQueue();
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.readableBytes() == 0;
            assert buf.numComponents() == 0;
            assert buf.capacity() == 0;
            assert !buf.isFree();
        } finally {
            buf.free();
        }
    }

    // ========== Single Component Read Tests ==========

    @Test
    public void test_readByte_single_component() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 10, 20, 30 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.numComponents() == 1;
            assert buf.readByte() == 10;
            assert buf.readByte() == 20;
            assert buf.readByte() == 30;
            assert buf.readableBytes() == 0;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_readBytes_single_component() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2, 3, 4, 5 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            byte[] dst = new byte[5];
            buf.readBytes(dst);
            assert dst[0] == 1;
            assert dst[1] == 2;
            assert dst[2] == 3;
            assert dst[3] == 4;
            assert dst[4] == 5;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_readInt32_single_component() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 0, 0, 1, 0 }); // 256 in big-endian
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.readInt32() == 256;
        } finally {
            buf.free();
        }
    }

    // ========== Multi-Component Read Tests ==========

    @Test
    public void test_readByte_across_components() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        offer(queue, new byte[] { 3, 4 });
        offer(queue, new byte[] { 5 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.numComponents() == 3;
            assert buf.readableBytes() == 5;
            assert buf.readByte() == 1;
            assert buf.readByte() == 2;
            assert buf.readByte() == 3; // crosses component boundary
            assert buf.readByte() == 4;
            assert buf.readByte() == 5; // crosses component boundary
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_refresh_only_appends_new_queue_messages() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });

        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.readableBytes() == 2;
            offer(queue, new byte[] { 3, 4, 5 });

            buf.refresh();

            assert buf.numComponents() == 2;
            byte[] dst = new byte[5];
            buf.readBytes(dst);
            assert dst[0] == 1;
            assert dst[1] == 2;
            assert dst[2] == 3;
            assert dst[3] == 4;
            assert dst[4] == 5;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_readBytes_across_components() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        offer(queue, new byte[] { 3, 4, 5 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            byte[] dst = new byte[5];
            buf.readBytes(dst);
            assert dst[0] == 1;
            assert dst[1] == 2;
            assert dst[2] == 3;
            assert dst[3] == 4;
            assert dst[4] == 5;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_readInt32_across_components() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 0, 0 });     // first 2 bytes of int32
        offer(queue, new byte[] { 1, 0 });     // last 2 bytes of int32
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.readInt32() == 256;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_readInt64_across_three_components() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 0, 0, 0 });
        offer(queue, new byte[] { 0, 0, 0 });
        offer(queue, new byte[] { 0, 1 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.readInt64() == 1L;
        } finally {
            buf.free();
        }
    }

    // ========== Get (Non-consuming) Operations ==========

    @Test
    public void test_getByte_does_not_consume() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 42, 43, 44 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.getByte(0) == 42;
            assert buf.getByte(1) == 43;
            assert buf.getByte(2) == 44;
            assert buf.readableBytes() == 3; // not consumed
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_getByte_across_components() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 10, 20 });
        offer(queue, new byte[] { 30, 40 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.getByte(0) == 10;
            assert buf.getByte(1) == 20;
            assert buf.getByte(2) == 30;
            assert buf.getByte(3) == 40;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_getInt32_across_components() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 0, 0 });
        offer(queue, new byte[] { 4, 0 }); // 1024 in big-endian
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.getInt32(0) == 1024;
            assert buf.readableBytes() == 4; // not consumed
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_getBytes_into_array() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2, 3 });
        offer(queue, new byte[] { 4, 5 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            byte[] dst = new byte[4];
            buf.getBytes(1, dst, 0, 4);
            assert dst[0] == 2;
            assert dst[1] == 3;
            assert dst[2] == 4;
            assert dst[3] == 5;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_getBuffer_into_bytebuf() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        offer(queue, new byte[] { 3, 4 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            ByteBuf dst = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(4);
            try {
                buf.getBuffer(0, dst, 4);
                dst.markWriter();
                assert dst.readByte() == 1;
                assert dst.readByte() == 2;
                assert dst.readByte() == 3;
                assert dst.readByte() == 4;
            } finally {
                dst.free();
            }
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_getBuffer_into_nio_bytebuffer() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 10, 20 });
        offer(queue, new byte[] { 30, 40 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            ByteBuffer dst = ByteBuffer.allocate(4);
            buf.getBuffer(0, dst, 4);
            dst.flip();
            assert dst.get() == 10;
            assert dst.get() == 20;
            assert dst.get() == 30;
            assert dst.get() == 40;
        } finally {
            buf.free();
        }
    }

    // ========== String Operations ==========

    @Test
    public void test_readString_across_components() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, "Hel");
        offer(queue, "lo ");
        offer(queue, "World");
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            String result = buf.readString(11, StandardCharsets.UTF_8);
            assert "Hello World".equals(result);
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_getString_across_components() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, "AB");
        offer(queue, "CD");
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            String result = buf.getString(1, 2, StandardCharsets.US_ASCII);
            assert "BC".equals(result);
            assert buf.readableBytes() == 4; // not consumed
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_readLine_across_components() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, "Hello\r");
        offer(queue, "\n");
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            String line = buf.readLine();
            assert "Hello".equals(line) : "got: " + line;
            assert buf.readableBytes() == 0;
        } finally {
            buf.free();
        }
    }

    // ========== Write Operations (Unsupported) ==========

    @Test(expected = UnsupportedOperationException.class)
    public void test_writeByte_throws() {
        ProtoQueue<ByteBuf> queue = newQueue();
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            buf.writeByte((byte) 1);
        } finally {
            buf.free();
        }
    }

    @Test(expected = UnsupportedOperationException.class)
    public void test_writeBytes_throws() {
        ProtoQueue<ByteBuf> queue = newQueue();
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            buf.writeBytes(new byte[] { 1, 2 });
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_writableBytes_is_zero() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.writableBytes() == 0;
            assert buf.writtenBytes() == 0;
        } finally {
            buf.free();
        }
    }

    // ========== markReader Tests (consumes from queue) ==========

    @Test
    public void test_markReader_consumes_fully_read_single() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2, 3 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert queue.queueSize() == 1;
            buf.readByte(); // 1
            buf.readByte(); // 2
            buf.readByte(); // 3
            buf.markReader();
            // ByteBuf fully consumed -> queue message skipped
            assert queue.queueSize() == 0;
            assert buf.numComponents() == 0;
            assert buf.readableBytes() == 0;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_markReader_consumes_fully_read_multiple() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        offer(queue, new byte[] { 3, 4 });
        offer(queue, new byte[] { 5, 6 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert queue.queueSize() == 3;
            // Read 4 bytes -> consumes first two components entirely
            buf.readByte(); // 1
            buf.readByte(); // 2
            buf.readByte(); // 3
            buf.readByte(); // 4
            buf.markReader();
            assert queue.queueSize() == 1; // only third remains
            assert buf.numComponents() == 1;
            assert buf.readableBytes() == 2;
            assert buf.readByte() == 5;
            assert buf.readByte() == 6;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_markReader_consumes_partially_read() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2, 3 });
        offer(queue, new byte[] { 4, 5, 6 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            // Read 4 bytes -> first component fully consumed, second partially (1 of 3)
            buf.readByte(); // 1
            buf.readByte(); // 2
            buf.readByte(); // 3
            buf.readByte(); // 4
            buf.markReader();
            assert queue.queueSize() == 1; // first skipped
            assert buf.numComponents() == 1;
            assert buf.readableBytes() == 2;
            assert buf.readByte() == 5;
            assert buf.readByte() == 6;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_markReader_no_read_is_noop() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2, 3 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            buf.markReader(); // readerIndex == 0, no-op
            assert queue.queueSize() == 1;
            assert buf.readableBytes() == 3;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_markReader_then_continue_reading() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        offer(queue, new byte[] { 3, 4 });
        offer(queue, new byte[] { 5, 6 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            // First read & mark
            buf.readByte(); // 1
            buf.readByte(); // 2
            buf.markReader();
            assert buf.readableBytes() == 4;
            assert queue.queueSize() == 2;

            // Second read & mark
            buf.readByte(); // 3
            buf.readByte(); // 4
            buf.readByte(); // 5
            buf.markReader();
            assert buf.readableBytes() == 1;
            assert queue.queueSize() == 1; // partial third remains in queue

            assert buf.readByte() == 6;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_markReader_resets_indices() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2, 3, 4, 5 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            buf.readByte(); // 1
            buf.readByte(); // 2
            buf.markReader(); // consumes first 2 bytes, resets readerIndex to 0
            assert buf.readerIndex() == 0;
            assert buf.readableBytes() == 3;
            assert buf.readByte() == 3;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_resetReader_after_markReader() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2, 3, 4, 5 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            buf.readByte(); // 1
            buf.readByte(); // 2
            buf.markReader(); // consume 1,2 from queue; mark at 0
            buf.readByte(); // 3
            buf.readByte(); // 4
            buf.resetReader(); // back to mark (0)
            assert buf.readableBytes() == 3;
            assert buf.readByte() == 3;
        } finally {
            buf.free();
        }
    }

    // ========== discardReadBytes Tests ==========

    @Test
    public void test_discard_fully_consumed_single() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2, 3 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert queue.queueSize() == 1;
            buf.readByte();
            buf.readByte();
            buf.readByte();
            buf.discardReadBytes();
            // ByteBuf fully consumed -> queue message skipped
            assert queue.queueSize() == 0;
            assert buf.numComponents() == 0;
            assert buf.readableBytes() == 0;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_discard_fully_consumed_multiple() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        offer(queue, new byte[] { 3, 4 });
        offer(queue, new byte[] { 5, 6 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert queue.queueSize() == 3;
            // Read 4 bytes -> consumes first two components entirely
            buf.readByte();
            buf.readByte();
            buf.readByte();
            buf.readByte();
            buf.discardReadBytes();
            assert queue.queueSize() == 1; // only third remains
            assert buf.numComponents() == 1;
            assert buf.readableBytes() == 2;
            assert buf.readByte() == 5;
            assert buf.readByte() == 6;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_discard_partially_consumed() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2, 3 });
        offer(queue, new byte[] { 4, 5, 6 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            // Read 4 bytes -> first component fully consumed, second partially (1 of 3)
            buf.readByte();
            buf.readByte();
            buf.readByte();
            buf.readByte();
            buf.discardReadBytes();
            assert queue.queueSize() == 1; // first skipped
            assert buf.numComponents() == 1;
            assert buf.readableBytes() == 2;
            assert buf.readByte() == 5;
            assert buf.readByte() == 6;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_discard_no_read() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2, 3 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            buf.discardReadBytes(); // readerIndex == 0, no-op
            assert queue.queueSize() == 1;
            assert buf.readableBytes() == 3;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_discard_then_continue_reading() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        offer(queue, new byte[] { 3, 4 });
        offer(queue, new byte[] { 5, 6 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            // First read & discard
            buf.readByte(); // 1
            buf.readByte(); // 2
            buf.discardReadBytes();
            assert buf.readableBytes() == 4;
            assert queue.queueSize() == 2;

            // Second read & discard
            buf.readByte(); // 3
            buf.readByte(); // 4
            buf.readByte(); // 5
            buf.discardReadBytes();
            assert buf.readableBytes() == 1;
            assert queue.queueSize() == 1; // partial third remains in queue

            assert buf.readByte() == 6;
        } finally {
            buf.free();
        }
    }

    // ========== refresh() Tests ==========

    @Test
    public void test_refresh_picks_up_new_messages() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.readableBytes() == 2;

            // Add more data to queue
            offer(queue, new byte[] { 3, 4 });
            assert buf.readableBytes() == 2; // not yet refreshed

            buf.refresh();
            assert buf.readableBytes() == 4; // now includes new data
            assert buf.numComponents() == 2;

            assert buf.readByte() == 1;
            assert buf.readByte() == 2;
            assert buf.readByte() == 3;
            assert buf.readByte() == 4;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_refresh_after_discard() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            buf.readByte(); // 1
            buf.readByte(); // 2
            buf.discardReadBytes();
            assert buf.readableBytes() == 0;

            // Add new data
            offer(queue, new byte[] { 3, 4 });
            buf.refresh();
            assert buf.readableBytes() == 2;
            assert buf.readByte() == 3;
            assert buf.readByte() == 4;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_refresh_after_markReader() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            buf.readByte(); // 1
            buf.readByte(); // 2
            buf.markReader(); // consume from queue
            assert buf.readableBytes() == 0;

            // Add new data
            offer(queue, new byte[] { 3, 4 });
            buf.refresh();
            assert buf.readableBytes() == 2;
            assert buf.readByte() == 3;
            assert buf.readByte() == 4;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_refresh_no_new_data() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            buf.refresh(); // no-op, no new data
            assert buf.readableBytes() == 2;
            assert buf.numComponents() == 1;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_refresh_chaining() {
        ProtoQueue<ByteBuf> queue = newQueue();
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            QueueByteBuf result = buf.refresh();
            assert result == buf;
        } finally {
            buf.free();
        }
    }

    // ========== Reader Index / Reset Tests ==========

    @Test
    public void test_resetReader_reverts_position() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2, 3 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            buf.readByte(); // 1
            buf.readByte(); // 2
            buf.resetReader(); // back to start
            assert buf.readableBytes() == 3;
            assert buf.readByte() == 1;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_skipReadableBytes() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2, 3, 4, 5 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            buf.skipReadableBytes(3);
            assert buf.readableBytes() == 2;
            assert buf.readByte() == 4;
        } finally {
            buf.free();
        }
    }

    // ========== Copy Tests ==========

    @Test
    public void test_copy_single_component() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2, 3 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            ByteBuf copy = buf.copy();
            try {
                assert copy.readableBytes() == 3;
                assert copy.readByte() == 1;
                assert copy.readByte() == 2;
                assert copy.readByte() == 3;
                // Original unchanged
                assert buf.readableBytes() == 3;
            } finally {
                copy.free();
            }
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_copy_multi_component() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        offer(queue, new byte[] { 3, 4 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            ByteBuf copy = buf.copy();
            try {
                assert copy.readableBytes() == 4;
                assert copy.readByte() == 1;
                assert copy.readByte() == 2;
                assert copy.readByte() == 3;
                assert copy.readByte() == 4;
            } finally {
                copy.free();
            }
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_copy_empty() {
        ProtoQueue<ByteBuf> queue = newQueue();
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            ByteBuf copy = buf.copy();
            assert copy.readableBytes() == 0;
        } finally {
            buf.free();
        }
    }

    // ========== sliceOff Tests ==========

    @Test
    public void test_sliceOff_single_component() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2, 3, 4, 5 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            ByteBuf front = buf.sliceOff(3);
            try {
                assert front.readableBytes() == 3;
                assert front.readByte() == 1;
                assert front.readByte() == 2;
                assert front.readByte() == 3;
                assert buf.readableBytes() == 2;
                assert buf.readByte() == 4;
                assert buf.readByte() == 5;
            } finally {
                front.free();
            }
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_sliceOff_across_components() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        offer(queue, new byte[] { 3, 4 });
        offer(queue, new byte[] { 5, 6 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            ByteBuf front = buf.sliceOff(3);
            try {
                assert front instanceof CompositeByteBuf;
                assert front.readableBytes() == 3;
                assert front.readByte() == 1;
                assert front.readByte() == 2;
                assert front.readByte() == 3;
                // first component fully consumed, second partially
                assert buf.readableBytes() == 3;
                assert buf.readByte() == 4;
                assert buf.readByte() == 5;
                assert buf.readByte() == 6;
            } finally {
                front.free();
            }
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_sliceOff_partial_view_survives_queue_consumption() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, "abc");
        offer(queue, "def");
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            ByteBuf front = buf.sliceOff(4);
            try {
                assert "abcd".equals(front.readString(front.readableBytes(), StandardCharsets.US_ASCII));
                assert "ef".equals(buf.readString(buf.readableBytes(), StandardCharsets.US_ASCII));
            } finally {
                front.free();
            }
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_slice_view_survives_queue_consumption() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, "abc");
        offer(queue, "def");
        QueueByteBuf buf = new QueueByteBuf(queue);
        ByteBuf slice = buf.slice(1, 4);
        try {
            buf.skipReadableBytes(buf.readableBytes());
            buf.discardReadBytes();
            buf.release();

            assert "bcde".equals(slice.readString(slice.readableBytes(), StandardCharsets.US_ASCII));
        } finally {
            slice.release();
            if (!buf.isFree()) {
                buf.release();
            }
        }
    }

    @Test
    public void test_slice_has_independent_indices() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, "abc");
        QueueByteBuf buf = new QueueByteBuf(queue);
        ByteBuf slice = buf.slice(0, 3);
        try {
            assert "abc".equals(slice.readString(slice.readableBytes(), StandardCharsets.US_ASCII));
            assert "abc".equals(buf.readString(buf.readableBytes(), StandardCharsets.US_ASCII));
        } finally {
            slice.release();
            buf.release();
        }
    }

    @Test
    public void test_sliceOff_exact_component_boundary() {
        // sliceOff at exact component boundary should be fully zero-copy
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        offer(queue, new byte[] { 3, 4 });
        offer(queue, new byte[] { 5, 6 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            ByteBuf front = buf.sliceOff(4); // exactly first 2 components
            try {
                assert front instanceof CompositeByteBuf;
                assert front.readableBytes() == 4;
                assert front.readByte() == 1;
                assert front.readByte() == 2;
                assert front.readByte() == 3;
                assert front.readByte() == 4;
                assert buf.readableBytes() == 2;
                assert buf.readByte() == 5;
                assert buf.readByte() == 6;
            } finally {
                front.free();
            }
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_sliceOff_zero_returns_empty() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2, 3 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            ByteBuf front = buf.sliceOff(0);
            assert front.readableBytes() == 0;
            assert buf.readableBytes() == 3;
        } finally {
            buf.free();
        }
    }

    // ========== Capacity & Properties Tests ==========

    @Test
    public void test_capacity_equals_total_readable() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        offer(queue, new byte[] { 3, 4, 5 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.capacity() == 5;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_isDirect_is_false() {
        ProtoQueue<ByteBuf> queue = newQueue();
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert !buf.isDirect();
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_queue_accessor() {
        ProtoQueue<ByteBuf> queue = newQueue();
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.queue() == queue;
        } finally {
            buf.free();
        }
    }

    // ========== Lifecycle Tests ==========

    @Test
    public void test_free_clears_components() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        buf.free();
        assert buf.isFree();
    }

    @Test(expected = IllegalStateException.class)
    public void test_readByte_after_free_throws() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        buf.free();
        buf.readByte();
    }

    @Test(expected = IllegalStateException.class)
    public void test_refresh_after_free_throws() {
        ProtoQueue<ByteBuf> queue = newQueue();
        QueueByteBuf buf = new QueueByteBuf(queue);
        buf.free();
        buf.refresh();
    }

    // ========== Queue Integration Tests ==========

    @Test
    public void test_markReader_after_queue_consumption() {
        // Simulate the handler pattern: read -> markReader
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        offer(queue, new byte[] { 3, 4 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            buf.readByte(); // 1
            buf.readByte(); // 2
            buf.markReader(); // consume first message

            // Queue should have 1 remaining message
            assert queue.queueSize() == 1;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_no_markReader_keeps_queue_unchanged() {
        // Simulate probing buffered bytes, then rewinding QueueByteBuf's local reader cursor.
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        offer(queue, new byte[] { 3, 4 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            buf.readByte(); // 1
            buf.readByte(); // 2
            buf.readByte(); // 3
            buf.resetReader(); // rewind local reader cursor

            // Underlying queue is unchanged because markReader() was never called.
            assert queue.queueSize() == 2;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_expect_line_across_components() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, "GET /index.htm");
        offer(queue, "l HTTP/1.1\r\n");
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            int lineEnd = buf.expectLine();
            assert lineEnd >= 0;
            String line = buf.readString(lineEnd, StandardCharsets.US_ASCII);
            buf.skipReadableBytes(2); // skip \r\n
            assert "GET /index.html HTTP/1.1".equals(line) : "got: " + line;
        } finally {
            buf.free();
        }
    }

    // ========== Edge Cases ==========

    @Test
    public void test_empty_buffers_in_queue_are_skipped() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[0]); // empty
        offer(queue, new byte[] { 1, 2 });
        offer(queue, new byte[0]); // empty
        offer(queue, new byte[] { 3 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.numComponents() == 2; // empty ones skipped
            assert buf.readableBytes() == 3;
            assert buf.readByte() == 1;
            assert buf.readByte() == 2;
            assert buf.readByte() == 3;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_large_number_of_components() {
        ProtoQueue<ByteBuf> queue = newQueue();
        for (int i = 0; i < 100; i++) {
            offer(queue, new byte[] { (byte) i });
        }
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.numComponents() == 100;
            assert buf.readableBytes() == 100;
            for (int i = 0; i < 100; i++) {
                assert buf.readByte() == (byte) i;
            }
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_random_access_with_binary_search() {
        // Access components in reverse order to exercise binary search
        ProtoQueue<ByteBuf> queue = newQueue();
        for (int i = 0; i < 10; i++) {
            offer(queue, new byte[] { (byte) (i * 10), (byte) (i * 10 + 1) });
        }
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            // Access last component first
            assert buf.getByte(18) == (byte) 90;
            // Then first component
            assert buf.getByte(0) == 0;
            // Middle component
            assert buf.getByte(10) == 50;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_asByteArray() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        offer(queue, new byte[] { 3, 4 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            byte[] arr = buf.asByteArray();
            assert arr.length == 4;
            assert arr[0] == 1;
            assert arr[1] == 2;
            assert arr[2] == 3;
            assert arr[3] == 4;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_toString_format() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            String s = buf.toString();
            assert s.startsWith("QueueByteBuf[");
        } finally {
            buf.free();
        }
    }

    // ========== Protocol Decoder Simulation ==========

    @Test
    public void test_protocol_decoder_simulation() {
        // Simulate a length-prefixed protocol decoder
        ProtoQueue<ByteBuf> queue = newQueue();

        // First message: a complete frame (4-byte length + payload)
        ByteBuf frame1 = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(64);
        frame1.writeInt32(5);
        frame1.writeString("Hello", StandardCharsets.UTF_8);
        frame1.markWriter();
        byte[] frame1Data = frame1.asByteArray();
        frame1.free();
        offer(queue, frame1Data);

        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            // Decoder logic: check if enough data for length prefix
            assert buf.readableBytes() >= 4;
            int len = buf.readInt32();
            assert len == 5;

            // Check if enough data for payload
            assert buf.readableBytes() >= len;
            String payload = buf.readString(len, StandardCharsets.UTF_8);
            assert "Hello".equals(payload);

            // Finalize the already-consumed prefix inside QueueByteBuf.
            buf.markReader();
            assert buf.readableBytes() == 0;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_protocol_decoder_fragmented_input() {
        // Simulate fragmented arrival: length in first ByteBuf, payload split across two
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 0, 0, 0, 5 }); // length = 5
        offer(queue, "He".getBytes(StandardCharsets.UTF_8));
        offer(queue, "llo".getBytes(StandardCharsets.UTF_8));

        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            int len = buf.readInt32();
            assert len == 5;
            String payload = buf.readString(len, StandardCharsets.UTF_8);
            assert "Hello".equals(payload);
            buf.markReader();
            assert queue.queueSize() == 0;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_protocol_decoder_insufficient_data() {
        // Simulate insufficient data: length header received but payload incomplete
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 0, 0, 0, 10 }); // length = 10
        offer(queue, "He".getBytes(StandardCharsets.UTF_8)); // only 2 bytes, need 10

        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            // Peek at length without consuming
            int len = buf.getInt32(0);
            assert len == 10;
            assert buf.readableBytes() < 4 + len; // not enough data

            // Don't consume, don't markReader
            // Queue remains unchanged
            assert queue.queueSize() == 2;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_multiple_frames_with_refresh() {
        ProtoQueue<ByteBuf> queue = newQueue();
        // First frame
        offer(queue, new byte[] { 0, 0, 0, 3 }); // length = 3
        offer(queue, "abc".getBytes(StandardCharsets.UTF_8));

        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            // Decode first frame
            int len1 = buf.readInt32();
            String payload1 = buf.readString(len1, StandardCharsets.UTF_8);
            assert "abc".equals(payload1);
            buf.markReader();

            // Second frame arrives
            offer(queue, new byte[] { 0, 0, 0, 2 }); // length = 2
            offer(queue, "de".getBytes(StandardCharsets.UTF_8));
            buf.refresh();

            // Decode second frame
            int len2 = buf.readInt32();
            String payload2 = buf.readString(len2, StandardCharsets.UTF_8);
            assert "de".equals(payload2);
            buf.markReader();

            assert buf.readableBytes() == 0;
        } finally {
            buf.free();
        }
    }

    // ========== UInt8 / UInt16 Tests ==========

    @Test
    public void test_getUInt8_across_components() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { (byte) 0xFF });
        offer(queue, new byte[] { (byte) 0x80 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.getUInt8(0) == 255;
            assert buf.getUInt8(1) == 128;
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_readUInt16_across_components() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { (byte) 0xFF });
        offer(queue, new byte[] { (byte) 0xFF });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            assert buf.readUInt16() == 65535;
        } finally {
            buf.free();
        }
    }

    // ========== readBuffer Tests ==========

    @Test
    public void test_readBuffer_into_bytebuf() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 1, 2 });
        offer(queue, new byte[] { 3, 4 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            ByteBuf dst = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.buffer(4);
            try {
                buf.readBuffer(dst, 4);
                dst.markWriter();
                assert dst.readableBytes() == 4;
                assert dst.readByte() == 1;
                assert dst.readByte() == 2;
                assert dst.readByte() == 3;
                assert dst.readByte() == 4;
                assert buf.readableBytes() == 0;
            } finally {
                dst.free();
            }
        } finally {
            buf.free();
        }
    }

    @Test
    public void test_readBuffer_into_nio() {
        ProtoQueue<ByteBuf> queue = newQueue();
        offer(queue, new byte[] { 10, 20 });
        offer(queue, new byte[] { 30 });
        QueueByteBuf buf = new QueueByteBuf(queue);
        try {
            ByteBuffer dst = ByteBuffer.allocate(3);
            buf.readBuffer(dst, 3);
            dst.flip();
            assert dst.get() == 10;
            assert dst.get() == 20;
            assert dst.get() == 30;
            assert buf.readableBytes() == 0;
        } finally {
            buf.free();
        }
    }
}
