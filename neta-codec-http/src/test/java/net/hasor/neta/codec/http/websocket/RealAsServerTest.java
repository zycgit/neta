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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpServerDuplexe;
import net.hasor.neta.codec.http.routing.HttpRouteKey;
import okhttp3.*;
import okio.ByteString;
import org.junit.Test;
import static org.junit.Assert.*;

public class RealAsServerTest extends AbstractWebSocketTest {
    private static final String BRANCH_HANDSHAKE = "handshake";
    private static final String BRANCH_WEBSOCKET = HttpRouteKey.BRANCH_SOCKET;

    private ProtoInitializer buildServerProto() {
        return buildServerProto(null, null);
    }

    private ProtoInitializer buildServerProto(CountDownLatch pongLatch, AtomicReference<String> pongPayload) {
        return ctx -> {
            ctx.addLast("http-server", new HttpServerDuplexe());
            ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsStatic((context, rcvUp, sndDown) -> {
                return BRANCH_HANDSHAKE;
            });
            routing.branchByInitializer(BRANCH_HANDSHAKE, branchCtx -> {
                branchCtx.addLast("ws-handshake", new WebSocketHandshakeDuplexer(true, WebSocketVersion.V13));
            });
            routing.branchByInitializer(BRANCH_WEBSOCKET, branchCtx -> {
                branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                branchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
                branchCtx.addLastDecoder("ws-events", new ThroughProtoHandler<WebSocketMessage>() {
                    @Override
                    public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
                        if (pongLatch != null && event.getData() instanceof PongWebSocketEvent) {
                            PongWebSocketEvent pongEvent = (PongWebSocketEvent) event.getData();
                            pongPayload.set(pongEvent.content().readString(pongEvent.content().readableBytes(), java.nio.charset.StandardCharsets.US_ASCII));
                            pongLatch.countDown();
                            pongEvent.release();
                        }
                        return true;
                    }
                });
                branchCtx.addLastDecoder("ws-echo", (ProtoHandler<WebSocketMessage, Object>) (context, src, dst) -> {
                    while (src.hasMore()) {
                        WebSocketMessage message = src.takeMessage();
                        if (message == null) {
                            continue;
                        }
                        try {
                            if (message instanceof TextWebSocketMessage) {
                                if (pongLatch != null) {
                                    context.fireUserEventSnd(PingWebSocketEvent.class, WebSocketUtils.pingEvent(ascii("ping-okhttp")));
                                } else {
                                    context.sendData(WebSocketUtils.textMessage(ascii("[Echo] " + text(message.content().retain())))).get();
                                }
                            } else if (message instanceof BinaryWebSocketMessage) {
                                context.sendData(WebSocketUtils.binaryMessage(message.content().retain())).get();
                            }
                        } finally {
                            message.release();
                        }
                    }
                    return ProtoStatus.Next;
                });
            });
            ctx.addLast("ws-route", routing.build());
        };
    }

    private static OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()//
                .connectTimeout(5, TimeUnit.SECONDS)//
                .readTimeout(5, TimeUnit.SECONDS)   //
                .writeTimeout(5, TimeUnit.SECONDS)  //
                .build();
    }

    @Test
    public void testNetaServerEchoesTextMessageToOkHttpWebSocketClient() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        OkHttpClient client = okHttpClient();
        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        AtomicReference<String> echoedText = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        neta.bind(new InetSocketAddress("127.0.0.1", port), buildServerProto(), SoConfig.TCP());
        try {
            Request request = new Request.Builder().url("ws://127.0.0.1:" + port + "/chat").build();
            WebSocket webSocket = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    openLatch.countDown();
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    echoedText.set(text);
                    messageLatch.countDown();
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    failure.set(t);
                    openLatch.countDown();
                    messageLatch.countDown();
                }
            });

            assertTrue(openLatch.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(webSocket.send("hello-neta"));
            assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals("[Echo] hello-neta", echoedText.get());
            webSocket.cancel();
        } finally {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
            neta.shutdown();
        }
    }

    @Test
    public void testNetaServerEchoesBinaryMessageToOkHttpWebSocketClient() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        OkHttpClient client = okHttpClient();
        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        AtomicReference<ByteString> echoedBinary = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        neta.bind(new InetSocketAddress("127.0.0.1", port), buildServerProto(), SoConfig.TCP());
        try {
            Request request = new Request.Builder().url("ws://127.0.0.1:" + port + "/chat").build();
            WebSocket webSocket = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    openLatch.countDown();
                }

                @Override
                public void onMessage(WebSocket webSocket, ByteString bytes) {
                    echoedBinary.set(bytes);
                    messageLatch.countDown();
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    failure.set(t);
                    openLatch.countDown();
                    messageLatch.countDown();
                }
            });

            assertTrue(openLatch.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(webSocket.send(ByteString.encodeUtf8("ABCD")));
            assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals("ABCD", echoedBinary.get().utf8());
            webSocket.cancel();
        } finally {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
            neta.shutdown();
        }
    }

    @Test
    public void testNetaServerReceivesPongEventFromOkHttpClientAfterSendingPing() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        OkHttpClient client = okHttpClient();
        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch pongLatch = new CountDownLatch(1);
        AtomicReference<String> pongPayload = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        neta.bind(new InetSocketAddress("127.0.0.1", port), buildServerProto(pongLatch, pongPayload), SoConfig.TCP());
        try {
            Request request = new Request.Builder().url("ws://127.0.0.1:" + port + "/chat").build();
            WebSocket webSocket = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    openLatch.countDown();
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    failure.set(t);
                    openLatch.countDown();
                    pongLatch.countDown();
                }
            });

            assertTrue(openLatch.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(webSocket.send("trigger-ping"));
            assertTrue(pongLatch.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals("ping-okhttp", pongPayload.get());
            webSocket.cancel();
        } finally {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
            neta.shutdown();
        }
    }
}
