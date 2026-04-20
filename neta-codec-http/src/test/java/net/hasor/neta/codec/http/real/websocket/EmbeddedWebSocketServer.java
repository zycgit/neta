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
package net.hasor.neta.codec.http.real.websocket;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

public class EmbeddedWebSocketServer extends WebSocketServer implements Closeable {
    private final CountDownLatch             startLatch         = new CountDownLatch(1);
    private final CountDownLatch             openLatch          = new CountDownLatch(1);
    private final CountDownLatch             textMessageLatch   = new CountDownLatch(1);
    private final CountDownLatch             binaryMessageLatch = new CountDownLatch(1);
    private final AtomicReference<String>    receivedText       = new AtomicReference<>();
    private final AtomicReference<String>    receivedBinary     = new AtomicReference<>();
    private final AtomicReference<Throwable> failure            = new AtomicReference<>();

    public EmbeddedWebSocketServer(int port) {
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
        this.receivedBinary.set(new String(bytes, StandardCharsets.US_ASCII));
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