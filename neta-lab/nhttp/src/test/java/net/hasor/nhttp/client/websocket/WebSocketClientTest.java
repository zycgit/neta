/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.client.websocket;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.hasor.cobble.concurrent.future.Future;
import net.hasor.nhttp.client.ClientTestSupport;
import net.hasor.nhttp.client.HttpClient;
import net.hasor.nhttp.client.WebSocket;
import net.hasor.nhttp.client.WebSocketListener;
import net.hasor.nhttp.request.Request;
import net.hasor.nhttp.server.NetaHttpServer;
import net.hasor.nhttp.server.WebSocketHandler;
import net.hasor.nhttp.server.WebSocketSession;

/**
 * WebSocket integration tests for the new client API.
 */
public class WebSocketClientTest extends ClientTestSupport {
    private NetaHttpServer server;
    private HttpClient     client;
    private int            port;

    @Before
    public void setUp() throws Exception {
        this.port = findFreePort();
        this.server = new NetaHttpServer();
    }

    @After
    public void tearDown() throws Exception {
        if (this.client != null) {
            this.client.close();
        }
        if (this.server != null) {
            this.server.stop();
        }
    }

    @Test
    public void testOpenSendReceiveAndClose() throws Exception {
        this.server.addWebSocket("/ws/echo", new WebSocketHandler() {
            @Override
            public void onMessage(WebSocketSession session, String message) {
                try {
                    session.sendText("echo:" + message);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        this.server.start(this.port);
        waitForServer();

        this.client = HttpClient.newBuilder().connectTimeout(5_000).readTimeout(5_000).writeTimeout(5_000).webSocketOpenTimeout(5_000).callTimeout(5_000).build();

        CountDownLatch messageLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        List<String> messages = new CopyOnWriteArrayList<String>();
        AtomicReference<Throwable> errorRef = new AtomicReference<Throwable>();

        Request request = new Request.Builder().url("ws://127.0.0.1:" + this.port + "/ws/echo").get().build();
        Future<WebSocket> future = this.client.openWebSocket(request, new WebSocketListener() {
            @Override
            public void onText(WebSocket webSocket, String message) {
                messages.add(message);
                messageLatch.countDown();
            }

            @Override
            public void onClosed(WebSocket webSocket, int statusCode, String reason) {
                closeLatch.countDown();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable error) {
                errorRef.set(error);
                while (messageLatch.getCount() > 0) {
                    messageLatch.countDown();
                }
                closeLatch.countDown();
            }
        });

        WebSocket webSocket = future.get(5, TimeUnit.SECONDS);
        assertTrue(webSocket.isOpen());
        assertEquals(request, webSocket.request());

        webSocket.sendText("hello");
        assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, messages.size());
        assertEquals("echo:hello", messages.get(0));

        webSocket.close(1000, "test done");
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
        assertFalse(webSocket.isOpen());
        assertNull(String.valueOf(errorRef.get()), errorRef.get());
    }
}
