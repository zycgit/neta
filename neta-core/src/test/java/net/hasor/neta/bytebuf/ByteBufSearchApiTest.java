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
import java.nio.charset.StandardCharsets;
import net.hasor.neta.channel.ProtoQueue;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ByteBufSearchApiTest {
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