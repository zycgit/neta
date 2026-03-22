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
import java.io.Closeable;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.HttpClientDuplexe;
import net.hasor.neta.codec.http.routing.HttpRouteKey;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.Test;
import static org.junit.Assert.*;

public class RealAsClientTest extends AbstractWebSocketTest {
    private static final String BRANCH_HANDSHAKE = "handshake";
    private static final String BRANCH_WEBSOCKET = HttpRouteKey.BRANCH_SOCKET;

    private static final class EmbeddedWebSocketServer extends WebSocketServer implements Closeable {
        private final CountDownLatch             startLatch         = new CountDownLatch(1);
        private final CountDownLatch             openLatch          = new CountDownLatch(1);
        private final CountDownLatch             textMessageLatch   = new CountDownLatch(1);
        private final CountDownLatch             binaryMessageLatch = new CountDownLatch(1);
        private final AtomicReference<String>    receivedText       = new AtomicReference<>();
        private final AtomicReference<String>    receivedBinary     = new AtomicReference<>();
        private final AtomicReference<Throwable> failure            = new AtomicReference<>();

        private EmbeddedWebSocketServer(int port) {
            super(new InetSocketAddress("127.0.0.1", port));
            setReuseAddr(true);
        }

        public void awaitStarted() throws Exception {
            assertTrue(this.startLatch.await(5, TimeUnit.SECONDS));
        }

        public void awaitOpen() throws Exception {
            assertTrue(this.openLatch.await(5, TimeUnit.SECONDS));
            assertNull(this.failure.get());
        }

        public void awaitTextMessage() throws Exception {
            assertTrue(this.textMessageLatch.await(5, TimeUnit.SECONDS));
            assertNull(this.failure.get());
        }

        public void awaitBinaryMessage() throws Exception {
            assertTrue(this.binaryMessageLatch.await(5, TimeUnit.SECONDS));
            assertNull(this.failure.get());
        }

        public String receivedText() {
            return this.receivedText.get();
        }

        public String receivedBinary() {
            return this.receivedBinary.get();
        }

        public Throwable failure() {
            return this.failure.get();
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            this.openLatch.countDown();
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            this.receivedText.set(message);
            conn.send("[Ack] " + message);
            this.textMessageLatch.countDown();
        }

        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
            byte[] bytes = new byte[message.remaining()];
            message.get(bytes);
            this.receivedBinary.set(new String(bytes, java.nio.charset.StandardCharsets.US_ASCII));
            conn.send(bytes);
            this.binaryMessageLatch.countDown();
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            this.failure.compareAndSet(null, ex);
            this.startLatch.countDown();
            this.openLatch.countDown();
            this.textMessageLatch.countDown();
            this.binaryMessageLatch.countDown();
        }

        @Override
        public void onStart() {
            this.startLatch.countDown();
        }

        @Override
        public void close() {
            try {
                stop(1000);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final class NetaWebSocketClientHarness implements Closeable {
        private final NetChannel    channel;
        private final Queue<Object> inbound;

        private NetaWebSocketClientHarness(NetChannel channel, Queue<Object> inbound) {
            this.channel = channel;
            this.inbound = inbound;
        }

        public void open(String path) throws Exception {
            this.channel.sendData(WebSocketUtils.createHandshake(WebSocketVersion.V13, path)).get();
            assertTrue(waitUntil(() -> hasReadyContext(this.channel), 5000L));
        }

        public void sendText(String text) throws Exception {
            this.channel.sendData(WebSocketUtils.textMessage(ascii(text))).get();
        }

        public void sendBinary(String text) throws Exception {
            this.channel.sendData(WebSocketUtils.binaryMessage(ascii(text))).get();
        }

        public void sendPing(String text) throws Exception {
            this.channel.fireUserEvent(PingWebSocketEvent.class, WebSocketUtils.pingEvent(ascii(text)));
        }

        public <T> T awaitInbound(Class<T> targetType, long timeoutMs) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                Object msg = this.inbound.poll();
                if (targetType.isInstance(msg)) {
                    return targetType.cast(msg);
                }
                Thread.sleep(10L);
            }
            fail("Timed out waiting for inbound " + targetType.getSimpleName());
            return null;
        }

        @Override
        public void close() {
            this.channel.close().await();
        }
    }

    private static boolean hasReadyContext(SoChannel<?> channel) {
        WebSocketContext webSocketContext = channel.findProtoContext(WebSocketContext.class);
        return webSocketContext != null && webSocketContext.isReady();
    }

    private static NetaWebSocketClientHarness netaClient(NetManager neta, int port) throws Exception {
        Queue<Object> inbound = new ConcurrentLinkedQueue<>();
        NetChannel channel = neta.connectSync(new InetSocketAddress("127.0.0.1", port), ctx -> {
            ctx.addLast("http-client", new HttpClientDuplexe());
            ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsStatic((context, rcvUp, sndDown) -> BRANCH_HANDSHAKE);
            routing.branchByInitializer(BRANCH_HANDSHAKE, branchCtx -> {
                branchCtx.addLast("ws-client", new WebSocketHandshakeDuplexer(false, WebSocketVersion.V13));
            });
            routing.branchByInitializer(BRANCH_WEBSOCKET, branchCtx -> {
                branchCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                branchCtx.addLast("ws-message", new WebSocketMessageDuplexer());
                branchCtx.addLastDecoder("ws-event-tap", new ThroughProtoHandler<WebSocketMessage>() {
                    @Override
                    public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
                        if (event.getData() != null) {
                            inbound.offer(event.getData());
                        }
                        return true;
                    }
                });
            });
            ctx.addLast("ws-route", routing.build());
        }, SoConfig.TCP());

        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> {
            if (d.getData() != null) {
                inbound.offer(d.getData());
            }
        });
        return new NetaWebSocketClientHarness(channel, inbound);
    }

    @Test
    public void testNetaClientReceivesTextEchoFromEmbeddedWebSocketServer() throws Exception {
        int port = findFreePort();
        EmbeddedWebSocketServer server = new EmbeddedWebSocketServer(port);
        server.start();
        server.awaitStarted();

        NetManager neta = new NetManager();
        NetaWebSocketClientHarness client = netaClient(neta, port);
        try {
            client.open("/chat");
            server.awaitOpen();

            client.sendText("hello-real");
            server.awaitTextMessage();
            assertEquals("hello-real", server.receivedText());

            WebSocketMessage ack = client.awaitInbound(WebSocketMessage.class, 5000L);
            assertTrue(ack instanceof TextWebSocketMessage);
            assertEquals("[Ack] hello-real", text(ack.content().retain()));
            ack.release();
        } finally {
            client.close();
            neta.shutdown();
            server.close();
        }
    }

    @Test
    public void testNetaClientReceivesBinaryEchoFromEmbeddedWebSocketServer() throws Exception {
        int port = findFreePort();
        EmbeddedWebSocketServer server = new EmbeddedWebSocketServer(port);
        server.start();
        server.awaitStarted();

        NetManager neta = new NetManager();
        NetaWebSocketClientHarness client = netaClient(neta, port);
        try {
            client.open("/binary");
            server.awaitOpen();

            client.sendBinary("WXYZ");
            server.awaitBinaryMessage();
            assertEquals("WXYZ", server.receivedBinary());

            WebSocketMessage echo = client.awaitInbound(WebSocketMessage.class, 5000L);
            assertTrue(echo instanceof BinaryWebSocketMessage);
            assertEquals("WXYZ", text(echo.content().retain()));
            echo.release();
        } finally {
            client.close();
            neta.shutdown();
            server.close();
        }
    }

    @Test
    public void testNetaClientReceivesPongEventFromEmbeddedWebSocketServerAfterSendingPing() throws Exception {
        int port = findFreePort();
        EmbeddedWebSocketServer server = new EmbeddedWebSocketServer(port);
        server.start();
        server.awaitStarted();

        NetManager neta = new NetManager();
        NetaWebSocketClientHarness client = netaClient(neta, port);
        try {
            client.open("/ping");
            server.awaitOpen();

            client.sendPing("ping-real");

            PongWebSocketEvent pong = client.awaitInbound(PongWebSocketEvent.class, 5000L);
            assertNull(server.failure());
            assertEquals("ping-real", pong.content().readString(pong.content().readableBytes(), java.nio.charset.StandardCharsets.US_ASCII));
            pong.release();
        } finally {
            client.close();
            neta.shutdown();
            server.close();
        }
    }
}
