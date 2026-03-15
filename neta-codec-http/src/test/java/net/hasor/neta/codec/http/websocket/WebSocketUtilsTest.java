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
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.cookie.DefaultCookie;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketUtilsTest extends AbstractWebSocketTest {
    private static String readContent(WebSocketFrame frame) {
        ByteBuf content = frame.content();
        return content.readString(content.readableBytes(), StandardCharsets.UTF_8);
    }

    @Test
    public void testVersionCodes() {
        assertEquals(0, WebSocketVersion.V0.code());
        assertEquals(7, WebSocketVersion.V7.code());
        assertEquals(8, WebSocketVersion.V8.code());
        assertEquals(13, WebSocketVersion.V13.code());
    }

    @Test
    public void testVersionOf() {
        assertSame(WebSocketVersion.V0, WebSocketVersion.of("0"));
        assertSame(WebSocketVersion.V7, WebSocketVersion.of("7"));
        assertSame(WebSocketVersion.V8, WebSocketVersion.of("8"));
        assertSame(WebSocketVersion.V13, WebSocketVersion.of("13"));
        assertSame(WebSocketVersion.V0, WebSocketVersion.of(null));
        assertSame(WebSocketVersion.V0, WebSocketVersion.of(""));
        assertNull(WebSocketVersion.of("99"));
    }

    @Test
    public void testVersionIsRfc6455Framing() {
        assertFalse(WebSocketVersion.V0.isRfc6455Framing());
        assertTrue(WebSocketVersion.V7.isRfc6455Framing());
        assertTrue(WebSocketVersion.V8.isRfc6455Framing());
        assertTrue(WebSocketVersion.V13.isRfc6455Framing());
    }

    @Test
    public void testOpcodeValues() {
        assertEquals(0x0, WebSocketOpcode.CONTINUATION.code());
        assertEquals(0x1, WebSocketOpcode.TEXT.code());
        assertEquals(0x2, WebSocketOpcode.BINARY.code());
        assertEquals(0x8, WebSocketOpcode.CLOSE.code());
        assertEquals(0x9, WebSocketOpcode.PING.code());
        assertEquals(0xA, WebSocketOpcode.PONG.code());
    }

    @Test
    public void testOpcodeOfKnown() {
        assertSame(WebSocketOpcode.TEXT, WebSocketOpcode.of(0x1));
        assertSame(WebSocketOpcode.BINARY, WebSocketOpcode.of(0x2));
        assertSame(WebSocketOpcode.CLOSE, WebSocketOpcode.of(0x8));
    }

    @Test
    public void testOpcodeOfUnknown() {
        assertNull(WebSocketOpcode.of(0x3));
    }

    @Test
    public void testTextFrame() {
        WebSocketFrame frame = WebSocketUtils.textFrame("Hello");
        assertEquals(WebSocketOpcode.TEXT, frame.opcode());
        assertTrue(frame.isFinalFragment());
        assertFalse(frame.isMasked());
        assertNull(frame.maskingKey());
        assertEquals("Hello", readContent(frame));
    }

    @Test
    public void testBinaryFrame() {
        byte[] data = { 1, 2, 3 };
        WebSocketFrame frame = WebSocketUtils.binaryFrame(data);
        assertEquals(WebSocketOpcode.BINARY, frame.opcode());
        assertTrue(frame.isFinalFragment());
        assertEquals(3, frame.content().readableBytes());
    }

    @Test
    public void testPingFrame() {
        WebSocketFrame frame = WebSocketUtils.pingFrame();
        assertEquals(WebSocketOpcode.PING, frame.opcode());
        assertEquals(0, frame.content().readableBytes());
        frame.release();
    }

    @Test
    public void testPingEvent() {
        PingWebSocketEvent event = WebSocketUtils.pingEvent();
        assertEquals(0, event.content().readableBytes());
        event.release();
    }

    @Test
    public void testPongEvent() {
        PongWebSocketEvent event = WebSocketUtils.pongEvent();
        assertEquals(0, event.content().readableBytes());
        event.release();
    }

    @Test
    public void testPongFrame() {
        WebSocketFrame frame = WebSocketUtils.pongFrame();
        assertEquals(WebSocketOpcode.PONG, frame.opcode());
        assertEquals(0, frame.content().readableBytes());
        frame.release();
    }

    @Test
    public void testCloseFrame() {
        WebSocketFrame frame = WebSocketUtils.closeFrame(1000, "Normal");
        assertEquals(WebSocketOpcode.CLOSE, frame.opcode());
        ByteBuf content = frame.content();
        assertTrue(content.readableBytes() >= 2);
        int status = ((content.getByte(content.readerIndex()) & 0xFF) << 8) | (content.getByte(content.readerIndex() + 1) & 0xFF);
        assertEquals(1000, status);
    }

    @Test
    public void testHandshakeRequestDefaults() {
        HttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");

        assertTrue(request instanceof FullHttpRequest);
        FullHttpRequest fullRequest = (FullHttpRequest) request;
        assertEquals(HttpVersion.HTTP_1_1, fullRequest.protocolVersion());
        assertEquals(HttpMethod.GET, fullRequest.method());
        assertEquals("/chat", fullRequest.uri());
        assertEquals("localhost", fullRequest.getString(HttpHeaderNames.HOST));
        assertEquals("websocket", fullRequest.getString(HttpHeaderNames.UPGRADE));
        assertEquals("Upgrade", fullRequest.getString(HttpHeaderNames.CONNECTION));
        assertEquals("http://localhost", fullRequest.getString(HttpHeaderNames.ORIGIN));
        String handshakeKey = fullRequest.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY);
        assertNotNull(handshakeKey);
        assertEquals(16, Base64.getDecoder().decode(handshakeKey).length);
        assertEquals("13", fullRequest.getString(HttpHeaderNames.SEC_WEBSOCKET_VERSION));
        assertEquals("0", fullRequest.getString(HttpHeaderNames.CONTENT_LENGTH));
        assertEquals(0, fullRequest.content().readableBytes());
    }

    @Test
    public void testLegacyHandshakeRequestUsesRandomChallengeMaterial() {
        FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V0, "/legacy");

        String key1 = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY1);
        String key2 = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY2);
        assertNotNull(key1);
        assertNotNull(key2);
        assertTrue(key1.indexOf(' ') >= 0);
        assertTrue(key2.indexOf(' ') >= 0);
        assertEquals(8, request.content().readableBytes());
        assertEquals("8", request.getString(HttpHeaderNames.CONTENT_LENGTH));
    }

    @Test
    public void testHandshakeRequestWithHeadersAndCookies() {
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.setHeader(HttpHeaderNames.HOST, "example.com");
        headers.setHeader(HttpHeaderNames.ORIGIN, "https://example.com");
        headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "graphql-transport-ws");

        HttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/graphql", headers, new DefaultCookie("sid", "abc"), new DefaultCookie("lang", "zh-CN"));

        FullHttpRequest fullRequest = (FullHttpRequest) request;
        assertEquals("example.com", fullRequest.getString(HttpHeaderNames.HOST));
        assertEquals("https://example.com", fullRequest.getString(HttpHeaderNames.ORIGIN));
        assertEquals("graphql-transport-ws", fullRequest.getString(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL));
        assertEquals("sid=abc; lang=zh-CN", fullRequest.getString(HttpHeaderNames.COOKIE));
    }

    @Test
    public void testHandshakeRequestMergesCookieHeaderAndCookies() {
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.setHeader(HttpHeaderNames.COOKIE, "token=old");

        FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/ws", headers, new DefaultCookie("sid", "abc"));
        assertEquals("token=old; sid=abc", request.getString(HttpHeaderNames.COOKIE));
    }

    @Test
    public void testHandshakeRequestMergesAllCookieHeaderValuesAndCookies() {
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.addHeader(HttpHeaderNames.COOKIE, "token=old");
        headers.addHeader(HttpHeaderNames.COOKIE, "lang=zh-CN");

        FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/ws", headers, new DefaultCookie("sid", "abc"), new DefaultCookie("theme", "light"));
        assertEquals("token=old; lang=zh-CN; sid=abc; theme=light", request.getString(HttpHeaderNames.COOKIE));
    }

    @Test
    public void testHandshakeRequestReturnsFullRequestBackedByHttpRequest() {
        HttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/ws");
        assertNotNull(request);
        assertTrue(request instanceof FullHttpRequest);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTextFrameNullContent() {
        WebSocketUtils.textFrame(true, false, null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBinaryFrameNullContent() {
        WebSocketUtils.binaryFrame(true, false, null, null);
    }

    @Test
    public void testFrameTransfersContentOwnership() {
        ByteBuf payload = ByteBufAllocator.DEFAULT.buffer(16, Integer.MAX_VALUE);
        payload.writeString("owned", StandardCharsets.UTF_8);
        payload.markWriter();

        WebSocketFrame frame = WebSocketUtils.textFrame(true, false, null, payload);
        assertSame(payload, frame.content());
        assertEquals("owned", readContent(frame));

        frame.release();
        assertTrue(payload.isFree());
        assertNull(frame.content());
    }

    @Test
    public void testFrameReleaseIsIdempotent() {
        WebSocketFrame frame = WebSocketUtils.textFrame("safe-release");
        frame.release();
        frame.release();
        assertNull(frame.content());
    }

    @Test
    public void testContextFromHandshakeBasic() {
        WebSocketContextImpl ctx = WebSocketContextImpl.fromHandshake("/chat", "graphql-transport-ws", null);

        assertTrue(ctx.isReady());
        assertTrue(ctx.isServer());
        assertFalse(ctx.isClient());
        assertEquals("/chat", ctx.requestPath());
        assertEquals("graphql-transport-ws", ctx.subProtocol());
        assertEquals(13, ctx.version());
        assertNull(ctx.extensions());
    }

    @Test
    public void testContextFromHandshakeWithExtensions() {
        WebSocketContextImpl ctx = WebSocketContextImpl.fromHandshake("/ws", null, "permessage-deflate, x-webkit-deflate-frame");
        assertNull(ctx.subProtocol());
        assertEquals("permessage-deflate, x-webkit-deflate-frame", ctx.extensions());
    }

    @Test
    public void testContextFromHandshakeEmptyExtensions() {
        WebSocketContextImpl ctx = WebSocketContextImpl.fromHandshake("/ws", null, "");
        assertNull(ctx.extensions());
    }

    @Test
    public void testContextFromHandshakeNullExtensions() {
        WebSocketContextImpl ctx = WebSocketContextImpl.fromHandshake("/ws", null, null);
        assertNull(ctx.extensions());
    }

    @Test
    public void testContextFromHandshakeExplicitVersion() {
        WebSocketContextImpl ctx = WebSocketContextImpl.fromHandshake(WebSocketVersion.V8, "/chat", "mqtt", null);
        assertEquals(8, ctx.version());
        assertEquals("/chat", ctx.requestPath());
        assertEquals("mqtt", ctx.subProtocol());
        assertTrue(ctx.isServer());
        assertTrue(ctx.isReady());
    }

    @Test
    public void testContextFromHandshakeLegacyVersion() {
        WebSocketContextImpl ctx = WebSocketContextImpl.fromHandshake(WebSocketVersion.V0, "/legacy", null, "permessage-deflate");
        assertEquals(0, ctx.version());
        assertEquals("/legacy", ctx.requestPath());
        assertEquals("permessage-deflate", ctx.extensions());
    }

    @Test
    public void testContextConstructorClientSide() {
        WebSocketContextImpl ctx = new WebSocketContextImpl(false, "mqtt", 13, "/mqtt", Collections.emptyList());
        assertFalse(ctx.isServer());
        assertTrue(ctx.isClient());
        assertEquals("mqtt", ctx.subProtocol());
        assertEquals("/mqtt", ctx.requestPath());
    }

    @Test
    public void testContextConstructorWithExtensionList() {
        WebSocketContextImpl ctx = new WebSocketContextImpl(true, null, 13, "/ws", Arrays.asList("permessage-deflate", "x-ext"));
        assertEquals("permessage-deflate, x-ext", ctx.extensions());
    }

    @Test
    public void testContextConstructorNullExtensionList() {
        WebSocketContextImpl ctx = new WebSocketContextImpl(true, null, 13, "/ws", null);
        assertNull(ctx.extensions());
    }

    @Test
    public void testContextAlwaysReady() {
        WebSocketContextImpl ctx = new WebSocketContextImpl(true, null, 13, "/", Collections.emptyList());
        assertTrue(ctx.isReady());
    }

    @Test
    public void testContextVersionRfc6455() {
        WebSocketContextImpl ctx = WebSocketContextImpl.fromHandshake("/", null, null);
        assertEquals(13, ctx.version());
    }
}