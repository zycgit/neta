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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.real.websocket.EmbeddedWebSocketServer;
import net.hasor.neta.codec.http.real.websocket.MixedHttpWebSocketPeerServer;
import net.hasor.neta.codec.http.routing.HttpRouteKey;
import org.junit.Test;
import static org.junit.Assert.*;

public class RealAsClientTest extends AbstractWebSocketTest {
    private static final String BRANCH_HTTP = "http";

    private <T> T awaitInbound(Queue<Object> inbound, Class<T> targetType, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Object msg = inbound.poll();
            if (targetType.isInstance(msg)) {
                return targetType.cast(msg);
            }
            Thread.sleep(10L);
        }
        fail("Timed out waiting for inbound " + targetType.getSimpleName());
        return null;
    }

    private ProtoHandler<WebSocketMessage, WebSocketMessage> inboundEventTap(Queue<Object> inbound) {
        return new ProtoHandler<WebSocketMessage, WebSocketMessage>() {
            @Override
            public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
                if (event.getData() != null) {
                    inbound.offer(event.getData());
                }
                return true;
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<WebSocketMessage> src, ProtoSndQueue<WebSocketMessage> dst) throws Throwable {
                while (src.hasMore()) {
                    dst.offerMessage(src.takeMessage());
                }
                return ProtoStatus.Next;
            }
        };
    }

    @Test
    public void socketOnlyByManualHandshakeClient() throws Throwable {
        autoCloseNeta(neta -> {
            int port = findFreePort();
            try (EmbeddedWebSocketServer server = new EmbeddedWebSocketServer(port)) {
                // server
                server.start();
                server.awaitStarted();

                //client
                Queue<Object> inbound = new ConcurrentLinkedQueue<>();
                NetChannel channel = neta.connectSync(new InetSocketAddress("127.0.0.1", port), ctx -> {
                    ctx.addLast("http-client", new HttpClientDuplexe());
                    ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
                    ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    ctx.addLast("ws-message", new WebSocketMessageDuplexer());
                    ctx.addLastDecoder("ws-event-tap", inboundEventTap(inbound));
                }, SoConfig.TCP());
                channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> {
                    if (d.getData() != null) {
                        inbound.offer(d.getData());
                    }
                });

                // handshake
                FullHttpRequest handshake = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
                channel.sendData(handshake, "ws-client").get();// "ws-client" is WebSocketClientHandshakeDuplexer target
                assertTrue(waitUntil(() -> WebSocketUtils.isReady(channel), 5000L));
                server.awaitOpen();

                // ping/pong
                channel.fireUserEvent(PingWebSocketEvent.class, WebSocketUtils.pingEvent(ascii("ping-manual")));
                PongWebSocketEvent pong = awaitInbound(inbound, PongWebSocketEvent.class, 5000L);
                assertEquals("ping-manual", pong.content().readString(pong.content().readableBytes(), StandardCharsets.US_ASCII));
                pong.release();

                // text Message
                channel.sendData(WebSocketUtils.textMessage(ascii("hello-real"))).get();
                server.awaitTextMessage();
                assertEquals("hello-real", server.receivedText());

                // socket Message
                WebSocketMessage ack = awaitInbound(inbound, WebSocketMessage.class, 5000L);
                assertTrue(ack instanceof TextWebSocketMessage);
                assertEquals("[Ack] hello-real", text(ack.content().retain()));
                ack.release();
            }
        });
    }

    @Test
    public void socketOnlyByAutoHandshakeClient() throws Throwable {
        autoCloseNeta(neta -> {
            int port = findFreePort();
            try (EmbeddedWebSocketServer server = new EmbeddedWebSocketServer(port)) {
                // server
                server.start();
                server.awaitStarted();

                //client
                Queue<Object> inbound = new ConcurrentLinkedQueue<>();
                NetChannel channel = neta.connectSync(new InetSocketAddress("127.0.0.1", port), ctx -> {
                    ctx.addLast("http-client", new HttpClientDuplexe());
                    ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13, new WebSocketAutoHandshakeConfig("/chat")));
                    ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    ctx.addLast("ws-message", new WebSocketMessageDuplexer());
                    ctx.addLastDecoder("ws-event-tap", inboundEventTap(inbound));
                }, SoConfig.TCP());
                channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> {
                    if (d.getData() != null) {
                        inbound.offer(d.getData());
                    }
                });

                // auto handshake
                assertTrue(waitUntil(() -> WebSocketUtils.isReady(channel), 5000L));
                server.awaitOpen();

                // ping/pong
                channel.fireUserEvent(PingWebSocketEvent.class, WebSocketUtils.pingEvent(ascii("ping-auto")));
                PongWebSocketEvent pong = awaitInbound(inbound, PongWebSocketEvent.class, 5000L);
                assertEquals("ping-auto", pong.content().readString(pong.content().readableBytes(), StandardCharsets.US_ASCII));
                pong.release();

                // text Message
                channel.sendData(WebSocketUtils.textMessage(ascii("hello-real"))).get();
                server.awaitTextMessage();
                assertEquals("hello-real", server.receivedText());

                // socket Message
                WebSocketMessage ack = awaitInbound(inbound, WebSocketMessage.class, 5000L);
                assertTrue(ack instanceof TextWebSocketMessage);
                assertEquals("[Ack] hello-real", text(ack.content().retain()));
                ack.release();
            }
        });
    }

    @Test
    public void mixByManualHandshakeClient() throws Throwable {
        autoCloseNeta(neta -> {
            int port = findFreePort();
            try (MixedHttpWebSocketPeerServer server = new MixedHttpWebSocketPeerServer(port)) {
                // server
                server.start();
                server.awaitStarted();

                //client
                Queue<Object> inbound = new ConcurrentLinkedQueue<>();
                NetChannel channel = neta.connectSync(new InetSocketAddress("127.0.0.1", port), ctx -> {
                    // 1st. http basic
                    ctx.addLast("http-client", new HttpClientDuplexe());
                    // 2st. distribution
                    ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsDefault(BRANCH_HTTP, branchCtx -> {
                        // - for HTTP
                        branchCtx.addLast("ws-over-http", new WebSocketClientUpgradeRouteDuplexer(WebSocketVersion.V13, HttpRouteKey.BRANCH_SOCKET));
                        branchCtx.addLastDecoder("resp-agg", new HttpResponseAggregator());
                    }).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
                        // - for WebSocket
                        branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                        branchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
                        branchCtx.addLastDecoder("ws-event-tap", inboundEventTap(inbound));
                    });
                    ctx.addLast("client-route", routing.build());
                }, SoConfig.TCP());
                channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> {
                    if (d.getData() != null) {
                        inbound.offer(d.getData());
                    }
                });

                // http request before websocket upgrade
                DefaultFullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/http-echo");
                httpRequest.setHeader(HttpHeaderNames.HOST, "127.0.0.1:" + port);
                httpRequest.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                channel.sendData(httpRequest, "http-client").get();

                FullHttpResponse httpResponse = awaitInbound(inbound, FullHttpResponse.class, 5000L);
                assertEquals(HttpStatus.OK, httpResponse.status());
                assertEquals("[HTTP] hello-http", text(httpResponse.content().retain()));
                httpResponse.release();
                server.awaitHttpRequest();
                assertEquals("GET", server.httpRequestMethod());
                assertEquals("/http-echo", server.httpRequestPath());

                // websocket upgrade on the same channel
                FullHttpRequest handshake = WebSocketUtils.createHandshake(WebSocketVersion.V13, "/chat");
                handshake.setHeader(HttpHeaderNames.HOST, "127.0.0.1:" + port);
                channel.sendData(handshake).get();
                assertTrue(waitUntil(() -> WebSocketUtils.isReady(channel), 5000L));
                server.awaitOpen();

                // ping/pong after upgrade on the same channel
                channel.fireUserEvent(PingWebSocketEvent.class, WebSocketUtils.pingEvent(ascii("ping-mixed")));
                PongWebSocketEvent pong = awaitInbound(inbound, PongWebSocketEvent.class, 5000L);
                assertEquals("ping-mixed", pong.content().readString(pong.content().readableBytes(), StandardCharsets.US_ASCII));
                pong.release();

                // text Message
                channel.sendData(WebSocketUtils.textMessage(ascii("hello-real"))).get();
                server.awaitTextMessage();
                assertEquals("hello-real", server.receivedText());

                // socket Message
                WebSocketMessage ack = awaitInbound(inbound, WebSocketMessage.class, 5000L);
                assertTrue(ack instanceof TextWebSocketMessage);
                assertEquals("[Ack] hello-real", text(ack.content().retain()));
                ack.release();
            }
        });
    }
}