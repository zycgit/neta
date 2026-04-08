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
import java.util.Collections;
import java.util.List;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.channel.SoEvent;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtTransfer;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.websocket.extensions.DeflateFrameSupport;
import net.hasor.neta.codec.http.websocket.extensions.PerMessageDeflateSupport;
import net.hasor.neta.codec.http.websocket.extensions.XWebkitDeflateFrameSupport;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketHandshakeExtensionTest extends AbstractWebSocketTest {
    private static class PassthroughExtensionSupport implements WebSocketExtension {
        private final String extensionName;

        private PassthroughExtensionSupport(String extensionName) {
            this.extensionName = extensionName;
        }

        @Override
        public String extensionName() {
            return this.extensionName;
        }

        @Override
        public String selectServerExtensions(WebSocketHandshakeRequest request, String proposedExtensions) {
            WebSocketExtensionResult requested = parseSingle(request != null ? request.requestedExtensions() : null, this.extensionName, "websocket handshake failed: unsupported websocket extension negotiation.");
            WebSocketExtensionResult proposed = parseSingle(proposedExtensions, this.extensionName, "websocket handshake failed: unsupported websocket extension negotiation.");
            if (proposed == null) {
                return null;
            }
            if (requested == null) {
                throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket handshake failed: server selected an unsolicited websocket extension.");
            }
            return proposed.asHeaderValue();
        }

        @Override
        public void validateClientExtensions(WebSocketVersion version, String requestedExtensions, String negotiatedExtensions) {
            WebSocketExtensionResult requested = parseSingle(requestedExtensions, this.extensionName, "websocket upgrade failed: unsupported websocket extension negotiation.");
            WebSocketExtensionResult negotiated = parseSingle(negotiatedExtensions, this.extensionName, "websocket upgrade failed: unsupported websocket extension negotiation.");
            if (negotiated == null) {
                return;
            }
            if (requested == null) {
                throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, "websocket upgrade failed: server selected an unsolicited websocket extension.");
            }
        }

        @Override
        public WebSocketExtensionResult parseNegotiatedExtension(String headerValue) {
            return parseSingle(headerValue, this.extensionName, "websocket extension runtime initialization failed: unsupported websocket extension negotiation.");
        }

        @Override
        public WebSocketExtensionRuntime createRuntimeExtension(final WebSocketExtensionResult negotiatedExtension) {
            return new WebSocketExtensionRuntime() {
                @Override
                public WebSocketExtensionResult negotiatedExtension() {
                    return negotiatedExtension;
                }

                @Override
                public boolean handlesInboundFrame(WebSocketFrame frame) {
                    return false;
                }

                @Override
                public boolean handlesOutboundFrame(WebSocketFrame frame) {
                    return false;
                }

                @Override
                public WebSocketFrame decodeFrame(ProtoContext context, WebSocketFrame frame) {
                    return frame;
                }

                @Override
                public WebSocketFrame encodeFrame(ProtoContext context, WebSocketFrame frame) {
                    return frame;
                }
            };
        }

        private static WebSocketExtensionResult parseSingle(String headerValue, String expectedName, String invalidMessage) {
            List<WebSocketExtensionResult> results = WebSocketExtensionResult.parse(headerValue);
            if (results.isEmpty()) {
                return null;
            }
            if (results.size() != 1) {
                throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage);
            }

            WebSocketExtensionResult result = results.get(0);
            if (!expectedName.equalsIgnoreCase(result.name()) || !result.parameters().isEmpty()) {
                throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, invalidMessage);
            }
            return new WebSocketExtensionResult(expectedName, Collections.emptyMap());
        }
    }

    private static WebSocketContext webSocketContext(SoChannel<?> channel) {
        return WebSocketRegistry.resolve(channel);
    }

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
            WebSocketSettings serverSettings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");
                callback.accept(headers);
            });
            WebSocketSettings clientSettings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> {
                ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(clientSettings));
            }, ctx -> {
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(serverSettings));
            }, VrtTransfer.direct());
            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, PerMessageDeflateSupport.EXTENSION_NAME);
            pipe.client().sendData(request, "ws-client").get();
            assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.client()) && WebSocketUtils.isReady(pipe.server()), 1000L));

            WebSocketContext serverContext = webSocketContext(pipe.server());
            WebSocketHandshakeEvent serverEvent = handshakeEvent(pipe.serverEvents());

            assertNotNull(serverContext);
            assertEquals("permessage-deflate", serverContext.extensions());
            assertTrue(serverContext.hasExtension(PerMessageDeflateSupport.EXTENSION_NAME));
            assertEquals(1, serverContext.extensionList().size());
            WebSocketExtensionResult result = serverContext.extensionList().get(0);
            assertEquals(PerMessageDeflateSupport.EXTENSION_NAME, result.name());
            assertTrue(result.parameters().isEmpty());
            assertNotNull(serverEvent);
            assertTrue(serverEvent.hasExtension(PerMessageDeflateSupport.EXTENSION_NAME));
            assertEquals(1, serverEvent.extensionResults().size());
        });
    }

    @Test
    public void testSettingsNegotiatorCanAcceptServerSelectedPerMessageDeflateWithParameters() throws Throwable {
        autoCloseNeta(neta -> {
            String extHeader = "permessage-deflate; client_no_context_takeover; server_no_context_takeover; client_max_window_bits=15; server_max_window_bits=15";
            WebSocketSettings serverSettings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, extHeader);
                callback.accept(headers);
            });
            WebSocketSettings clientSettings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(clientSettings)), ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(serverSettings)), VrtTransfer.direct());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, extHeader);
            pipe.client().sendData(request, "ws-client").get();
            assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.client()) && WebSocketUtils.isReady(pipe.server()), 1000L));

            WebSocketContext context = webSocketContext(pipe.server());
            assertNotNull(context);
            assertTrue(context.hasExtension(PerMessageDeflateSupport.EXTENSION_NAME));
            assertEquals("15", context.extensionList().get(0).parameter(PerMessageDeflateSupport.CLIENT_MAX_WINDOW_BITS));
            assertEquals("15", context.extensionList().get(0).parameter(PerMessageDeflateSupport.SERVER_MAX_WINDOW_BITS));
            assertTrue(context.extensionList().get(0).hasParameter(PerMessageDeflateSupport.CLIENT_NO_CONTEXT_TAKEOVER));
            assertTrue(context.extensionList().get(0).hasParameter(PerMessageDeflateSupport.SERVER_NO_CONTEXT_TAKEOVER));
        });
    }

    @Test
    public void testSettingsNegotiatorAllowsServerSelectedPerMessageDeflateWithImplicitServerLimits() throws Throwable {
        autoCloseNeta(neta -> {
            String requestHeader = "permessage-deflate";
            String responseHeader = "permessage-deflate; server_no_context_takeover; server_max_window_bits=9";
            WebSocketSettings serverSettings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, responseHeader);
                callback.accept(headers);
            });
            WebSocketSettings clientSettings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(clientSettings)), ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(serverSettings)), VrtTransfer.direct());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, requestHeader);
            pipe.client().sendData(request, "ws-client").get();
            assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.client()) && WebSocketUtils.isReady(pipe.server()), 1000L));

            WebSocketContext context = webSocketContext(pipe.client());
            assertNotNull(context);
            assertTrue(context.hasExtension(PerMessageDeflateSupport.EXTENSION_NAME));
            assertTrue(context.extensionList().get(0).hasParameter(PerMessageDeflateSupport.SERVER_NO_CONTEXT_TAKEOVER));
            assertEquals("9", context.extensionList().get(0).parameter(PerMessageDeflateSupport.SERVER_MAX_WINDOW_BITS));
        });
    }

    @Test
    public void testSettingsNegotiatorAllowsClientMaxWindowBitsHintWithoutOfferValue() throws Throwable {
        autoCloseNeta(neta -> {
            String requestHeader = "permessage-deflate; client_max_window_bits";
            String responseHeader = "permessage-deflate; client_max_window_bits=13";
            WebSocketSettings serverSettings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, responseHeader);
                callback.accept(headers);
            });
            WebSocketSettings clientSettings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(clientSettings)), ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(serverSettings)), VrtTransfer.direct());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, requestHeader);
            pipe.client().sendData(request, "ws-client").get();
            assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.client()) && WebSocketUtils.isReady(pipe.server()), 1000L));

            WebSocketContext context = webSocketContext(pipe.client());
            assertNotNull(context);
            assertEquals("13", context.extensionList().get(0).parameter(PerMessageDeflateSupport.CLIENT_MAX_WINDOW_BITS));
        });
    }

    @Test
    public void testPerMessageDeflateRejectsNegotiatedWindowBitsOutsideRequestedRange() throws Throwable {
        autoCloseNeta(neta -> {
            WebSocketSettings settings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate; client_max_window_bits=15");
                callback.accept(headers);
            });

            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(settings)), VrtSoConfig.asServer());
            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate; client_max_window_bits=14");
            List<HttpObject> inbound = receiveAndIntBound(pipe, request);
            List<Object> outbound = drainQueue(pipe.channelOutbound());

            assertTrue(inbound.isEmpty());
            assertEquals(400, ((HttpResponse) outbound.get(0)).status().code());
        });
    }

    @Test
    public void testPerMessageDeflateAcceptsWindowBitsAtLowerBound() throws Throwable {
        autoCloseNeta(neta -> {
            String extHeader = "permessage-deflate; client_max_window_bits=8; server_max_window_bits=8";
            WebSocketSettings serverSettings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, extHeader);
                callback.accept(headers);
            });
            WebSocketSettings clientSettings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(clientSettings)), ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(serverSettings)), VrtTransfer.direct());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, extHeader);
            pipe.client().sendData(request, "ws-client").get();
            assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.client()) && WebSocketUtils.isReady(pipe.server()), 1000L));

            WebSocketContext context = webSocketContext(pipe.client());
            assertNotNull(context);
            assertEquals("8", context.extensionList().get(0).parameter(PerMessageDeflateSupport.CLIENT_MAX_WINDOW_BITS));
            assertEquals("8", context.extensionList().get(0).parameter(PerMessageDeflateSupport.SERVER_MAX_WINDOW_BITS));
        });
    }

    @Test
    public void testPerMessageDeflateRejectsServerMaxWindowBitsWithoutValue() throws Throwable {
        autoCloseNeta(neta -> {
            WebSocketSettings settings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate; server_max_window_bits");
                callback.accept(headers);
            });

            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(settings)), VrtSoConfig.asServer());
            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate; server_max_window_bits");
            List<HttpObject> inbound = receiveAndIntBound(pipe, request);
            List<Object> outbound = drainQueue(pipe.channelOutbound());

            assertTrue(inbound.isEmpty());
            assertEquals(400, ((HttpResponse) outbound.get(0)).status().code());
        });
    }

    @Test
    public void testPerMessageDeflateRejectsWindowBitsWithLeadingZero() throws Throwable {
        autoCloseNeta(neta -> {
            WebSocketSettings settings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate; client_max_window_bits=015");
                callback.accept(headers);
            });

            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(settings)), VrtSoConfig.asServer());
            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate; client_max_window_bits=015");
            List<HttpObject> inbound = receiveAndIntBound(pipe, request);
            List<Object> outbound = drainQueue(pipe.channelOutbound());

            assertTrue(inbound.isEmpty());
            assertEquals(400, ((HttpResponse) outbound.get(0)).status().code());
        });
    }

    @Test
    public void testPerMessageDeflateRejectsDuplicatedParameters() throws Throwable {
        autoCloseNeta(neta -> {
            WebSocketSettings settings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate; client_no_context_takeover; CLIENT_NO_CONTEXT_TAKEOVER");
                callback.accept(headers);
            });

            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(settings)), VrtSoConfig.asServer());
            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate; client_no_context_takeover; CLIENT_NO_CONTEXT_TAKEOVER");
            List<HttpObject> inbound = receiveAndIntBound(pipe, request);
            List<Object> outbound = drainQueue(pipe.channelOutbound());

            assertTrue(inbound.isEmpty());
            assertEquals(400, ((HttpResponse) outbound.get(0)).status().code());
        });
    }

    @Test
    public void testPerMessageDeflateSupportAcceptsMixedRequestedExtensions() throws Throwable {
        autoCloseNeta(neta -> {
            WebSocketSettings settings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");
                callback.accept(headers);
            });

            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(settings)), VrtSoConfig.asServer());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate, x-test-ext");
            List<HttpObject> inbound = receiveAndIntBound(pipe, request);
            List<Object> outbound = drainQueue(pipe.channelOutbound());
            HttpResponse response = (HttpResponse) outbound.get(0);

            assertTrue(inbound.isEmpty());
            WebSocketContext context = webSocketContext(pipe.channel());
            assertNotNull(context);
            assertEquals(101, response.status().code());
            assertTrue(context.hasExtension(PerMessageDeflateSupport.EXTENSION_NAME));
            assertFalse(context.hasExtension("x-test-ext"));
        });
    }

    @Test
    public void testPerMessageDeflateSupportRejectsV0HandshakeSelection() throws Throwable {
        autoCloseNeta(neta -> {
            WebSocketSettings settings = WebSocketSettings.of(WebSocketVersion.V0).usePerMessageDeflateDefaults().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");
                callback.accept(headers);
            });

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
            WebSocketSettings clientSettings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults();
            WebSocketSettings serverSettings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, PerMessageDeflateSupport.EXTENSION_NAME);
                callback.accept(headers);
            });

            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(clientSettings)), ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(serverSettings)), VrtTransfer.direct());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "permessage-deflate");
            pipe.client().sendData(request, "ws-client").get();
            assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.client()) && WebSocketUtils.isReady(pipe.server()), 1000L));
            WebSocketContext context = webSocketContext(pipe.client());

            assertNotNull(context);
            assertEquals("permessage-deflate", context.extensions());
            assertTrue(context.hasExtension(PerMessageDeflateSupport.EXTENSION_NAME));
        });
    }

    @Test
    public void testClientPerMessageDeflateSupportRejectsMultipleNegotiatedExtensions() throws Throwable {
        autoCloseNeta(neta -> {
            WebSocketSettings settings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults();

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

    @Test
    public void testSettingsNegotiatorCanAcceptServerSelectedDeflateFrameExtension() throws Throwable {
        autoCloseNeta(neta -> {
            WebSocketSettings serverSettings = WebSocketSettings.of(WebSocketVersion.V13).useDeflateFrameDefaults().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, DeflateFrameSupport.EXTENSION_NAME);
                callback.accept(headers);
            });
            WebSocketSettings clientSettings = WebSocketSettings.of(WebSocketVersion.V13).useDeflateFrameDefaults();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(clientSettings)), ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(serverSettings)), VrtTransfer.direct());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, DeflateFrameSupport.EXTENSION_NAME);
            pipe.client().sendData(request, "ws-client").get();
            assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.client()) && WebSocketUtils.isReady(pipe.server()), 1000L));

            WebSocketContext context = webSocketContext(pipe.server());
            assertNotNull(context);
            assertEquals(DeflateFrameSupport.EXTENSION_NAME, context.extensions());
            assertTrue(context.hasExtension(DeflateFrameSupport.EXTENSION_NAME));
        });
    }

    @Test
    public void testSettingsNegotiatorCanAcceptServerSelectedXWebkitDeflateFrameExtension() throws Throwable {
        autoCloseNeta(neta -> {
            WebSocketSettings serverSettings = WebSocketSettings.of(WebSocketVersion.V13).useXWebkitDeflateFrameDefaults().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, XWebkitDeflateFrameSupport.EXTENSION_NAME);
                callback.accept(headers);
            });
            WebSocketSettings clientSettings = WebSocketSettings.of(WebSocketVersion.V13).useXWebkitDeflateFrameDefaults();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(clientSettings)), ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(serverSettings)), VrtTransfer.direct());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, XWebkitDeflateFrameSupport.EXTENSION_NAME);
            pipe.client().sendData(request, "ws-client").get();
            assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.client()) && WebSocketUtils.isReady(pipe.server()), 1000L));

            WebSocketContext context = webSocketContext(pipe.server());
            assertNotNull(context);
            assertEquals(XWebkitDeflateFrameSupport.EXTENSION_NAME, context.extensions());
            assertTrue(context.hasExtension(XWebkitDeflateFrameSupport.EXTENSION_NAME));
        });
    }

    @Test
    public void testSettingsNegotiatorCanAccumulateBuiltInExtensionCapabilities() throws Throwable {
        WebSocketSettings settings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults().useDeflateFrameDefaults();

        assertEquals(2, settings.extensionSupports().size());
    }

    @Test
    public void testSettingsRejectsDuplicateExtensionSupportNames() throws Throwable {
        try {
            WebSocketSettings.of(WebSocketVersion.V13).addExtensionSupport(new PassthroughExtensionSupport("x-test-order")).addExtensionSupport(new PassthroughExtensionSupport("X-Test-Order"));
            fail("expected duplicate websocket extension support to be rejected");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("duplicated websocket extension support"));
        }
    }

    @Test
    public void testAccumulatedSettingsCanAcceptServerSelectedDeflateFrameFromMultiRequest() throws Throwable {
        autoCloseNeta(neta -> {
            WebSocketSettings serverSettings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults().useDeflateFrameDefaults().handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, DeflateFrameSupport.EXTENSION_NAME);
                callback.accept(headers);
            });
            WebSocketSettings clientSettings = WebSocketSettings.of(WebSocketVersion.V13).usePerMessageDeflateDefaults().useDeflateFrameDefaults();
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(clientSettings)), ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(serverSettings)), VrtTransfer.direct());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, PerMessageDeflateSupport.EXTENSION_NAME + ", " + DeflateFrameSupport.EXTENSION_NAME);
            pipe.client().sendData(request, "ws-client").get();
            assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.client()) && WebSocketUtils.isReady(pipe.server()), 1000L));

            WebSocketContext context = webSocketContext(pipe.server());
            assertNotNull(context);
            assertEquals(DeflateFrameSupport.EXTENSION_NAME, context.extensions());
            assertTrue(context.hasExtension(DeflateFrameSupport.EXTENSION_NAME));
            assertFalse(context.hasExtension(PerMessageDeflateSupport.EXTENSION_NAME));
        });
    }

    @Test
    public void testHandshakeUsesNegotiatedExtensionOrderAcrossRegisteredSupports() throws Throwable {
        autoCloseNeta(neta -> {
            WebSocketExtension extensionA = new PassthroughExtensionSupport("x-order-a");
            WebSocketExtension extensionB = new PassthroughExtensionSupport("x-order-b");
            WebSocketSettings serverSettings = WebSocketSettings.of(WebSocketVersion.V13).addExtensionSupport(extensionA).addExtensionSupport(extensionB).handshakeAuthorizer((request, callback) -> {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "x-order-b, x-order-a");
                callback.accept(headers);
            });
            WebSocketSettings clientSettings = WebSocketSettings.of(WebSocketVersion.V13).addExtensionSupport(extensionA).addExtensionSupport(extensionB);
            VirtualPipe pipe = openVirtualPipe(neta, ctx -> ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(clientSettings)), ctx -> ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(serverSettings)), VrtTransfer.direct());

            FullHttpRequest request = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "x-order-a, x-order-b");
            pipe.client().sendData(request, "ws-client").get();
            assertTrue(waitUntil(() -> WebSocketUtils.isReady(pipe.client()) && WebSocketUtils.isReady(pipe.server()), 1000L));

            WebSocketContext clientContext = webSocketContext(pipe.client());
            WebSocketContext serverContext = webSocketContext(pipe.server());

            assertNotNull(clientContext);
            assertNotNull(serverContext);
            assertEquals("x-order-b, x-order-a", clientContext.extensions());
            assertEquals("x-order-b, x-order-a", serverContext.extensions());
            assertEquals("x-order-b", clientContext.extensionList().get(0).name());
            assertEquals("x-order-a", clientContext.extensionList().get(1).name());
        });
    }
}