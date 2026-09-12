/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import net.hasor.neta.channel.data.ProtoQueue;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ByteBufSearchApiTest {
    @Test
    public void testArrayExpectAllByteValuesAlignmentsAndLimits() {
        for (int value = 0; value < 256; value++) {
            byte expected = (byte) value;
            byte other = (byte) (value ^ 1);
            for (int prefix = 1; prefix <= 16; prefix++) {
                byte[] data = new byte[prefix + 64];
                Arrays.fill(data, other);
                data[0] = expected;
                byte[] wrappedData = data.clone();
                byte[] sliceData = data.clone();
                ByteBuf wrapped = ByteBuf.wrap(wrappedData);
                ByteBuf growing = ByteBufUtils.UNPOOLED_HEAP_ALLOCATOR.heapBuffer(2, data.length);
                growing.writeBytes(data);
                growing.markWriter();
                ByteBuf parent = ByteBuf.wrap(sliceData);
                ByteBuf slice = parent.slice(1, data.length - 1);
                parent.release();
                wrapped.skipReadableBytes(prefix);
                growing.skipReadableBytes(prefix);
                slice.skipReadableBytes(prefix - 1);
                ByteBuf[] buffers = { wrapped, growing, slice };
                byte[][] arrays = { wrappedData, ((AutoArrayByteBuf) growing).target, sliceData };
                for (int variant = 0; variant < buffers.length; variant++) {
                    ByteBuf buffer = buffers[variant];
                    byte[] array = arrays[variant];
                    try {
                        buffer.order((value & 1) == 0 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
                        int reader = buffer.readerIndex();
                        int writer = buffer.writerIndex();
                        assertEquals(-1, buffer.expect(expected, Integer.MAX_VALUE));
                        for (int position = 0; position < 64; position++) {
                            array[prefix + position] = expected;
                            assertEquals(position, buffer.expect(expected, Integer.MAX_VALUE));
                            assertEquals(-1, buffer.expect(expected, position));
                            assertEquals(position, buffer.expect(expected, position + 1));
                            if (position < 63) {
                                array[prefix + position + 1] = expected;
                                assertEquals(position, buffer.expect(expected, 64));
                                array[prefix + position + 1] = other;
                            }
                            array[prefix + position] = other;
                        }
                        assertEquals(reader, buffer.readerIndex());
                        assertEquals(writer, buffer.writerIndex());
                    } finally {
                        buffer.release();
                    }
                }
            }
        }
    }

    @Test
    public void testArrayExpectRespectsReadableRangeAndScanLimit() {
        byte[] data = { 0x55, 0x66, (byte) 0x80, 0x11, (byte) 0x80 };
        ByteBuf growing = ByteBufAllocator.DEFAULT.heapBuffer(2, 32);
        growing.writeBytes(data);
        growing.markWriter();
        for (ByteBuf buffer : new ByteBuf[] { ByteBuf.wrap(data), growing }) {
            try {
                buffer.skipReadableBytes(2);
                assertEquals(-1, buffer.expect((byte) 0x80, 0));
                assertEquals(-1, buffer.expect((byte) 0x80, -1));
                assertEquals(0, buffer.expect((byte) 0x80, 1));
                assertEquals(-1, buffer.expect((byte) 0x11, 1));
                assertEquals(1, buffer.expect((byte) 0x11, 2));
                assertEquals(-1, buffer.expect((byte) 0x55, Integer.MAX_VALUE));
                assertEquals(2, buffer.readerIndex());
                assertEquals(5, buffer.writerIndex());
                buffer.skipReadableBytes(3);
                assertEquals(-1, buffer.expect((byte) 0, Integer.MAX_VALUE));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    public void testExpectBinaryAcrossQueueBuffer() {
        ProtoQueue<ByteBuf> queue = new ProtoQueue<>(-1);
        queue.offerMessage(ByteBuf.wrap(new byte[] { 0x01, 0x02 }));
        queue.offerMessage(ByteBuf.wrap(new byte[] { 0x03, 0x04 }));
        queue.offerMessage(ByteBuf.wrap(new byte[] { 0x05, 0x06 }));

        ByteBuf queueBuf = ByteBufUtils.queueBuffer(queue);
        try {
            assertEquals(2, queueBuf.expect(new byte[] { 0x03, 0x04, 0x05 }));
            assertEquals(1, queueBuf.expectLast(new byte[] { 0x02, 0x03, 0x04 }));
        } finally {
            queueBuf.free();
        }
    }

    @Test
    public void testReadLineWithScanLimitAcrossQueueBuffer() {
        ProtoQueue<ByteBuf> queue = new ProtoQueue<>(-1);
        queue.offerMessage(ByteBuf.wrap("GET /hel".getBytes(StandardCharsets.US_ASCII)));
        queue.offerMessage(ByteBuf.wrap("lo HTTP/1.1\r\nbody".getBytes(StandardCharsets.US_ASCII)));

        ByteBuf queueBuf = ByteBufUtils.queueBuffer(queue);
        try {
            assertEquals("GET /hello HTTP/1.1", queueBuf.readLine(StandardCharsets.US_ASCII, 64));
            assertEquals("body", queueBuf.readString(queueBuf.readableBytes(), StandardCharsets.US_ASCII));
        } finally {
            queueBuf.free();
        }
    }

    @Test
    public void testReadLineBufferViewSurvivesQueueConsumption() {
        ProtoQueue<ByteBuf> queue = new ProtoQueue<>(-1);
        queue.offerMessage(ByteBuf.wrap("Host: example.com\r\nnext".getBytes(StandardCharsets.US_ASCII)));

        ByteBuf queueBuf = ByteBufUtils.queueBuffer(queue);
        try {
            ByteBuf line = queueBuf.readLineBuffer(64);
            assertNotNull(line);

            StringView view = StringView.request(line, 0, line.readableBytes());
            try {
                queueBuf.markReader();
                line.free();
                assertEquals("Host: example.com", view.toString());
            } finally {
                view.release();
            }
        } finally {
            queueBuf.free();
        }
    }

    @Test
    public void testLineBufferExpectWorksForArraySliceView() {
        ProtoQueue<ByteBuf> queue = new ProtoQueue<>(-1);
        queue.offerMessage(ByteBuf.wrap("Header: some-value\r\n".getBytes(StandardCharsets.US_ASCII)));

        ByteBuf queueBuf = ByteBufUtils.queueBuffer(queue);
        try {
            ByteBuf line = queueBuf.readLineBuffer(64);
            assertNotNull(line);
            try {
                assertEquals(6, line.expect((byte) ':', line.readableBytes()));
                assertEquals(17, line.expectLast((byte) 'e', line.readableBytes()));
                assertEquals(-1, line.expect((byte) '\n', line.readableBytes()));
                assertEquals("Header: some-value", line.readString(line.readableBytes(), StandardCharsets.US_ASCII));
            } finally {
                line.free();
            }
        } finally {
            queueBuf.free();
        }
    }

    @Test
    public void testLineBufferSliceSurvivesWrappedSourceRelease() {
        ByteBuf source = ByteBuf.wrap("Header: value\r\nBody".getBytes(StandardCharsets.US_ASCII));
        try {
            ByteBuf line = source.readLineBuffer(64);
            assertNotNull(line);
            try {
                source.free();
                assertEquals("Header: value", line.readString(line.readableBytes(), StandardCharsets.US_ASCII));
            } finally {
                line.free();
            }
        } finally {
            if (!source.isFree()) {
                source.free();
            }
        }
    }

    @Test
    public void testQueueBufferSliceOffRemainsCorrectAfterRepeatedConsumption() {
        ProtoQueue<ByteBuf> queue = new ProtoQueue<>(-1);
        queue.offerMessage(ByteBuf.wrap("abc".getBytes(StandardCharsets.US_ASCII)));
        queue.offerMessage(ByteBuf.wrap("defg".getBytes(StandardCharsets.US_ASCII)));
        queue.offerMessage(ByteBuf.wrap("hijk".getBytes(StandardCharsets.US_ASCII)));

        ByteBuf queueBuf = ByteBufUtils.queueBuffer(queue);
        try {
            ByteBuf first = queueBuf.sliceOff(5);
            try {
                assertEquals("abcde", first.readString(first.readableBytes(), StandardCharsets.US_ASCII));
            } finally {
                first.free();
            }

            ByteBuf second = queueBuf.sliceOff(3);
            try {
                assertEquals("fgh", second.readString(second.readableBytes(), StandardCharsets.US_ASCII));
            } finally {
                second.free();
            }

            assertEquals("ijk", queueBuf.readString(queueBuf.readableBytes(), StandardCharsets.US_ASCII));
            queueBuf.markReader();
            assertEquals(0, queue.queueSize());
        } finally {
            queueBuf.free();
        }
    }

    @Test
    public void testQueueBufferMarkReaderDeferredPreservesSliceOffFrontier() {
        ProtoQueue<ByteBuf> queue = new ProtoQueue<>(-1);
        queue.offerMessage(ByteBuf.wrap("Header: value\r\nBody".getBytes(StandardCharsets.US_ASCII)));

        QueueByteBuf queueBuf = ByteBufUtils.queueBuffer(queue);
        try {
            ByteBuf line = queueBuf.readLineBuffer(64);
            assertNotNull(line);
            try {
                assertEquals("Header: value", line.readString(line.readableBytes(), StandardCharsets.US_ASCII));
            } finally {
                line.free();
            }

            queueBuf.markReaderDeferred();

            ByteBuf body = queueBuf.sliceOff(4);
            try {
                assertEquals("Body", body.readString(body.readableBytes(), StandardCharsets.US_ASCII));
            } finally {
                body.free();
            }

            queueBuf.markReader();
            assertEquals(0, queue.queueSize());
        } finally {
            queueBuf.free();
        }
    }

    @Test
    public void testReadExpectBinaryReturnsPayloadBeforeDelimiter() {
        ByteBuf buf = ByteBuf.wrap(new byte[] { 0x11, 0x12, 0x13, 0x00, 0x01, 0x21 });
        try {
            ByteBuf payload = buf.readExpect(new byte[] { 0x00, 0x01 });
            assertNotNull(payload);
            try {
                assertEquals(3, payload.readableBytes());
                assertEquals(0x11, payload.readUInt8());
                assertEquals(0x12, payload.readUInt8());
                assertEquals(0x13, payload.readUInt8());
            } finally {
                payload.free();
            }
            assertEquals(1, buf.readableBytes());
            assertEquals(0x21, buf.readUInt8());
        } finally {
            buf.free();
        }
    }
}
