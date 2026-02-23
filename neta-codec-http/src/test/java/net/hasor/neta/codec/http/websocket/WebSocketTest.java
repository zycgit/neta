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
package net.hasor.neta.codec.http.websocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import net.hasor.neta.channel.virtual.VrtTransfer;
import net.hasor.neta.codec.http.*;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for the websocket package:
 * {@link WebSocketOpcode}, {@link DefaultWebSocketFrame},
 * {@link WebSocketFrameDecoder}, {@link WebSocketFrameEncoder},
 * {@link WebSocketServerHandshaker}.
 */
public class WebSocketTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String readContent(WebSocketFrame frame) {
        ByteBuf c = frame.content();
        return c.readString(c.readableBytes(), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(ByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.readBytes(b, 0, b.length);
        return b;
    }

    // =========================================================================
    // WebSocketOpcode
    // =========================================================================

    private static ByteBuf buildRawFrame(int opcode, boolean fin, boolean masked, byte[] maskKey, byte[] payload) {
        int headerSize = 2;
        if (payload.length >= 126 && payload.length <= 65535) {
            headerSize += 2;
        }
        if (masked) {
            headerSize += 4;
        }
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(headerSize + payload.length, Integer.MAX_VALUE);
        // Byte 0
        buf.writeByte((byte) ((fin ? 0x80 : 0x00) | (opcode & 0x0F)));
        // Byte 1
        int lenByte = payload.length < 126 ? payload.length : 126;
        buf.writeByte((byte) ((masked ? 0x80 : 0x00) | lenByte));
        if (lenByte == 126) {
            buf.writeByte((byte) ((payload.length >> 8) & 0xFF));
            buf.writeByte((byte) (payload.length & 0xFF));
        }
        if (masked) {
            buf.writeBytes(maskKey, 0, 4);
            byte[] masked_payload = new byte[payload.length];
            for (int i = 0; i < payload.length; i++) {
                masked_payload[i] = (byte) (payload[i] ^ maskKey[i % 4]);
            }
            buf.writeBytes(masked_payload, 0, masked_payload.length);
        } else {
            buf.writeBytes(payload, 0, payload.length);
        }
        buf.markWriter();
        return buf;
    }

    @Test
    public void testOpcode_values() {
        assertEquals(0x0, WebSocketOpcode.CONTINUATION.code());
        assertEquals(0x1, WebSocketOpcode.TEXT.code());
        assertEquals(0x2, WebSocketOpcode.BINARY.code());
        assertEquals(0x8, WebSocketOpcode.CLOSE.code());
        assertEquals(0x9, WebSocketOpcode.PING.code());
        assertEquals(0xA, WebSocketOpcode.PONG.code());
    }

    @Test
    public void testOpcode_of_known() {
        assertSame(WebSocketOpcode.TEXT, WebSocketOpcode.of(0x1));
        assertSame(WebSocketOpcode.BINARY, WebSocketOpcode.of(0x2));
        assertSame(WebSocketOpcode.CLOSE, WebSocketOpcode.of(0x8));
    }

    // =========================================================================
    // DefaultWebSocketFrame – factories
    // =========================================================================

    @Test
    public void testOpcode_of_unknown_returnsNull() {
        // code 0x3 is reserved/unknown in RFC 6455 – of() returns null
        WebSocketOpcode op = WebSocketOpcode.of(0x3);
        assertNull(op);
    }

    @Test
    public void testTextFrame() {
        DefaultWebSocketFrame f = DefaultWebSocketFrame.text("Hello");
        assertEquals(WebSocketOpcode.TEXT, f.opcode());
        assertTrue(f.isFinalFragment());
        assertFalse(f.isMasked());
        assertNull(f.maskingKey());
        assertEquals("Hello", readContent(f));
    }

    @Test
    public void testBinaryFrame() {
        byte[] data = new byte[] { 1, 2, 3 };
        DefaultWebSocketFrame f = DefaultWebSocketFrame.binary(data);
        assertEquals(WebSocketOpcode.BINARY, f.opcode());
        assertTrue(f.isFinalFragment());
        assertEquals(3, f.content().readableBytes());
    }

    @Test
    public void testPingFrame() {
        DefaultWebSocketFrame f = DefaultWebSocketFrame.ping();
        assertEquals(WebSocketOpcode.PING, f.opcode());
        assertTrue(f.isFinalFragment());
        assertEquals(0, f.content().readableBytes());
    }

    @Test
    public void testPongFrame() {
        DefaultWebSocketFrame f = DefaultWebSocketFrame.pong();
        assertEquals(WebSocketOpcode.PONG, f.opcode());
        assertEquals(0, f.content().readableBytes());
    }

    @Test
    public void testCloseFrame_withStatusAndReason() {
        DefaultWebSocketFrame f = DefaultWebSocketFrame.close(1000, "Normal closure");
        assertEquals(WebSocketOpcode.CLOSE, f.opcode());
        // Payload: 2 bytes status + reason bytes
        ByteBuf c = f.content();
        assertTrue(c.readableBytes() >= 2);
        // status code big-endian
        byte hi = c.getByte(c.readerIndex());
        byte lo = c.getByte(c.readerIndex() + 1);
        int status = ((hi & 0xFF) << 8) | (lo & 0xFF);
        assertEquals(1000, status);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDefaultWebSocketFrame_nullOpcodeThrows() {
        new DefaultWebSocketFrame(null, true, false, null, ByteBuf.EMPTY);
    }

    // =========================================================================
    // WebSocketFrameEncoder – encode unmasked frames
    // =========================================================================

    @Test(expected = IllegalArgumentException.class)
    public void testDefaultWebSocketFrame_nullContentThrows() {
        new DefaultWebSocketFrame(WebSocketOpcode.TEXT, true, false, null, null);
    }

    /** Verifies that a short text frame encodes correctly (FIN + opcode 0x01). */
    @Test
    public void testEncoder_shortTextFrame() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel enc = (VrtChannel) neta.connectSync(new VrtSocketAddress(10), ctx -> {
            ctx.addLastEncoder(new WebSocketFrameEncoder());
        }, VrtSoConfig.asServer());
        VrtChannel sink = (VrtChannel) neta.connectSync(new VrtSocketAddress(11), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(enc, sink, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        sink.subscribe(d -> rcvData.offer(d.getData()));

        enc.sendData(DefaultWebSocketFrame.text("Hi")).get();

        assertEquals(1, rcvData.size());
        ByteBuf wire = (ByteBuf) rcvData.poll();
        byte b0 = wire.readByte();
        byte b1 = wire.readByte();
        assertTrue("FIN bit should be set", (b0 & 0x80) != 0);
        assertEquals("opcode should be TEXT (1)", 0x01, b0 & 0x0F);
        assertFalse("should not be masked", (b1 & 0x80) != 0);
        int payloadLen = b1 & 0x7F;
        byte[] payload = new byte[payloadLen];
        wire.readBytes(payload, 0, payloadLen);
        assertEquals("Hi", new String(payload, StandardCharsets.UTF_8));

        neta.shutdown();
    }

    // =========================================================================
    // WebSocketFrameDecoder – decode raw bytes: unmasked text frame
    // =========================================================================

    /** Verifies that a ping frame encodes as opcode 0x09 with empty payload. */
    @Test
    public void testEncoder_pingFrame() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel enc = (VrtChannel) neta.connectSync(new VrtSocketAddress(20), ctx -> {
            ctx.addLastEncoder(new WebSocketFrameEncoder());
        }, VrtSoConfig.asServer());
        VrtChannel sink = (VrtChannel) neta.connectSync(new VrtSocketAddress(21), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(enc, sink, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        sink.subscribe(d -> rcvData.offer(d.getData()));

        enc.sendData(DefaultWebSocketFrame.ping()).get();

        ByteBuf wire = (ByteBuf) rcvData.poll();
        byte b0 = wire.readByte();
        assertEquals("opcode should be PING (9)", 0x09, b0 & 0x0F);

        neta.shutdown();
    }

    @Test
    public void testDecoder_unmaskedTextFrame() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(30), ctx -> {
            ctx.addLastDecoder(new WebSocketFrameDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(31), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        byte[] payload = "Hello".getBytes(StandardCharsets.UTF_8);
        ByteBuf rawFrame = buildRawFrame(0x01, true, false, null, payload);
        client.sendData(rawFrame).get();

        assertEquals(1, rcvData.size());
        WebSocketFrame frame = (WebSocketFrame) rcvData.poll();
        assertEquals(WebSocketOpcode.TEXT, frame.opcode());
        assertTrue(frame.isFinalFragment());
        assertFalse(frame.isMasked());
        assertEquals("Hello", readContent(frame));

        neta.shutdown();
    }

    @Test
    public void testDecoder_maskedTextFrame() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(32), ctx -> {
            ctx.addLastDecoder(new WebSocketFrameDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(33), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        byte[] maskKey = { 0x37, (byte) 0xFA, 0x21, 0x3D };
        byte[] payload = "Masked".getBytes(StandardCharsets.UTF_8);
        ByteBuf rawFrame = buildRawFrame(0x01, true, true, maskKey, payload);
        client.sendData(rawFrame).get();

        assertEquals(1, rcvData.size());
        WebSocketFrame frame = (WebSocketFrame) rcvData.poll();
        assertEquals(WebSocketOpcode.TEXT, frame.opcode());
        assertTrue(frame.isMasked());
        assertEquals("Masked", readContent(frame));

        neta.shutdown();
    }

    @Test
    public void testDecoder_binaryFrame() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(34), ctx -> {
            ctx.addLastDecoder(new WebSocketFrameDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(35), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        byte[] payload = { 0x01, 0x02, 0x03, 0x04 };
        ByteBuf rawFrame = buildRawFrame(0x02, true, false, null, payload);
        client.sendData(rawFrame).get();

        WebSocketFrame frame = (WebSocketFrame) rcvData.poll();
        assertNotNull(frame);
        assertEquals(WebSocketOpcode.BINARY, frame.opcode());
        assertEquals(4, frame.content().readableBytes());

        neta.shutdown();
    }

    @Test
    public void testDecoder_pingFrame() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(36), ctx -> {
            ctx.addLastDecoder(new WebSocketFrameDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(37), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        ByteBuf rawFrame = buildRawFrame(0x09, true, false, null, new byte[0]);
        client.sendData(rawFrame).get();

        WebSocketFrame frame = (WebSocketFrame) rcvData.poll();
        assertNotNull(frame);
        assertEquals(WebSocketOpcode.PING, frame.opcode());
        assertEquals(0, frame.content().readableBytes());

        neta.shutdown();
    }

    /** Extended payload length (16-bit length field for 126-byte payloads). */
    @Test
    public void testDecoder_extendedPayloadLen126() throws Throwable {
        NetManager neta = new NetManager();
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(38), ctx -> {
            ctx.addLastDecoder(new WebSocketFrameDecoder());
        }, VrtSoConfig.asServer());
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(39), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        Queue<Object> rcvData = new ArrayDeque<>();
        server.subscribe(d -> rcvData.offer(d.getData()));

        byte[] payload = new byte[200];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0xFF);
        }
        ByteBuf rawFrame = buildRawFrame(0x02, true, false, null, payload);
        client.sendData(rawFrame).get();

        WebSocketFrame frame = (WebSocketFrame) rcvData.poll();
        assertNotNull(frame);
        assertEquals(WebSocketOpcode.BINARY, frame.opcode());
        assertEquals(200, frame.content().readableBytes());

        neta.shutdown();
    }

    // =========================================================================
    // Round-trip: Encoder → Decoder
    // =========================================================================

    @Test
    public void testRoundTrip_textFrame() throws Throwable {
        NetManager neta = new NetManager();
        // encoder channel encodes frames → raw bytes
        VrtChannel encoder = (VrtChannel) neta.connectSync(new VrtSocketAddress(40), ctx -> {
            ctx.addLastEncoder(new WebSocketFrameEncoder());
        }, VrtSoConfig.asServer());
        // decoder channel receives raw bytes and produces frames
        VrtChannel decoder = (VrtChannel) neta.connectSync(new VrtSocketAddress(41), ctx -> {
            ctx.addLastDecoder(new WebSocketFrameDecoder());
        }, VrtSoConfig.asServer());
        // wire: encoder output → decoder input
        VrtChannel wire1 = (VrtChannel) neta.connectSync(new VrtSocketAddress(42), ctx -> {
        }, VrtSoConfig.asClient());
        VrtChannel wire2 = (VrtChannel) neta.connectSync(new VrtSocketAddress(43), ctx -> {
        }, VrtSoConfig.asClient());
        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(encoder, wire1, VrtTransfer.duplicate());
        Queue<Object> rawQueue = new ArrayDeque<>();
        wire1.subscribe(d -> rawQueue.offer(d.getData()));

        encoder.sendData(DefaultWebSocketFrame.text("RoundTrip")).get();

        // grab the encoded bytes
        assertEquals(1, rawQueue.size());
        ByteBuf encoded = (ByteBuf) rawQueue.poll();

        // now pass them through decoder
        transfer.linkTo(wire2, decoder, VrtTransfer.duplicate());
        Queue<Object> decodedQueue = new ArrayDeque<>();
        decoder.subscribe(d -> decodedQueue.offer(d.getData()));
        wire2.sendData(encoded).get();

        assertEquals(1, decodedQueue.size());
        WebSocketFrame decoded = (WebSocketFrame) decodedQueue.poll();
        assertEquals(WebSocketOpcode.TEXT, decoded.opcode());
        assertEquals("RoundTrip", readContent(decoded));

        neta.shutdown();
    }

    // =========================================================================
    // WebSocketServerHandshaker
    // =========================================================================

    @Test
    public void testComputeAcceptKey_rfc6455Example() {
        // Example from RFC 6455 §1.3:
        //   key    = "dGhlIHNhbXBsZSBub25jZQ=="
        //   expect = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        String key = "dGhlIHNhbXBsZSBub25jZQ==";
        String expected = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        assertEquals(expected, WebSocketServerHandshaker.computeAcceptKey(key));
    }

    @Test
    public void testHandshakeResponse_validRequest() {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/chat");
        req.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
        req.headers().set(HttpHeaderNames.CONNECTION, "upgrade");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");

        FullHttpResponse resp = WebSocketServerHandshaker.handshakeResponse(req);

        assertEquals(101, resp.status().code());
        assertEquals(HttpHeaderValues.WEBSOCKET.toLowerCase(), resp.headers().get(HttpHeaderNames.UPGRADE).toLowerCase());
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", resp.headers().get(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testHandshakeResponse_missingKeyThrows() {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/chat");
        req.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
        req.headers().set(HttpHeaderNames.CONNECTION, "upgrade");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        // no Sec-WebSocket-Key
        WebSocketServerHandshaker.handshakeResponse(req);
    }

    @Test
    public void testIsWebSocketUpgrade_valid() {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ws");
        req.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        req.headers().set(HttpHeaderNames.CONNECTION, "upgrade");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "abc==");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        assertTrue(WebSocketServerHandshaker.isWebSocketUpgrade(req));
    }

    @Test
    public void testIsWebSocketUpgrade_missingVersion() {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ws");
        req.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        req.headers().set(HttpHeaderNames.CONNECTION, "upgrade");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "abc==");
        // no Sec-WebSocket-Version
        assertFalse(WebSocketServerHandshaker.isWebSocketUpgrade(req));
    }

    @Test
    public void testIsWebSocketUpgrade_nullRequest() {
        assertFalse(WebSocketServerHandshaker.isWebSocketUpgrade(null));
    }

    @Test
    public void testHandshakeResponse_subProtocol() {
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ws");
        req.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        req.headers().set(HttpHeaderNames.CONNECTION, "upgrade");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_KEY, "dGhlIHNhbXBsZSBub25jZQ==");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        req.headers().set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "chat");

        FullHttpResponse resp = WebSocketServerHandshaker.handshakeResponse(req);
        assertEquals("chat", resp.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL));
    }
}
