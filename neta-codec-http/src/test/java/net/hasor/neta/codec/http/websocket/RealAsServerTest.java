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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.real.websocket.OkHttpWebSocketClientHarness;
import net.hasor.neta.codec.http.routing.HttpRouteKey;
import okhttp3.OkHttpClient;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RealAsServerTest extends AbstractWebSocketTest {
    private static final String BRANCH_HTTP      = "http";
    private static final String BRANCH_HANDSHAKE = "handshake";
    private static final String WS_PATH          = "/chat";
    private static final String BRANCH_WEBSOCKET = HttpRouteKey.BRANCH_SOCKET;

    private OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()           //
                .connectTimeout(5, TimeUnit.SECONDS)//
                .readTimeout(5, TimeUnit.SECONDS)   //
                .writeTimeout(5, TimeUnit.SECONDS)  //
                .build();
    }

    private ThroughProtoHandler<WebSocketMessage> pongEventRecorder(CountDownLatch pongLatch, AtomicReference<String> pongPayload) {
        return new ThroughProtoHandler<WebSocketMessage>() {
            @Override
            public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
                if (event.getData() instanceof PongWebSocketEvent) {
                    PongWebSocketEvent pongEvent = (PongWebSocketEvent) event.getData();
                    pongPayload.set(pongEvent.content().readString(pongEvent.content().readableBytes(), StandardCharsets.US_ASCII));
                    pongLatch.countDown();
                    pongEvent.release();
                }
                return true;
            }
        };
    }

    private ProtoHandler<HttpObject, Object> httpBusinessHandler() {
        return (context, src, dst) -> {
            while (src.hasMore()) {
                HttpObject message = src.takeMessage();
                if (message instanceof FullHttpRequest) {
                    FullHttpRequest request = (FullHttpRequest) message;
                    try {
                        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, ascii("[HTTP] hello-http"));
                        response.streamId(request.streamId());
                        response.setHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                        response.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(response.content().readableBytes()));
                        context.sendData(response);
                    } finally {
                        request.release();
                    }
                }
            }
            return ProtoStatus.Next;
        };
    }

    private ProtoHandler<WebSocketMessage, WebSocketMessage> webSocketBusinessHandler() {
        return (context, src, dst) -> {
            while (src.hasMore()) {
                WebSocketMessage message = src.takeMessage();
                if (message == null) {
                    continue;
                }
                try {
                    if (message instanceof TextWebSocketMessage) {
                        String content = text(message.content().retain());
                        if ("trigger-ping".equals(content)) {
                            context.fireUserEventSnd(PingWebSocketEvent.class, WebSocketUtils.pingEvent(ascii("ping-okhttp")));
                        } else {
                            context.sendData(WebSocketUtils.textMessage(ascii("[Echo] " + content)));
                        }
                    } else if (message instanceof BinaryWebSocketMessage) {
                        context.sendData(WebSocketUtils.binaryMessage(message.content().retain()));
                    }
                } finally {
                    message.release();
                }
            }
            return ProtoStatus.Next;
        };
    }

    private ThroughProtoHandler<HttpObject> webSocketRouteSwitchHandler() {
        return new ThroughProtoHandler<HttpObject>() {
            @Override
            public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
                if (event.getData() instanceof WebSocketHandshakeEvent) {
                    ProtoRoutingControl routingControl = context.context(ProtoRoutingControl.class);
                    if (routingControl != null) {
                        routingControl.switchRoute(BRANCH_WEBSOCKET);
                    }
                }
                return true;
            }
        };
    }

    private ProtoInitializer webSocketBranchInitializer(CountDownLatch pongLatch, AtomicReference<String> pongPayload) {
        return branchCtx -> {
            ProtoRoutingBuilder<HttpObject, HttpObject> webSocketRouting = ProtoHelper.typedRoutingAsStatic((innerContext, innerRcvUp, innerSndDown) -> BRANCH_HANDSHAKE);

            webSocketRouting.branchByInitializer(BRANCH_HANDSHAKE, innerBranchCtx -> {
                innerBranchCtx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                innerBranchCtx.addLastDecoder("ws-route-switch", webSocketRouteSwitchHandler());
            });

            webSocketRouting.branchByInitializer(BRANCH_WEBSOCKET, innerBranchCtx -> {
                innerBranchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                innerBranchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
                innerBranchCtx.addLastDecoder("ws-events", pongEventRecorder(pongLatch, pongPayload));
                innerBranchCtx.addLastDecoder("ws-echo", webSocketBusinessHandler());
            });

            branchCtx.addLast("ws-route", webSocketRouting.build());
        };
    }

    private void assertWebSocketFlow(OkHttpWebSocketClientHarness harness, CountDownLatch pongLatch, AtomicReference<String> pongPayload) throws Exception {
        harness.awaitOpen();
        harness.sendText("hello-neta");
        assertEquals("[Echo] hello-neta", harness.awaitText());
        harness.sendBinary("ABCD");
        assertEquals("ABCD", harness.awaitBinary().utf8());
        harness.sendText("trigger-ping");
        assertTrue(pongLatch.await(5, TimeUnit.SECONDS));
        assertEquals("ping-okhttp", pongPayload.get());
    }

    @Test
    public void socketOnlyServerHandlesTextBinaryAndPingPong() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        CountDownLatch pongLatch = new CountDownLatch(1);
        AtomicReference<String> pongPayload = new AtomicReference<>();
        OkHttpWebSocketClientHarness harness = null;
        try {
            neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> {
                ctx.addLast("http-server", new HttpServerDuplexe());
                ctx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                ctx.addLast("ws-message", new WebSocketMessageDuplexer());
                ctx.addLastDecoder("ws-events", pongEventRecorder(pongLatch, pongPayload));
                ctx.addLastDecoder("ws-echo", webSocketBusinessHandler());
            }, SoConfig.TCP());
            harness = OkHttpWebSocketClientHarness.connect(port, WS_PATH);
            assertWebSocketFlow(harness, pongLatch, pongPayload);
        } finally {
            if (harness != null) {
                harness.close();
            }
            neta.shutdown();
        }
    }

    @Test
    public void httpOnlyRouteServerHandlesHttpOnSamePort() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        HttpClient httpClient = new HttpClient();
        try {
            httpClient.setConnectTimeout(5000);
            httpClient.setIdleTimeout(5000);
            httpClient.start();

            neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> {
                ctx.addLast("http-server", new HttpServerDuplexe());
                ctx.addLastDecoder("http-agg", new HttpRequestAggregator(1024 * 1024));

                ProtoRoutingBuilder<HttpObject, HttpObject> routing = ProtoHelper.typedRoutingAsRealtime((context, rcvUp, sndDown) -> {
                    if (rcvUp.queueSize() == 0) {
                        return null;
                    }
                    return BRANCH_HTTP;
                });

                routing.branchByInitializer(BRANCH_HTTP, branchCtx -> {
                    branchCtx.addLastDecoder("http-handler", httpBusinessHandler());
                });

                ctx.addLast("server-route", routing.build());
            }, SoConfig.TCP());

            ContentResponse httpResponse = httpClient.newRequest("http://127.0.0.1:" + port + "/http-echo").timeout(5, TimeUnit.SECONDS).send();
            assertEquals(200, httpResponse.getStatus());
            assertEquals("[HTTP] hello-http", httpResponse.getContentAsString());
        } finally {
            httpClient.stop();
            neta.shutdown();
        }
    }

    @Test
    public void httpRouteWithUnusedSocketBranchStillHandlesHttp() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        HttpClient httpClient = new HttpClient();
        try {
            httpClient.setConnectTimeout(5000);
            httpClient.setIdleTimeout(5000);
            httpClient.start();

            neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> {
                ctx.addLast("http-server", new HttpServerDuplexe());
                ctx.addLastDecoder("http-agg", new HttpRequestAggregator(1024 * 1024));

                ProtoRoutingBuilder<HttpObject, HttpObject> routing = ProtoHelper.typedRoutingAsRealtime((context, rcvUp, sndDown) -> {
                    if (rcvUp.queueSize() == 0) {
                        return null;
                    }

                    HttpObject object = rcvUp.peekMessage();
                    if (object instanceof FullHttpRequest) {
                        FullHttpRequest request = (FullHttpRequest) object;
                        if (WS_PATH.equals(request.uri())) {
                            return BRANCH_WEBSOCKET;
                        }
                    }
                    return BRANCH_HTTP;
                });

                routing.branchByInitializer(BRANCH_HTTP, branchCtx -> {
                    branchCtx.addLastDecoder("http-handler", httpBusinessHandler());
                });
                routing.branchByInitializer(BRANCH_WEBSOCKET, branchCtx -> {
                });

                ctx.addLast("server-route", routing.build());
            }, SoConfig.TCP());

            ContentResponse httpResponse = httpClient.newRequest("http://127.0.0.1:" + port + "/http-echo").timeout(5, TimeUnit.SECONDS).send();
            assertEquals(200, httpResponse.getStatus());
            assertEquals("[HTTP] hello-http", httpResponse.getContentAsString());
        } finally {
            httpClient.stop();
            neta.shutdown();
        }
    }

    @Test
    public void httpRouteWithHandshakeBranchStillHandlesHttp() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        HttpClient httpClient = new HttpClient();
        try {
            httpClient.setConnectTimeout(5000);
            httpClient.setIdleTimeout(5000);
            httpClient.start();

            neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> {
                ctx.addLast("http-server", new HttpServerDuplexe());
                ctx.addLastDecoder("http-agg", new HttpRequestAggregator(1024 * 1024));

                ProtoRoutingBuilder<HttpObject, HttpObject> routing = ProtoHelper.typedRoutingAsRealtime((context, rcvUp, sndDown) -> {
                    if (rcvUp.queueSize() == 0) {
                        return null;
                    }

                    HttpObject object = rcvUp.peekMessage();
                    if (object instanceof FullHttpRequest) {
                        FullHttpRequest request = (FullHttpRequest) object;
                        if (WS_PATH.equals(request.uri())) {
                            return BRANCH_WEBSOCKET;
                        }
                    }
                    return BRANCH_HTTP;
                });

                routing.branchByInitializer(BRANCH_HTTP, branchCtx -> {
                    branchCtx.addLastDecoder("http-handler", httpBusinessHandler());
                });
                routing.branchByInitializer(BRANCH_WEBSOCKET, branchCtx -> {
                    branchCtx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                });

                ctx.addLast("server-route", routing.build());
            }, SoConfig.TCP());

            ContentResponse httpResponse = httpClient.newRequest("http://127.0.0.1:" + port + "/http-echo").timeout(5, TimeUnit.SECONDS).send();
            assertEquals(200, httpResponse.getStatus());
            assertEquals("[HTTP] hello-http", httpResponse.getContentAsString());
        } finally {
            httpClient.stop();
            neta.shutdown();
        }
    }

    @Test
    public void httpRouteWithHandshakeAndFrameBranchStillHandlesHttp() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        HttpClient httpClient = new HttpClient();
        try {
            httpClient.setConnectTimeout(5000);
            httpClient.setIdleTimeout(5000);
            httpClient.start();

            neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> {
                ctx.addLast("http-server", new HttpServerDuplexe());
                ctx.addLastDecoder("http-agg", new HttpRequestAggregator(1024 * 1024));

                ProtoRoutingBuilder<HttpObject, HttpObject> routing = ProtoHelper.typedRoutingAsRealtime((context, rcvUp, sndDown) -> {
                    if (rcvUp.queueSize() == 0) {
                        return null;
                    }

                    HttpObject object = rcvUp.peekMessage();
                    if (object instanceof FullHttpRequest) {
                        FullHttpRequest request = (FullHttpRequest) object;
                        if (WS_PATH.equals(request.uri())) {
                            return BRANCH_WEBSOCKET;
                        }
                    }
                    return BRANCH_HTTP;
                });

                routing.branchByInitializer(BRANCH_HTTP, branchCtx -> {
                    branchCtx.addLastDecoder("http-handler", httpBusinessHandler());
                });
                routing.branchByInitializer(BRANCH_WEBSOCKET, branchCtx -> {
                    branchCtx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                    branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                });

                ctx.addLast("server-route", routing.build());
            }, SoConfig.TCP());

            ContentResponse httpResponse = httpClient.newRequest("http://127.0.0.1:" + port + "/http-echo").timeout(5, TimeUnit.SECONDS).send();
            assertEquals(200, httpResponse.getStatus());
            assertEquals("[HTTP] hello-http", httpResponse.getContentAsString());
        } finally {
            httpClient.stop();
            neta.shutdown();
        }
    }

    @Test
    public void httpRouteWithHandshakeFrameAndMessageBranchStillHandlesHttp() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        HttpClient httpClient = new HttpClient();
        try {
            httpClient.setConnectTimeout(5000);
            httpClient.setIdleTimeout(5000);
            httpClient.start();

            neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> {
                ctx.addLast("http-server", new HttpServerDuplexe());
                ctx.addLastDecoder("http-agg", new HttpRequestAggregator(1024 * 1024));

                ProtoRoutingBuilder<HttpObject, HttpObject> routing = ProtoHelper.typedRoutingAsRealtime((context, rcvUp, sndDown) -> {
                    if (rcvUp.queueSize() == 0) {
                        return null;
                    }

                    HttpObject object = rcvUp.peekMessage();
                    if (object instanceof FullHttpRequest) {
                        FullHttpRequest request = (FullHttpRequest) object;
                        if (WS_PATH.equals(request.uri())) {
                            return BRANCH_WEBSOCKET;
                        }
                    }
                    return BRANCH_HTTP;
                });

                routing.branchByInitializer(BRANCH_HTTP, branchCtx -> {
                    branchCtx.addLastDecoder("http-handler", httpBusinessHandler());
                });
                routing.branchByInitializer(BRANCH_WEBSOCKET, branchCtx -> {
                    branchCtx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                    branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    branchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
                });

                ctx.addLast("server-route", routing.build());
            }, SoConfig.TCP());

            ContentResponse httpResponse = httpClient.newRequest("http://127.0.0.1:" + port + "/http-echo").timeout(5, TimeUnit.SECONDS).send();
            assertEquals(200, httpResponse.getStatus());
            assertEquals("[HTTP] hello-http", httpResponse.getContentAsString());
        } finally {
            httpClient.stop();
            neta.shutdown();
        }
    }

    @Test
    public void httpRouteWithEventRecorderBranchStillHandlesHttp() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        HttpClient httpClient = new HttpClient();
        CountDownLatch pongLatch = new CountDownLatch(1);
        AtomicReference<String> pongPayload = new AtomicReference<>();
        try {
            httpClient.setConnectTimeout(5000);
            httpClient.setIdleTimeout(5000);
            httpClient.start();

            neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> {
                ctx.addLast("http-server", new HttpServerDuplexe());
                ctx.addLastDecoder("http-agg", new HttpRequestAggregator(1024 * 1024));

                ProtoRoutingBuilder<HttpObject, HttpObject> routing = ProtoHelper.typedRoutingAsRealtime((context, rcvUp, sndDown) -> {
                    if (rcvUp.queueSize() == 0) {
                        return null;
                    }

                    HttpObject object = rcvUp.peekMessage();
                    if (object instanceof FullHttpRequest) {
                        FullHttpRequest request = (FullHttpRequest) object;
                        if (WS_PATH.equals(request.uri())) {
                            return BRANCH_WEBSOCKET;
                        }
                    }
                    return BRANCH_HTTP;
                });

                routing.branchByInitializer(BRANCH_HTTP, branchCtx -> {
                    branchCtx.addLastDecoder("http-handler", httpBusinessHandler());
                });
                routing.branchByInitializer(BRANCH_WEBSOCKET, branchCtx -> {
                    branchCtx.addLast("ws-handshake", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                    branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    branchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
                    branchCtx.addLastDecoder("ws-events", pongEventRecorder(pongLatch, pongPayload));
                });

                ctx.addLast("server-route", routing.build());
            }, SoConfig.TCP());

            ContentResponse httpResponse = httpClient.newRequest("http://127.0.0.1:" + port + "/http-echo").timeout(5, TimeUnit.SECONDS).send();
            assertEquals(200, httpResponse.getStatus());
            assertEquals("[HTTP] hello-http", httpResponse.getContentAsString());
        } finally {
            httpClient.stop();
            neta.shutdown();
        }
    }

    @Test
    public void httpRouteWithMixSelectorStillHandlesHttp() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        HttpClient httpClient = new HttpClient();
        CountDownLatch pongLatch = new CountDownLatch(1);
        AtomicReference<String> pongPayload = new AtomicReference<>();
        try {
            httpClient.setConnectTimeout(5000);
            httpClient.setIdleTimeout(5000);
            httpClient.start();

            neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> {
                ctx.addLast("http-server", new HttpServerDuplexe());
                ctx.addLastDecoder("http-agg", new HttpRequestAggregator(1024 * 1024));

                ProtoRoutingBuilder<HttpObject, HttpObject> routing = ProtoHelper.typedRoutingAsRealtime((context, rcvUp, sndDown) -> {
                    WebSocketContext webSocketContext = context.rootContext(WebSocketContext.class);
                    if (webSocketContext != null && webSocketContext.isReady()) {
                        return BRANCH_WEBSOCKET;
                    }
                    if (rcvUp.queueSize() == 0) {
                        return null;
                    }

                    HttpObject object = rcvUp.peekMessage();
                    if (object instanceof FullHttpRequest) {
                        FullHttpRequest request = (FullHttpRequest) object;
                        String upgrade = request.getString(HttpHeaderNames.UPGRADE);
                        String connection = request.getString(HttpHeaderNames.CONNECTION);
                        if (WS_PATH.equals(request.uri()) && HttpHeaderValues.WEBSOCKET.equalsIgnoreCase(upgrade) && connection != null && connection.toLowerCase().contains(HttpHeaderValues.UPGRADE)) {
                            return BRANCH_WEBSOCKET;
                        }
                        return BRANCH_HTTP;
                    }
                    if (object instanceof HttpByteBuf) {
                        return BRANCH_WEBSOCKET;
                    }
                    return BRANCH_HTTP;
                });

                routing.branchByInitializer(BRANCH_HTTP, branchCtx -> {
                    branchCtx.addLastDecoder("http-handler", httpBusinessHandler());
                });
                routing.branchByInitializer(BRANCH_WEBSOCKET, webSocketBranchInitializer(pongLatch, pongPayload));

                ctx.addLast("server-route", routing.build());
            }, SoConfig.TCP());

            ContentResponse httpResponse = httpClient.newRequest("http://127.0.0.1:" + port + "/http-echo").timeout(5, TimeUnit.SECONDS).send();
            assertEquals(200, httpResponse.getStatus());
            assertEquals("[HTTP] hello-http", httpResponse.getContentAsString());
        } finally {
            httpClient.stop();
            neta.shutdown();
        }
    }

    @Test
    public void mixServerHandlesHttpAndWebSocketOnSamePort() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        CountDownLatch pongLatch = new CountDownLatch(1);
        AtomicReference<String> pongPayload = new AtomicReference<>();
        HttpClient httpClient = new HttpClient();
        OkHttpWebSocketClientHarness harness = null;
        try {
            httpClient.setConnectTimeout(5000);
            httpClient.setIdleTimeout(5000);
            httpClient.start();
            neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> {
                ctx.addLast("http-server", new HttpServerDuplexe());
                ctx.addLastDecoder("http-agg", new HttpRequestAggregator(1024 * 1024));

                ProtoRoutingBuilder<HttpObject, HttpObject> routing = ProtoHelper.typedRoutingAsRealtime((context, rcvUp, sndDown) -> {
                    WebSocketContext webSocketContext = context.rootContext(WebSocketContext.class);
                    if (webSocketContext != null && webSocketContext.isReady()) {
                        return BRANCH_WEBSOCKET;
                    }
                    if (rcvUp.queueSize() == 0) {
                        return null;
                    }

                    HttpObject object = rcvUp.peekMessage();
                    if (object instanceof FullHttpRequest) {
                        FullHttpRequest request = (FullHttpRequest) object;
                        String upgrade = request.getString(HttpHeaderNames.UPGRADE);
                        String connection = request.getString(HttpHeaderNames.CONNECTION);
                        if (WS_PATH.equals(request.uri()) && HttpHeaderValues.WEBSOCKET.equalsIgnoreCase(upgrade) && connection != null && connection.toLowerCase().contains(HttpHeaderValues.UPGRADE)) {
                            return BRANCH_WEBSOCKET;
                        }
                        return BRANCH_HTTP;
                    }
                    if (object instanceof HttpByteBuf) {
                        return BRANCH_WEBSOCKET;
                    }
                    return BRANCH_HTTP;
                });

                routing.branchByInitializer(BRANCH_HTTP, branchCtx -> {
                    branchCtx.addLastDecoder("http-handler", httpBusinessHandler());
                });
                routing.branchByInitializer(BRANCH_WEBSOCKET, webSocketBranchInitializer(pongLatch, pongPayload));

                ctx.addLast("server-route", routing.build());
            }, SoConfig.TCP());

            ContentResponse httpResponse = httpClient.newRequest("http://127.0.0.1:" + port + "/http-echo").timeout(5, TimeUnit.SECONDS).send();
            assertEquals(200, httpResponse.getStatus());
            assertEquals("[HTTP] hello-http", httpResponse.getContentAsString());

            harness = OkHttpWebSocketClientHarness.connect(port, WS_PATH);
            assertWebSocketFlow(harness, pongLatch, pongPayload);
        } finally {
            if (harness != null) {
                harness.close();
            }
            httpClient.stop();
            neta.shutdown();
        }
    }
}
