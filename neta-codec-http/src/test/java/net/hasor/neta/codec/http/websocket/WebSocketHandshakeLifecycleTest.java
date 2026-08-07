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

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtTransfer;
import net.hasor.neta.codec.http.*;

public class WebSocketHandshakeLifecycleTest extends AbstractWebSocketTest {
    private static final String WS_URI                  = "ws://example.com/chat";
    private static final String LEGACY_URI              = "ws://example.com/legacy";
    private static final String OUTBOUND_SIZE_FLASH_KEY = "test.outbound.size";

    private static WebSocketHandshakeEvent handshakeEvent(Iterable<SoEvent> events) {
        if (events == null) {
            return null;
        }
        for (SoEvent event : events) {
            if (event != null && event.getData() instanceof WebSocketHandshakeEvent) {
                return (WebSocketHandshakeEvent) event.getData();
            }
        }
        return null;
    }

    private static WebSocketContext webSocketContext(SoChannel<?> channel) {
        return WebSocketRegistry.resolve(channel);
    }

    private static ProtoHandler<HttpObject, HttpObject> errorRecorder(List<Throwable> errors, AtomicInteger outboundCountAtError) {
        return new ProtoHandler<HttpObject, HttpObject>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) {
                while (src.hasMore()) {
                    dst.offerMessage(src.takeMessage());
                }
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                errors.add(e);
                Object outboundSize = context.flash(OUTBOUND_SIZE_FLASH_KEY);
                outboundCountAtError.set(outboundSize instanceof Integer ? (Integer) outboundSize : -1);
                return ProtoStatus.Next;
            }
        };
    }

    private static ProtoHandler<HttpObject, HttpObject> outboundCountRecorder(VirtualPipe[] pipeRef) {
        return new ProtoHandler<HttpObject, HttpObject>() {
            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) {
                while (src.hasMore()) {
                    dst.offerMessage(src.takeMessage());
                }
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                VirtualPipe pipe = pipeRef[0];
                context.flash(OUTBOUND_SIZE_FLASH_KEY, pipe != null ? pipe.channelOutbound().size() : -1);
                return ProtoStatus.Next;
            }
        };
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
    public void testServerRfc6455HandshakeCreatesReadyContextAndAuthorizerSeesRequest() throws Throwable {
        autoCloseNeta(neta -> {
            AtomicLong requestStreamId = new AtomicLong(-1);
            AtomicReference<WebSocketVersion> requestVersion = new AtomicReference<>();
            AtomicReference<String> requestPath = new AtomicReference<>();
            AtomicReference<String> requestProtocols = new AtomicReference<>();
            AtomicReference<String> requestExtensions = new AtomicReference<>();
            AtomicReference<String> requestKey = new AtomicReference<>();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (request, callback) -> {
                    requestStreamId.set(request.streamId());
                    requestVersion.set(request.version());
                    requestPath.set(request.requestPath());
                    requestProtocols.set(request.requestedProtocols());
                    requestExtensions.set(request.requestedExtensions());
                    requestKey.set(request.header(HttpHeaderNames.SEC_WEBSOCKET_KEY));
                    callback.accept();
                };
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13, authorizer));
            }, VrtSoConfig.asServer());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI);
            request.streamId(7);
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "graphql-ws");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");
            String wsKey = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY);

            List<HttpObject> inbound = receiveAndIntBound(pipe, request);
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);
            HttpHeaders headers = (HttpHeaders) outbound.get(1);
            HttpContent content = (HttpContent) outbound.get(2);
            WebSocketContext webSocketContext = webSocketContext(pipe.channel());
            WebSocketHandshakeEvent handshakeEvent = handshakeEvent(pipe.channelEvents());

            assertTrue(inbound.isEmpty());
            assertEquals(7L, requestStreamId.get());
            assertEquals(WebSocketVersion.V13, requestVersion.get());
            assertEquals("/chat", requestPath.get());
            assertEquals("graphql-ws", requestProtocols.get());
            assertEquals("permessage-deflate", requestExtensions.get());
            assertEquals(wsKey, requestKey.get());
            assertNotNull(webSocketContext);
            assertTrue(webSocketContext.isReady());
            assertNotNull(handshakeEvent);
            assertTrue(handshakeEvent.isServer());
            assertEquals(WebSocketVersion.V13.code(), handshakeEvent.version());
            assertEquals("/chat", handshakeEvent.requestPath());
            assertEquals(WebSocketVersion.V13.code(), webSocketContext.version());
            assertEquals("/chat", webSocketContext.requestPath());
            assertEquals(3, outbound.size());
            assertEquals(101, response.status().code());
            assertEquals(computeAcceptKey(wsKey), headers.getString(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT));
            assertEquals(0, content.content().readableBytes());
        });
    }

    @Test
    public void testServerLegacyV0HandshakeCreatesReadyContext() throws Throwable {
        autoCloseNeta(neta -> {
            AtomicReference<WebSocketHandshakeRequest> requestRef = new AtomicReference<>();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (request, callback) -> {
                    requestRef.set(request);
                    callback.accept();
                };
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V0, authorizer));
            }, VrtSoConfig.asServer());

            DefaultHttpHeaders headers = new DefaultHttpHeaders();
            headers.setHeader(HttpHeaderNames.ORIGIN, "http://example.com");
            headers.setHeader(HttpHeaderNames.HOST, "example.com");
            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V0, LEGACY_URI, headers));
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);
            HttpHeaders responseHeaders = (HttpHeaders) outbound.get(1);
            HttpContent content = (HttpContent) outbound.get(2);
            WebSocketContext webSocketContext = webSocketContext(pipe.channel());

            assertTrue(inbound.isEmpty());
            assertNotNull(requestRef.get());
            assertEquals(WebSocketVersion.V0, requestRef.get().version());
            assertEquals("/legacy", requestRef.get().requestPath());
            assertNotNull(webSocketContext);
            assertTrue(webSocketContext.isReady());
            assertEquals(WebSocketVersion.V0.code(), webSocketContext.version());
            assertEquals("/legacy", webSocketContext.requestPath());
            assertEquals(3, outbound.size());
            assertEquals(101, response.status().code());
            assertEquals("WebSocket", responseHeaders.getString(HttpHeaderNames.UPGRADE));
            assertEquals("http://example.com", responseHeaders.getString(HttpHeaderNames.SEC_WEBSOCKET_ORIGIN));
            assertEquals("ws://example.com/legacy", responseHeaders.getString(HttpHeaderNames.SEC_WEBSOCKET_LOCATION));
            assertEquals(16, content.content().readableBytes());
        });
    }

    @Test
    public void testServerRejectsMalformedHandshakeWithoutReadyContext() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13)), VrtSoConfig.asServer());

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/chat");
            request.setHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
            request.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");

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
    public void testAuthorizerAcceptsAndAppendsResponseHeaders() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (request, callback) -> {
                    assertEquals("/chat", request.requestPath());
                    DefaultHttpHeaders headers = new DefaultHttpHeaders();
                    headers.setHeader("X-Accept", "ok");
                    headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "chat");
                    callback.accept(headers);
                };
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13, authorizer));
            }, VrtSoConfig.asServer());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI);
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "chat");
            List<HttpObject> inbound = receiveAndIntBound(pipe, request);
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);
            HttpHeaders headers = (HttpHeaders) outbound.get(1);
            WebSocketContext webSocketContext = webSocketContext(pipe.channel());

            assertTrue(inbound.isEmpty());
            assertNotNull(webSocketContext);
            assertTrue(webSocketContext.isReady());
            assertEquals("chat", webSocketContext.subProtocol());
            assertNull(webSocketContext.extensions());
            assertEquals(101, response.status().code());
            assertEquals("ok", headers.getString("X-Accept"));
            assertEquals("chat", headers.getString(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL));
            assertNull(headers.getString(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS));
        });
    }

    @Test
    public void testAuthorizerSelectingUnsupportedSubProtocolBecomesBadRequest() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (request, callback) -> {
                    DefaultHttpHeaders headers = new DefaultHttpHeaders();
                    headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "mqtt");
                    callback.accept(headers);
                };
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13, authorizer));
            }, VrtSoConfig.asServer());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI);
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "chat, graphql-ws");
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
    public void testAuthorizerRejectsWithDefaultMethodNotAllowed() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (request, callback) -> callback.reject();
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13, authorizer));
            }, VrtSoConfig.asServer());

            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI));
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);
            HttpHeaders headers = (HttpHeaders) outbound.get(1);

            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));
            assertEquals(405, response.status().code());
            assertEquals("0", headers.getString(HttpHeaderNames.CONTENT_LENGTH));
        });
    }

    @Test
    public void testAuthorizerExceptionTurnsInto500AndClosesChannel() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (request, callback) -> {
                    throw new IllegalStateException("boom");
                };
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13, authorizer));
            }, VrtSoConfig.asServer());

            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI));
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);

            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));
            assertEquals(500, response.status().code());
            assertTrue(pipe.channel().isClose());
        });
    }

    @Test
    public void testAuthorizerExceptionTurnsInto500AndStillReachesServerPipelineErrorHandlers() throws Throwable {
        autoCloseNeta(neta -> {
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            AtomicInteger outboundCountAtError = new AtomicInteger(-1);
            VirtualPipe[] pipeRef = new VirtualPipe[1];
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (request, callback) -> {
                    throw new IllegalStateException("boom");
                };
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13, authorizer));
                ctx.addLastDecoder("order-probe", outboundCountRecorder(pipeRef));
                ctx.addLastDecoder("probe", errorRecorder(errors, outboundCountAtError));
            }, VrtSoConfig.asServer());
            pipeRef[0] = pipe;

            List<Throwable> inboundErrors = new CopyOnWriteArrayList<>();
            pipe.channel().subscribe(p -> p.isInbound() && p.getError() != null, SubscribeMode.SYNC, p -> inboundErrors.add(p.getError()));

            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI));
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);

            assertTrue(inbound.isEmpty());
            assertEquals(500, response.status().code());
            assertTrue(waitUntil(() -> !errors.isEmpty() && !inboundErrors.isEmpty(), 1000L));
            assertEquals("websocket handshake authorizer failed", errors.get(0).getMessage());
            assertEquals("websocket handshake authorizer failed", inboundErrors.get(0).getMessage());
            assertNotNull(errors.get(0).getCause());
            assertEquals("boom", errors.get(0).getCause().getMessage());
            assertTrue(outboundCountAtError.get() >= 3);
            assertTrue(pipe.channel().isClose());
        });
    }

    @Test
    public void testAsyncAuthorizerAcceptProducesReadyContextAndResponse() throws Throwable {
        autoCloseNeta(neta -> {
            AtomicReference<WebSocketHandshakeCallback> asyncCallback = new AtomicReference<>();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (request, callback) -> asyncCallback.set(callback);
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13, authorizer));
            }, VrtSoConfig.asServer());

            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI));
            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));
            assertTrue(drainQueue(pipe.channelOutbound()).isEmpty());

            asyncCallback.get().accept();

            assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.channel()), 1000L));
            assertTrue(waitUntil(() -> pipe.channelOutbound().size() >= 3, 1000L));
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);

            assertEquals(101, response.status().code());
        });
    }

    @Test
    public void testAsyncAuthorizerRejectsWithoutReadyContext() throws Throwable {
        autoCloseNeta(neta -> {
            AtomicReference<WebSocketHandshakeCallback> asyncCallback = new AtomicReference<>();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (request, callback) -> asyncCallback.set(callback);
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13, authorizer));
            }, VrtSoConfig.asServer());

            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI));
            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));

            asyncCallback.get().reject(HttpStatus.FORBIDDEN, "denied".getBytes(StandardCharsets.UTF_8));

            assertTrue(waitUntil(() -> pipe.channelOutbound().size() >= 3, 1000L));
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);
            HttpHeaders headers = (HttpHeaders) outbound.get(1);

            assertNull(webSocketContext(pipe.channel()));
            assertEquals(403, response.status().code());
            assertEquals("6", headers.getString(HttpHeaderNames.CONTENT_LENGTH));
        });
    }

    @Test
    public void testAsyncAuthorizerPendingStateRejectsAdditionalInboundData() throws Throwable {
        autoCloseNeta(neta -> {
            List<WebSocketHandshakeCallback> asyncCallback = new ArrayList<>();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (request, callback) -> asyncCallback.add(callback);
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13, authorizer));
            }, VrtSoConfig.asServer());

            assertTrue(receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI)).isEmpty());
            assertTrue(drainQueue(pipe.channelOutbound()).isEmpty());

            assertTrue(receiveAndIntBound(pipe, new DefaultHttpByteBuf(ascii("abc"))).isEmpty());
            assertTrue(waitUntil(() -> pipe.channelOutbound().size() >= 3, 1000L));
            List<Object> rejected = drainQueue(pipe.channelOutbound());
            HttpResponse rejectedResponse = (HttpResponse) rejected.get(0);
            assertEquals(400, rejectedResponse.status().code());

            asyncCallback.get(0).accept();
            assertNull(webSocketContext(pipe.channel()));
            assertTrue(drainQueue(pipe.channelOutbound()).isEmpty());

            assertTrue(receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI)).isEmpty());
            asyncCallback.get(1).accept();

            assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.channel()), 1000L));
            assertTrue(waitUntil(() -> pipe.channelOutbound().size() >= 3, 1000L));
            List<Object> success = drainQueue(pipe.channelOutbound());
            HttpResponse successResponse = (HttpResponse) success.get(0);
            assertEquals(101, successResponse.status().code());
        });
    }

    @Test
    public void testClientValid101ResponseCreatesReadyContext() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplex(WebSocketVersion.V13)), VrtSoConfig.asClient());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI);
            request.streamId(11);
            String wsKey = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY);
            sendAndOutBound(pipe, request);

            DefaultFullHttpResponse response = newUpgradeResponse(wsKey);
            List<HttpObject> inbound = receiveAndIntBound(pipe, response);
            WebSocketHandshakeEvent handshakeEvent = handshakeEvent(pipe.channelEvents());

            assertTrue(inbound.isEmpty());
            assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.channel()), 1000L));
            assertNotNull(handshakeEvent);
            assertTrue(handshakeEvent.isClient());
            assertEquals(WebSocketVersion.V13.code(), handshakeEvent.version());
            assertEquals("/chat", handshakeEvent.requestPath());
            assertEquals(WebSocketVersion.V13.code(), webSocketContext(pipe.channel()).version());
            assertEquals("/chat", webSocketContext(pipe.channel()).requestPath());
        });
    }

    @Test
    public void testClientRejectsNon101ResponseWithoutReadyContext() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplex(WebSocketVersion.V13)), VrtSoConfig.asClient());

            sendAndOutBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI));
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.FORBIDDEN);
            response.setHeader(HttpHeaderNames.CONTENT_LENGTH, "0");
            List<HttpObject> inbound = receiveAndIntBound(pipe, response);

            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));
            assertTrue(pipe.channel().isClose());
        });
    }

    @Test
    public void testClientRejectsUnexpectedAcceptKeyWithoutReadyContext() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplex(WebSocketVersion.V13)), VrtSoConfig.asClient());

            sendAndOutBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI));
            DefaultFullHttpResponse response = newUpgradeResponse("another-key==");
            List<HttpObject> inbound = receiveAndIntBound(pipe, response);

            assertTrue(inbound.isEmpty());
            assertNull(webSocketContext(pipe.channel()));
            assertTrue(pipe.channel().isClose());
        });
    }

    @Test
    public void testLinkedClientAndServerPipesBothCreateReadyContext() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, clientCtx -> {
                clientCtx.addLast("ws-client", new WebSocketClientHandshakeDuplex(WebSocketVersion.V13));
            }, serverCtx -> {
                serverCtx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13));
            }, VrtTransfer.direct());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI);
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "graphql-ws");
            pipe.client().sendData(request).get();

            assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.client()) && WebSocketUtils.isReady(pipe.server()), 1000L));
            assertTrue(drainQueue(pipe.serverInbound()).isEmpty());
            assertTrue(drainQueue(pipe.clientInbound()).isEmpty());
            assertEquals("/chat", webSocketContext(pipe.server()).requestPath());
            assertEquals("/chat", webSocketContext(pipe.client()).requestPath());
        });
    }

    @Test
    public void testServerReadyContextIsRemovedFromRegistryWhenChannelCloses() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplex(WebSocketVersion.V13)), VrtSoConfig.asServer());

            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, WS_URI));
            assertTrue(inbound.isEmpty());
            assertTrue(WebSocketUtils.isReady(pipe.channel()));
            assertNotNull(webSocketContext(pipe.channel()));

            pipe.channel().close();
            // Graceful close runs on the pipeline executor: the close flag flips immediately but
            // registry cleanup (duplex onClose) completes asynchronously — wait for the actual state.
            assertTrue(waitUntil(() -> webSocketContext(pipe.channel()) == null, 1000L));
            assertTrue(pipe.channel().isClose());
        });
    }
}