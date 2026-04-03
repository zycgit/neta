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
import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.DefaultFullHttpResponse;
import net.hasor.neta.codec.http.DefaultHttpHeaders;
import net.hasor.neta.codec.http.FullHttpRequest;
import net.hasor.neta.codec.http.HttpHeaderNames;
import net.hasor.neta.codec.http.HttpHeaderValues;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.HttpResponse;
import net.hasor.neta.codec.http.HttpStatus;
import net.hasor.neta.codec.http.HttpVersion;
import net.hasor.neta.codec.http.websocket.extension.PerMessageDeflateSupport;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketHandshakeExtensionTest extends AbstractWebSocketTest {
    private static WebSocketContext webSocketContext(SoChannel<?> channel) {
        return channel.findProtoContext(WebSocketContext.class);
    }

    private static DefaultFullHttpResponse newUpgradeResponse(String key) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.SWITCHING_PROTOCOLS);
        response.streamId(11);
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
    public void testAuthorizerSelectingUnsupportedExtensionBecomesBadRequest() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (request, callback) -> {
                    DefaultHttpHeaders headers = new DefaultHttpHeaders();
                    headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");
                    callback.accept(headers);
                };
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13, authorizer));
            }, VrtSoConfig.asServer());

            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat"));
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);

            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));
            assertEquals(3, outbound.size());
            assertEquals(400, response.status().code());
        });
    }

    @Test
    public void testSettingsNegotiatorCanAcceptServerSelectedExtension() throws Throwable {
        autoCloseNeta(neta -> {
            WebSocketSettings settings = WebSocketSettings.builder(WebSocketVersion.V13).perMessageDeflate().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");
                callback.accept(headers);
            }).build();

            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(settings));
            }, VrtSoConfig.asServer());

            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat"));
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);
            HttpHeaders headers = (HttpHeaders) outbound.get(1);
            WebSocketContext webSocketContext = webSocketContext(pipe.channel());

            assertTrue(inbound.isEmpty());
            assertEquals(101, response.status().code());
            assertEquals("permessage-deflate", headers.getString(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS));
            assertNotNull(webSocketContext);
            assertEquals("permessage-deflate", webSocketContext.extensions());
        });
    }

    @Test
    public void testPerMessageDeflateSupportRejectsMultipleRequestedExtensions() throws Throwable {
        autoCloseNeta(neta -> {
            WebSocketSettings settings = WebSocketSettings.builder(WebSocketVersion.V13).perMessageDeflate().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");
                callback.accept(headers);
            }).build();

            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(settings)), VrtSoConfig.asServer());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate, x-test-ext");
            List<HttpObject> inbound = receiveAndIntBound(pipe, request);
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);

            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));
            assertEquals(400, response.status().code());
        });
    }

    @Test
    public void testPerMessageDeflateSupportRejectsV0HandshakeSelection() throws Throwable {
        autoCloseNeta(neta -> {
            WebSocketSettings settings = WebSocketSettings.builder(WebSocketVersion.V0).perMessageDeflate().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");
                callback.accept(headers);
            }).build();

            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(settings)), VrtSoConfig.asServer());

            DefaultHttpHeaders headers = new DefaultHttpHeaders();
            headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");
            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V0, "/legacy", headers));
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);

            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));
            assertEquals(400, response.status().code());
        });
    }

    @Test
    public void testClientSettingsNegotiatorCanAcceptNegotiatedExtension() throws Throwable {
        autoCloseNeta(neta -> {
            WebSocketSettings settings = WebSocketSettings.builder(WebSocketVersion.V13).perMessageDeflate().build();

            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(settings)), VrtSoConfig.asClient());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");
            sendAndOutBound(pipe, request);

            DefaultFullHttpResponse response = newUpgradeResponse(request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY));
            response.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");
            List<HttpObject> inbound = receiveAndIntBound(pipe, response);
            WebSocketContext context = webSocketContext(pipe.channel());

            assertTrue(inbound.isEmpty());
            assertNotNull(context);
            assertEquals("permessage-deflate", context.extensions());
        });
    }

    @Test
    public void testClientPerMessageDeflateSupportRejectsMultipleNegotiatedExtensions() throws Throwable {
        autoCloseNeta(neta -> {
            WebSocketSettings settings = WebSocketSettings.builder(WebSocketVersion.V13).perMessageDeflate().build();

            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(settings)), VrtSoConfig.asClient());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, PerMessageDeflateSupport.EXTENSION_NAME);
            sendAndOutBound(pipe, request);

            DefaultFullHttpResponse response = newUpgradeResponse(request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY));
            response.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate, x-test-ext");
            List<HttpObject> inbound = receiveAndIntBound(pipe, response);

            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));
            assertTrue(pipe.channel().isClose());
        });
    }

    @Test
    public void testClientRejectsUnsupportedNegotiatedExtensionWithoutPublishingEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13)), VrtSoConfig.asClient());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            sendAndOutBound(pipe, request);
            DefaultFullHttpResponse response = newUpgradeResponse(request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY));
            response.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");
            List<HttpObject> inbound = receiveAndIntBound(pipe, response);

            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));
            assertTrue(pipe.channel().isClose());
        });
    }
}