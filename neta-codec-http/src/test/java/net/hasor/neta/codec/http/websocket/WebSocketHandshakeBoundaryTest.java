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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketHandshakeBoundaryTest extends AbstractWebSocketTest {
    private static final String RFC6455_KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    private static WebSocketContext webSocketContext(net.hasor.neta.channel.SoChannel<?> channel) {
        return WebSocketRegistry.resolve(channel);
    }

    private static DefaultFullHttpResponse newUpgradeResponse(String key) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.SWITCHING_PROTOCOLS);
        response.setHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
        response.setHeader(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT, computeAcceptKey(key));
        return response;
    }

    private static String computeAcceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void testServerRejectsRfc6455HandshakeWithRequestBodyWithoutPublishingEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
            }, VrtSoConfig.asServer());

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/chat", ascii("abc"));
            request.setHeader(HttpHeaderNames.HOST, "localhost");
            request.setHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
            request.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
            request.setHeader(HttpHeaderNames.ORIGIN, "http://localhost");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_KEY, RFC6455_KEY);
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
            request.setHeader(HttpHeaderNames.CONTENT_LENGTH, "3");

            List<HttpObject> inbound = receiveAndIntBound(pipe, request);
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);

            assertTrue(inbound.isEmpty());
            assertNull(WebSocketRegistry.resolve(pipe.channel()));
            assertEquals(3, outbound.size());
            assertEquals(400, response.status().code());
        });
    }

    @Test
    public void testServerRejectsLegacyV0HandshakeWithInvalidChallengeLengthWithoutPublishingEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V0)), VrtSoConfig.asServer());

            FullHttpRequest base = WebSocketUtils.createHandshake(WebSocketVersion.V0, "/legacy");
            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/legacy", ascii("short"));
            request.setHeader(HttpHeaderNames.HOST, base.getString(HttpHeaderNames.HOST));
            request.setHeader(HttpHeaderNames.UPGRADE, base.getString(HttpHeaderNames.UPGRADE));
            request.setHeader(HttpHeaderNames.CONNECTION, base.getString(HttpHeaderNames.CONNECTION));
            request.setHeader(HttpHeaderNames.ORIGIN, base.getString(HttpHeaderNames.ORIGIN));
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_KEY1, base.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY1));
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_KEY2, base.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY2));
            request.setHeader(HttpHeaderNames.CONTENT_LENGTH, "5");

            List<HttpObject> inbound = receiveAndIntBound(pipe, request);
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);

            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));
            assertEquals(3, outbound.size());
            assertEquals(400, response.status().code());
        });
    }

    @Test
    public void testServerRejectsIncompatibleVersionWithUpgradeRequiredWithoutPublishingEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V0)), VrtSoConfig.asServer());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            List<HttpObject> inbound = receiveAndIntBound(pipe, request);
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);
            HttpHeaders headers = (HttpHeaders) outbound.get(1);

            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));
            assertEquals(3, outbound.size());
            assertEquals(426, response.status().code());
            assertEquals("0", headers.getString(HttpHeaderNames.SEC_WEBSOCKET_VERSION));
        });
    }

    @Test
    public void testServerCreatesReadyContextForStagedRfc6455Request() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13)), VrtSoConfig.asServer());

            DefaultHttpRequest requestLine = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/chat");
            requestLine.streamId(23);
            DefaultLastHttpHeaders headers = new DefaultLastHttpHeaders();
            headers.setHeader(HttpHeaderNames.HOST, "localhost");
            headers.setHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
            headers.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
            headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_KEY, RFC6455_KEY);
            headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
            headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "chat");
            headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");

            List<HttpObject> inbound = receiveAndIntBound(pipe, requestLine, headers, DefaultLastHttpContent.EMPTY);
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);
            WebSocketContext context = webSocketContext(pipe.channel());

            assertTrue(inbound.isEmpty());
            assertNotNull(context);
            assertTrue(context.isReady());
            assertEquals(WebSocketVersion.V13.code(), context.version());
            assertEquals("/chat", context.requestPath());
            assertNull(context.subProtocol());
            assertNull(context.extensions());
            assertEquals(3, outbound.size());
            assertEquals(101, response.status().code());
        });
    }

    @Test
    public void testClientCreatesReadyContextForStaged101Response() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13)), VrtSoConfig.asClient());

            DefaultHttpRequest requestLine = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/chat");
            DefaultLastHttpHeaders requestHeaders = new DefaultLastHttpHeaders();
            requestHeaders.setHeader(HttpHeaderNames.HOST, "localhost");
            requestHeaders.setHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
            requestHeaders.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
            requestHeaders.setHeader(HttpHeaderNames.SEC_WEBSOCKET_KEY, RFC6455_KEY);
            requestHeaders.setHeader(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
            requestHeaders.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "chat");
            sendAndOutBound(pipe, requestLine, requestHeaders, DefaultLastHttpContent.EMPTY);

            DefaultHttpResponse responseLine = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.SWITCHING_PROTOCOLS);
            DefaultLastHttpHeaders responseHeaders = new DefaultLastHttpHeaders();
            responseHeaders.setHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
            responseHeaders.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
            responseHeaders.setHeader(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT, computeAcceptKey(RFC6455_KEY));
            responseHeaders.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "chat");

            List<HttpObject> inbound = receiveAndIntBound(pipe, responseLine, responseHeaders, DefaultLastHttpContent.EMPTY);
            WebSocketContext context = webSocketContext(pipe.channel());

            assertTrue(inbound.isEmpty());
            assertNotNull(context);
            assertTrue(context.isReady());
            assertEquals(WebSocketVersion.V13.code(), context.version());
            assertEquals("/chat", context.requestPath());
            assertEquals("chat", context.subProtocol());
        });
    }

    @Test
    public void testClientRejects101WithoutConnectionUpgradeWithoutPublishingEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13)), VrtSoConfig.asClient());

            sendAndOutBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat"));
            DefaultFullHttpResponse response = newUpgradeResponse(RFC6455_KEY);
            response.removeHeader(HttpHeaderNames.CONNECTION);
            List<HttpObject> inbound = receiveAndIntBound(pipe, response);

            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));
            assertTrue(pipe.channel().isClose());
        });
    }

    @Test
    public void testClientRejects101WithoutUpgradeHeaderWithoutPublishingEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13)), VrtSoConfig.asClient());

            sendAndOutBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat"));
            DefaultFullHttpResponse response = newUpgradeResponse(RFC6455_KEY);
            response.removeHeader(HttpHeaderNames.UPGRADE);
            List<HttpObject> inbound = receiveAndIntBound(pipe, response);

            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));
            assertTrue(pipe.channel().isClose());
        });
    }

    @Test
    public void testClientRejectsUnexpectedSelectedSubProtocolWithoutPublishingEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13)), VrtSoConfig.asClient());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "chat, graphql-ws");
            sendAndOutBound(pipe, request);

            DefaultFullHttpResponse response = newUpgradeResponse(request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY));
            response.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "mqtt");
            List<HttpObject> inbound = receiveAndIntBound(pipe, response);

            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));
            assertTrue(pipe.channel().isClose());
        });
    }

    @Test
    public void testClientRejectsSelectedSubProtocolWhenNoneRequestedWithoutPublishingEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13)), VrtSoConfig.asClient());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            sendAndOutBound(pipe, request);

            DefaultFullHttpResponse response = newUpgradeResponse(request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY));
            response.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "chat");
            List<HttpObject> inbound = receiveAndIntBound(pipe, response);

            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));
            assertTrue(pipe.channel().isClose());
        });
    }
}