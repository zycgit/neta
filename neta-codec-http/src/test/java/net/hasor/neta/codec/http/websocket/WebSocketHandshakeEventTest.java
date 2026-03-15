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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtTransfer;
import net.hasor.neta.codec.http.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketHandshakeEventTest extends AbstractWebSocketTest {
    private static final String OUTBOUND_SIZE_FLASH_KEY = "test.outbound.size";

    private static WebSocketHandshakeEvent handshakeEvent(Iterable<SoUserEvent> events) {
        if (events == null) {
            return null;
        }
        for (SoUserEvent event : events) {
            if (event != null && event.getData() instanceof WebSocketHandshakeEvent) {
                return (WebSocketHandshakeEvent) event.getData();
            }
        }
        return null;
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

    private static DefaultFullHttpResponse newUpgradeResponse(String path, String key) {
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

    // accept

    @Test
    public void testServerPublishesHandshakeEventForRfc6455Success() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (event, callback) -> callback.accept();
                WebSocketHandshakeDuplexer duplexer = new WebSocketHandshakeDuplexer(true, WebSocketVersion.V13, authorizer);

                ctx.addLast("ws-server", duplexer);
            }, VrtSoConfig.asServer());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.streamId(7);
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "graphql-ws");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");
            String wsKey = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY);

            List<HttpObject> inbound = receiveAndIntBound(pipe, request);
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            WebSocketHandshakeEvent event = handshakeEvent(pipe.channelUserEvents());
            HttpResponse response = (HttpResponse) outbound.get(0);
            HttpHeaders headers = (HttpHeaders) outbound.get(1);
            HttpContent content = (HttpContent) outbound.get(2);

            assertTrue(inbound.isEmpty());
            assertNotNull(event);
            assertEquals(7, event.streamId());
            assertEquals(WebSocketVersion.V13, event.version());
            assertEquals("/chat", event.requestPath());
            assertEquals("graphql-ws", event.subProtocol());
            assertEquals("permessage-deflate", event.extensions());
            assertEquals(wsKey, event.header(HttpHeaderNames.SEC_WEBSOCKET_KEY));
            assertEquals(3, outbound.size());
            assertEquals(101, response.status().code());
            assertEquals(computeAcceptKey(wsKey), headers.getString(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT));
            assertEquals(0, content.content().readableBytes());
        });
    }

    @Test
    public void testServerPublishesHandshakeEventForLegacyV0Success() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (event, callback) -> callback.accept();
                WebSocketHandshakeDuplexer duplexer = new WebSocketHandshakeDuplexer(true, WebSocketVersion.V0, authorizer);

                ctx.addLast("ws-server", duplexer);
            }, VrtSoConfig.asServer());

            DefaultHttpHeaders headers = new DefaultHttpHeaders();
            headers.setHeader(HttpHeaderNames.ORIGIN, "http://example.com");
            headers.setHeader(HttpHeaderNames.HOST, "example.com");
            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V0, "/legacy", headers));
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            WebSocketHandshakeEvent event = handshakeEvent(pipe.channelUserEvents());
            HttpResponse response = (HttpResponse) outbound.get(0);
            HttpHeaders responseHeaders = (HttpHeaders) outbound.get(1);
            HttpContent content = (HttpContent) outbound.get(2);

            assertTrue(inbound.isEmpty());
            assertNotNull(event);
            assertEquals(WebSocketVersion.V0, event.version());
            assertEquals("/legacy", event.requestPath());
            assertEquals(3, outbound.size());
            assertEquals(101, response.status().code());
            assertEquals("WebSocket", responseHeaders.getString(HttpHeaderNames.UPGRADE));
            assertEquals("http://example.com", responseHeaders.getString(HttpHeaderNames.SEC_WEBSOCKET_ORIGIN));
            assertEquals("ws://example.com/legacy", responseHeaders.getString(HttpHeaderNames.SEC_WEBSOCKET_LOCATION));
            assertEquals(16, content.content().readableBytes());
        });
    }

    @Test
    public void testServerRejectsMalformedHandshakeWithoutPublishingEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (event, callback) -> callback.accept();
                WebSocketHandshakeDuplexer duplexer = new WebSocketHandshakeDuplexer(true, WebSocketVersion.V13, authorizer);

                ctx.addLast("ws-server", duplexer);
            }, VrtSoConfig.asServer());

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/chat");
            request.setHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
            request.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");

            List<HttpObject> inbound = receiveAndIntBound(pipe, request);
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);

            assertTrue(inbound.isEmpty());
            assertNull(handshakeEvent(pipe.channelUserEvents()));
            assertEquals(3, outbound.size());
            assertEquals(400, response.status().code());
        });
    }

    @Test
    public void testHandshakeEventListenerFailureDoesNotBlockSuccessfulResponse() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-server", new WebSocketHandshakeDuplexer(true, WebSocketVersion.V13));
                ctx.addLastDecoder("probe", new ThroughProtoHandler<HttpObject>() {
                    @Override
                    public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
                        throw new IllegalStateException("probe-event-failed");
                    }
                });
            }, VrtSoConfig.asServer());

            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat"));
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);

            assertTrue(inbound.isEmpty());
            assertEquals(3, outbound.size());
            assertEquals(101, response.status().code());
        });
    }

    @Test
    public void testAuthorizerAcceptsAndAppendsResponseHeaders() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (event, callback) -> {
                    assertEquals("/chat", event.requestPath());
                    DefaultHttpHeaders headers = new DefaultHttpHeaders();
                    headers.setHeader("X-Accept", "ok");
                    headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "chat");
                    headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");
                    callback.accept(headers);
                };

                WebSocketHandshakeDuplexer duplexer = new WebSocketHandshakeDuplexer(true, WebSocketVersion.V13, authorizer);
                ctx.addLast("ws-server", duplexer);
            }, VrtSoConfig.asServer());

            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat"));
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);
            HttpHeaders headers = (HttpHeaders) outbound.get(1);

            assertTrue(inbound.isEmpty());
            assertNotNull(handshakeEvent(pipe.channelUserEvents()));
            assertEquals(101, response.status().code());
            assertEquals("ok", headers.getString("X-Accept"));
            assertEquals("chat", headers.getString(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL));
            assertEquals("permessage-deflate", headers.getString(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS));
        });
    }

    // reject

    @Test
    public void testAuthorizerRejectsWithDefaultMethodNotAllowed() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (event, callback) -> callback.reject();
                WebSocketHandshakeDuplexer duplexer = new WebSocketHandshakeDuplexer(true, WebSocketVersion.V13, authorizer);

                ctx.addLast("ws-server", duplexer);
            }, VrtSoConfig.asServer());

            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat"));
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);
            HttpHeaders headers = (HttpHeaders) outbound.get(1);

            assertTrue(inbound.isEmpty());
            assertNull(handshakeEvent(pipe.channelUserEvents()));
            assertEquals(405, response.status().code());
            assertEquals("0", headers.getString(HttpHeaderNames.CONTENT_LENGTH));
        });
    }

    @Test
    public void testAuthorizerExceptionTurnsInto500AndClosesChannel() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (event, callback) -> {
                    throw new IllegalStateException("boom");
                };
                WebSocketHandshakeDuplexer duplexer = new WebSocketHandshakeDuplexer(true, WebSocketVersion.V13, authorizer);

                ctx.addLast("ws-server", duplexer);
            }, VrtSoConfig.asServer());

            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat"));
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);

            assertTrue(inbound.isEmpty());
            assertNull(handshakeEvent(pipe.channelUserEvents()));
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
                WebSocketHandshakeAuthorizer authorizer = (event, callback) -> {
                    throw new IllegalStateException("boom");
                };
                WebSocketHandshakeDuplexer duplexer = new WebSocketHandshakeDuplexer(true, WebSocketVersion.V13, authorizer);

                ctx.addLast("ws-server", duplexer);
                ctx.addLastDecoder("order-probe", outboundCountRecorder(pipeRef));
                ctx.addLastDecoder("probe", errorRecorder(errors, outboundCountAtError));
            }, VrtSoConfig.asServer());
            pipeRef[0] = pipe;

            List<Throwable> inboundErrors = new CopyOnWriteArrayList<>();
            pipe.channel().subscribe(p -> {
                return p.isInbound() && p.getError() != null;
            }, SubscribeMode.SYNC, p -> {
                inboundErrors.add(p.getError());
            });

            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat"));
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
    public void testAsyncAuthorizerAcceptPublishesEventAndResponse() throws Throwable {
        autoCloseNeta(neta -> {
            AtomicReference<WebSocketHandshakeCallback> asyncCallback = new AtomicReference<>();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (event, callback) -> {
                    asyncCallback.set(callback);
                };
                WebSocketHandshakeDuplexer duplexer = new WebSocketHandshakeDuplexer(true, WebSocketVersion.V13, authorizer);

                ctx.addLast("ws-server", duplexer);
            }, VrtSoConfig.asServer());

            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat"));
            assertTrue(inbound.isEmpty());
            assertNull(handshakeEvent(pipe.channelUserEvents()));
            assertTrue(drainQueue(pipe.channelOutbound()).isEmpty());

            //
            asyncCallback.get().accept();
            assertTrue(waitUntil(() -> handshakeEvent(pipe.channelUserEvents()) != null, 1000L));
            assertTrue(waitUntil(() -> pipe.channelOutbound().size() >= 3, 1000L));
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);

            assertEquals(101, response.status().code());
        });
    }

    @Test
    public void testAsyncAuthorizerRejectsWithoutPublishingEvent() throws Throwable {
        autoCloseNeta(neta -> {
            AtomicReference<WebSocketHandshakeCallback> asyncCallback = new AtomicReference<>();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (event, callback) -> {
                    asyncCallback.set(callback);
                };
                WebSocketHandshakeDuplexer duplexer = new WebSocketHandshakeDuplexer(true, WebSocketVersion.V13, authorizer);

                ctx.addLast("ws-server", duplexer);
            }, VrtSoConfig.asServer());

            List<HttpObject> inbound = receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat"));
            assertTrue(inbound.isEmpty());
            assertNull(handshakeEvent(pipe.channelUserEvents()));

            asyncCallback.get().reject(HttpStatus.FORBIDDEN, "denied".getBytes(StandardCharsets.UTF_8));

            //
            assertTrue(waitUntil(() -> pipe.channelOutbound().size() >= 3, 1000L));
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);
            HttpHeaders headers = (HttpHeaders) outbound.get(1);

            assertNull(handshakeEvent(pipe.channelUserEvents()));
            assertEquals(403, response.status().code());
            assertEquals("6", headers.getString(HttpHeaderNames.CONTENT_LENGTH));
        });
    }

    @Test
    public void testAsyncAuthorizerPendingStateRejectsAdditionalInboundData() throws Throwable {
        autoCloseNeta(neta -> {
            List<WebSocketHandshakeCallback> asyncCallback = new ArrayList<>();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                WebSocketHandshakeAuthorizer authorizer = (event, callback) -> {
                    asyncCallback.add(callback);
                };
                WebSocketHandshakeDuplexer duplexer = new WebSocketHandshakeDuplexer(true, WebSocketVersion.V13, authorizer);

                ctx.addLast("ws-server", duplexer);
            }, VrtSoConfig.asServer());

            assertTrue(receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat")).isEmpty());
            assertTrue(drainQueue(pipe.channelOutbound()).isEmpty());

            assertTrue(receiveAndIntBound(pipe, new DefaultHttpByteBuf(ascii("abc"))).isEmpty());
            assertTrue(waitUntil(() -> pipe.channelOutbound().size() >= 3, 1000L));
            List<Object> rejected = drainQueue(pipe.channelOutbound());
            HttpResponse rejectedResponse = (HttpResponse) rejected.get(0);
            assertEquals(400, rejectedResponse.status().code());

            asyncCallback.get(0).accept();
            assertNull(handshakeEvent(pipe.channelUserEvents()));
            assertTrue(drainQueue(pipe.channelOutbound()).isEmpty());

            assertTrue(receiveAndIntBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat")).isEmpty());
            asyncCallback.get(1).accept();

            //
            assertTrue(waitUntil(() -> handshakeEvent(pipe.channelUserEvents()) != null, 1000L));
            assertTrue(waitUntil(() -> pipe.channelOutbound().size() >= 3, 1000L));
            List<Object> success = drainQueue(pipe.channelOutbound());
            HttpResponse successResponse = (HttpResponse) success.get(0);
            assertEquals(101, successResponse.status().code());
        });
    }

    @Test
    public void testClientPublishesHandshakeEventForValid101Response() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-client", new WebSocketHandshakeDuplexer(false, WebSocketVersion.V13));
            }, VrtSoConfig.asClient());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.streamId(11);
            String wsKey = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY);
            sendAndOutBound(pipe, request);

            DefaultFullHttpResponse response = newUpgradeResponse("/chat", wsKey);
            List<HttpObject> inbound = receiveAndIntBound(pipe, response);

            assertTrue(inbound.isEmpty());
            assertTrue(waitUntil(() -> handshakeEvent(pipe.channelUserEvents()) != null, 1000L));
            assertEquals(WebSocketVersion.V13, handshakeEvent(pipe.channelUserEvents()).version());
            assertEquals("/chat", handshakeEvent(pipe.channelUserEvents()).requestPath());
        });
    }

    @Test
    public void testClientHandshakeEventListenerFailureDoesNotAbortSuccessfulUpgrade() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-client", new WebSocketHandshakeDuplexer(false, WebSocketVersion.V13));
                ctx.addLastDecoder("probe", new ThroughProtoHandler<HttpObject>() {
                    @Override
                    public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
                        throw new IllegalStateException("probe-event-failed");
                    }
                });
            }, VrtSoConfig.asClient());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            String wsKey = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY);
            sendAndOutBound(pipe, request);

            List<HttpObject> inbound = receiveAndIntBound(pipe, newUpgradeResponse("/chat", wsKey));

            assertTrue(inbound.isEmpty());
            assertFalse(pipe.channel().isClose());
        });
    }

    @Test
    public void testClientRejectsNon101ResponseWithoutPublishingEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-client", new WebSocketHandshakeDuplexer(false, WebSocketVersion.V13));
            }, VrtSoConfig.asClient());

            sendAndOutBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat"));
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.FORBIDDEN);
            response.setHeader(HttpHeaderNames.CONTENT_LENGTH, "0");
            List<HttpObject> inbound = receiveAndIntBound(pipe, response);

            assertTrue(inbound.isEmpty());
            assertNull(handshakeEvent(pipe.channelUserEvents()));
            assertTrue(pipe.channel().isClose());
        });
    }

    @Test
    public void testClientRejectsUnexpectedAcceptKeyWithoutPublishingEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-client", new WebSocketHandshakeDuplexer(false, WebSocketVersion.V13));
            }, VrtSoConfig.asClient());

            sendAndOutBound(pipe, WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat"));
            DefaultFullHttpResponse response = newUpgradeResponse("/chat", "another-key==");
            List<HttpObject> inbound = receiveAndIntBound(pipe, response);

            assertTrue(inbound.isEmpty());
            assertNull(handshakeEvent(pipe.channelUserEvents()));
            assertTrue(pipe.channel().isClose());
        });
    }

    @Test
    public void testLinkedClientAndServerPipesBothPublishHandshakeEvent() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openVirtualPipe(neta, clientCtx -> {
                clientCtx.addLast("ws-client", new WebSocketHandshakeDuplexer(false, WebSocketVersion.V13));
            }, serverCtx -> {
                serverCtx.addLast("ws-server", new WebSocketHandshakeDuplexer(true, WebSocketVersion.V13));
            }, VrtTransfer.direct());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "graphql-ws");
            pipe.client().sendData(request).get();

            assertTrue(waitUntil(() -> handshakeEvent(pipe.serverUserEvents()) != null && handshakeEvent(pipe.clientUserEvents()) != null, 1000L));

            assertTrue(drainQueue(pipe.serverInbound()).isEmpty());
            assertTrue(drainQueue(pipe.clientInbound()).isEmpty());
            assertEquals("/chat", handshakeEvent(pipe.serverUserEvents()).requestPath());
            assertEquals("/chat", handshakeEvent(pipe.clientUserEvents()).requestPath());
            assertEquals("graphql-ws", handshakeEvent(pipe.serverUserEvents()).subProtocol());
            assertEquals("graphql-ws", handshakeEvent(pipe.clientUserEvents()).subProtocol());
        });
    }
}