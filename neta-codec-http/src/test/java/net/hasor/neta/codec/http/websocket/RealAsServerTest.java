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
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.ProtoRoutingBuilder;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.real.websocket.NetaWebSocketClientHarness;
import net.hasor.neta.codec.http.real.websocket.NetaWebSocketClientHarness.HttpResponseView;
import net.hasor.neta.codec.http.routing.HttpRouteKey;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class RealAsServerTest extends AbstractWebSocketTest {
    private static final String BRANCH_HTTP = "http";
    private static final String WS_PATH     = "/chat";

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

    private ProtoHandler<WebSocketMessage, WebSocketMessage> serverEventTap(Queue<Object> serverEvents) {
        return new ProtoHandler<WebSocketMessage, WebSocketMessage>() {
            @Override
            public boolean onEvent(ProtoContext context, SoEvent event) {
                if (event.getData() instanceof WebSocketHandshakeEvent || event.getData() instanceof PongWebSocketEvent) {
                    serverEvents.offer(event.getData());
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

    private ThroughProtoHandler<HttpObject> handshakeEventTap(Queue<Object> serverEvents) {
        return new ThroughProtoHandler<HttpObject>() {
            @Override
            public boolean onEvent(ProtoContext context, SoEvent event) {
                if (event.getData() instanceof WebSocketHandshakeEvent) {
                    serverEvents.offer(event.getData());
                }
                return true;
            }
        };
    }

    @Test
    public void websocketOnlyServerAcceptsClientTextBinaryAndPingPong() throws Throwable {
        autoCloseNeta(neta -> {
            int port = findFreePort();
            Queue<Object> serverEvents = new ConcurrentLinkedQueue<>();

            neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> {
                ctx.addLast("http-server", new HttpServerDuplexe());
                ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
                ctx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                ctx.addLast("ws-message", new WebSocketMessageDuplexer());
                ctx.addLastDecoder("ws-events", serverEventTap(serverEvents));
            }, SoConfig.TCP());

            neta.subscribe(PlayLoad::isInbound, payload -> {
                Object data = payload.getData();
                if (!(data instanceof WebSocketMessage)) {
                    return;
                }

                WebSocketMessage message = (WebSocketMessage) data;
                try {
                    if (message instanceof TextWebSocketMessage) {
                        String content = text(message.content().copy());
                        ((NetChannel) payload.getSource()).sendData(WebSocketUtils.textMessage(ascii("[Ack] " + content)));
                    } else if (message instanceof BinaryWebSocketMessage) {
                        ((NetChannel) payload.getSource()).sendData(WebSocketUtils.binaryMessage(message.content().copy()));
                    }
                } finally {
                    message.release();
                }
            });

            try (NetaWebSocketClientHarness client = NetaWebSocketClientHarness.connectSocketOnly(port)) {
                client.handshake(WS_PATH, 5000L);
                awaitInbound(serverEvents, WebSocketHandshakeEvent.class, 5000L);

                client.sendText("hello-real");
                NetaWebSocketClientHarness.WebSocketFrameView ack = client.awaitFrame(5000L);
                assertEquals(0x1, ack.opcode());
                assertEquals("[Ack] hello-real", ack.text());

                client.sendBinary("ABCD");
                NetaWebSocketClientHarness.WebSocketFrameView binaryAck = client.awaitFrame(5000L);
                assertEquals(0x2, binaryAck.opcode());
                assertEquals("ABCD", binaryAck.text());

                client.sendPing("ping-from-client");
                NetaWebSocketClientHarness.WebSocketFrameView pongAck = client.awaitFrame(5000L);
                assertEquals(0xA, pongAck.opcode());
                assertEquals("ping-from-client", pongAck.text());

                client.sendPong("pong-from-client");
                PongWebSocketEvent serverPong = awaitInbound(serverEvents, PongWebSocketEvent.class, 5000L);
                assertEquals("pong-from-client", serverPong.content().readString(serverPong.content().readableBytes(), StandardCharsets.US_ASCII));
                serverPong.release();
            }
        });
    }

    @Test
    public void websocketMixServerAcceptsClientTextBinaryAndPingPongAfterUpgrade() throws Throwable {
        autoCloseNeta(neta -> {
            int port = findFreePort();
            Queue<Object> serverEvents = new ConcurrentLinkedQueue<>();

            neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> {
                ctx.addLast("http-server", new HttpServerDuplexe());
                final ProtoRoutingControl[] routingControl = new ProtoRoutingControl[1];
                ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsDefault(BRANCH_HTTP, branchCtx -> {
                    branchCtx.addLast("ws-upgrade", new WebSocketServerUpgradeRouteDuplexer(routingControl[0], WebSocketVersion.V13, HttpRouteKey.BRANCH_SOCKET));
                    branchCtx.addLastDecoder("ws-handshake-events", handshakeEventTap(serverEvents));
                    branchCtx.addLastDecoder("http-agg", new HttpRequestAggregator(1024 * 1024));
                }).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
                    branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                    branchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
                    branchCtx.addLastDecoder("ws-events", serverEventTap(serverEvents));
                });
                routingControl[0] = routing.control();
                ctx.addLast("server-route", routing.build());
            }, SoConfig.TCP());

            neta.subscribe(PlayLoad::isInbound, payload -> {
                Object data = payload.getData();
                if (data instanceof FullHttpRequest) {
                    FullHttpRequest request = (FullHttpRequest) data;
                    try {
                        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.OK, ascii("[HTTP] hello-http"));
                        response.streamId(request.streamId());
                        response.setHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                        response.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(response.content().readableBytes()));
                        ((NetChannel) payload.getSource()).sendData(response);
                    } finally {
                        request.release();
                    }
                    return;
                }

                if (!(data instanceof WebSocketMessage)) {
                    return;
                }

                WebSocketMessage message = (WebSocketMessage) data;
                try {
                    if (message instanceof TextWebSocketMessage) {
                        String content = text(message.content().copy());
                        ((NetChannel) payload.getSource()).sendData(WebSocketUtils.textMessage(ascii("[Ack] " + content)));
                    } else if (message instanceof BinaryWebSocketMessage) {
                        ((NetChannel) payload.getSource()).sendData(WebSocketUtils.binaryMessage(message.content().copy()));
                    }
                } finally {
                    message.release();
                }
            });

            try (NetaWebSocketClientHarness client = NetaWebSocketClientHarness.connectMixed(port)) {
                HttpResponseView httpResponse = client.sendHttpGet("/http-echo", 5000L);
                assertEquals(200, httpResponse.statusCode());
                assertEquals("[HTTP] hello-http", httpResponse.body());

                client.upgrade(WS_PATH, 5000L);

                client.sendText("hello-real");
                NetaWebSocketClientHarness.WebSocketFrameView ack = client.awaitFrame(5000L);
                assertEquals(0x1, ack.opcode());
                assertEquals("[Ack] hello-real", ack.text());

                client.sendBinary("ABCD");
                NetaWebSocketClientHarness.WebSocketFrameView binaryAck = client.awaitFrame(5000L);
                assertEquals(0x2, binaryAck.opcode());
                assertEquals("ABCD", binaryAck.text());

                client.sendPing("ping-from-client");
                NetaWebSocketClientHarness.WebSocketFrameView pongAck = client.awaitFrame(5000L);
                assertEquals(0xA, pongAck.opcode());
                assertEquals("ping-from-client", pongAck.text());

                client.sendPong("pong-from-client");
                PongWebSocketEvent serverPong = awaitInbound(serverEvents, PongWebSocketEvent.class, 5000L);
                assertEquals("pong-from-client", serverPong.content().readString(serverPong.content().readableBytes(), StandardCharsets.US_ASCII));
                serverPong.release();
            }
        });
    }
}
