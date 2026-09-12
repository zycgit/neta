/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import okhttp3.*;

/**
 * End-to-end WebSocket echo test using OkHttp as the WebSocket client.
 * Validates that {@link NetaHttpServer}'s WebSocket pipeline correctly handles
 * upgrade, frame encoding/decoding, and echo message delivery.
 */
public class WebSocketEchoTest {

    private NetaHttpServer server;
    private OkHttpClient   client;
    private int            port;

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    @Before
    public void setUp() throws Exception {
        port = findFreePort();
        server = new NetaHttpServer();
        client = new OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).build();
    }

    @After
    public void tearDown() {
        if (client != null) {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
        if (server != null) {
            server.stop();
        }
    }

    /**
     * Test: WebSocket echo via NetaHttpServer.
     * 1. Server registers echo handler at /ws/echo
     * 2. Client connects via OkHttp WebSocket
     * 3. Server sends "Welcome!" on open
     * 4. Client sends "Hello, Neta!" → server echoes "[Echo] Hello, Neta!"
     * 5. Verify both messages received in order
     */
    @Test
    public void testWebSocketEcho() throws Exception {
        // --- Server setup ---
        server.addWebSocket("/ws/echo", new WebSocketHandler() {
            @Override
            public void onOpen(WebSocketSession session) {
                try {
                    session.sendText("Welcome!");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onMessage(WebSocketSession session, String message) {
                try {
                    session.sendText("[Echo] " + message);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        server.start(port);
        Thread.sleep(300); // allow server to settle

        // --- Client setup ---
        Request request = new Request.Builder().url("ws://127.0.0.1:" + port + "/ws/echo").build();

        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(2); // "Welcome!" + "[Echo] Hello, Neta!"
        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                openLatch.countDown();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                receivedMessages.add(text);
                messageLatch.countDown();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                failure.set(t);
                openLatch.countDown();
                messageLatch.countDown();
                messageLatch.countDown();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                // normal close
            }
        });

        // --- Wait for connection to open ---
        assertTrue("WebSocket should open within 5s", openLatch.await(5, TimeUnit.SECONDS));
        assertNull("No failure should occur on open: " + failure.get(), failure.get());

        // --- Send a message ---
        ws.send("Hello, Neta!");

        // --- Wait for both messages ---
        assertTrue("Should receive 2 messages within 5s", messageLatch.await(5, TimeUnit.SECONDS));
        assertNull("No failure should occur: " + failure.get(), failure.get());

        // --- Verify messages ---
        assertEquals("Should have received exactly 2 messages", 2, receivedMessages.size());
        assertEquals("First message should be welcome", "Welcome!", receivedMessages.get(0));
        assertEquals("Second message should be echo", "[Echo] Hello, Neta!", receivedMessages.get(1));

        // --- Cleanup ---
        ws.close(1000, "test done");
    }

    /**
     * Test: Multiple echo messages.
     */
    @Test
    public void testWebSocketMultipleMessages() throws Exception {
        server.addWebSocket("/ws/echo", new WebSocketHandler() {
            @Override
            public void onMessage(WebSocketSession session, String message) {
                try {
                    session.sendText(message); // simple echo without prefix
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        server.start(port);
        Thread.sleep(300);

        Request request = new Request.Builder().url("ws://127.0.0.1:" + port + "/ws/echo").build();

        int messageCount = 5;
        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(messageCount);
        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                openLatch.countDown();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                receivedMessages.add(text);
                messageLatch.countDown();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                failure.set(t);
                openLatch.countDown();
                for (int i = 0; i < messageCount; i++) {
                    messageLatch.countDown();
                }
            }
        });

        assertTrue("WebSocket should open", openLatch.await(5, TimeUnit.SECONDS));
        assertNull("No failure on open: " + failure.get(), failure.get());

        // Send multiple messages
        for (int i = 0; i < messageCount; i++) {
            ws.send("msg-" + i);
        }

        assertTrue("Should receive all messages within 5s", messageLatch.await(5, TimeUnit.SECONDS));
        assertNull("No failure: " + failure.get(), failure.get());

        assertEquals(messageCount, receivedMessages.size());
        for (int i = 0; i < messageCount; i++) {
            assertEquals("msg-" + i, receivedMessages.get(i));
        }

        ws.close(1000, "test done");
    }

    /**
     * Test: WebSocket close handler is invoked.
     */
    @Test
    public void testWebSocketClose() throws Exception {
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        AtomicReference<Integer> closeCode = new AtomicReference<>();
        AtomicReference<String> closeReason = new AtomicReference<>();

        server.addWebSocket("/ws/echo", new WebSocketHandler() {
            @Override
            public void onClose(WebSocketSession session, int statusCode, String reason) {
                closeCode.set(statusCode);
                closeReason.set(reason);
                serverCloseLatch.countDown();
            }
        });
        server.start(port);
        Thread.sleep(300);

        Request request = new Request.Builder().url("ws://127.0.0.1:" + port + "/ws/echo").build();

        CountDownLatch openLatch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                openLatch.countDown();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                failure.set(t);
                openLatch.countDown();
            }
        });

        assertTrue("WebSocket should open", openLatch.await(5, TimeUnit.SECONDS));
        assertNull("No failure: " + failure.get(), failure.get());

        // Send close frame
        ws.close(1000, "bye");

        // Wait for server to receive close
        assertTrue("Server should receive close within 5s", serverCloseLatch.await(5, TimeUnit.SECONDS));
        assertEquals(Integer.valueOf(1000), closeCode.get());
        assertEquals("bye", closeReason.get());
    }

    /**
     * Diagnostic test: uses a raw socket to inspect the exact bytes sent by the server
     * after a WebSocket upgrade. This helps debug frame encoding issues.
     */
    @Test
    public void testRawSocketWebSocketDiagnostic() throws Exception {
        CountDownLatch onOpenCalled = new CountDownLatch(1);
        CountDownLatch onMessageCalled = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        server.addWebSocket("/ws/echo", new WebSocketHandler() {
            @Override
            public void onOpen(WebSocketSession session) {
                try {
                    session.sendText("Hi");
                    onOpenCalled.countDown();
                } catch (Throwable e) {
                    serverError.set(e);
                    onOpenCalled.countDown();
                }
            }

            @Override
            public void onMessage(WebSocketSession session, String message) {
                try {
                    session.sendText(message);
                    onMessageCalled.countDown();
                } catch (Throwable e) {
                    serverError.set(e);
                    onMessageCalled.countDown();
                }
            }
        });
        server.start(port);
        Thread.sleep(300);

        // Use raw socket for WebSocket handshake
        String wsKey = "dGhlIHNhbXBsZSBub25jZQ==";
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Send HTTP upgrade request
            String upgradeRequest = "GET /ws/echo HTTP/1.1\r\n" + "Host: 127.0.0.1:" + port + "\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n" + "Sec-WebSocket-Key: " + wsKey + "\r\n" + "Sec-WebSocket-Version: 13\r\n" + "\r\n";
            out.write(upgradeRequest.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Read the HTTP response headers
            StringBuilder responseHeaders = new StringBuilder();
            int prev = -1;
            int crlfCount = 0;
            while (crlfCount < 2) {
                int b = in.read();
                if (b < 0)
                    break;
                responseHeaders.append((char) b);
                if (b == '\n' && prev == '\r') {
                    crlfCount++;
                } else if (b != '\r') {
                    crlfCount = 0;
                }
                prev = b;
            }

            String headers = responseHeaders.toString();
            assertTrue("Should be 101", headers.contains("101"));

            // Wait for server onOpen callback (which sends welcome frame)
            assertTrue("Server onOpen should be called", onOpenCalled.await(3, TimeUnit.SECONDS));
            assertNull("No server error in onOpen: " + serverError.get(), serverError.get());
            Thread.sleep(500); // allow server to send welcome frame

            // Read welcome frame
            int available = in.available();
            assertTrue("Should receive welcome frame bytes", available > 0);

            byte[] data = new byte[Math.min(available, 256)];
            int read = in.read(data, 0, data.length);

            // Parse and verify welcome frame
            assertTrue("Welcome frame should have at least 2 bytes", read >= 2);
            int byte0 = data[0] & 0xFF;
            int byte1 = data[1] & 0xFF;
            boolean fin = (byte0 & 0x80) != 0;
            int opcode = byte0 & 0x0F;
            boolean masked = (byte1 & 0x80) != 0;
            int payloadLen = byte1 & 0x7F;
            assertTrue("Welcome frame should have FIN set", fin);
            assertEquals("Welcome frame should be TEXT (opcode=1)", 1, opcode);
            assertFalse("Server frames should not be masked", masked);
            assertEquals("Welcome payload should be 'Hi' (2 bytes)", 2, payloadLen);

            String payload = new String(data, 2, payloadLen, StandardCharsets.UTF_8);
            assertEquals("Hi", payload);

            // Send a masked WebSocket text frame to test echo
            byte[] echoPayload = "test".getBytes(StandardCharsets.UTF_8);
            byte[] mask = { 0x12, 0x34, 0x56, 0x78 };
            byte[] echoFrame = new byte[2 + 4 + echoPayload.length];
            echoFrame[0] = (byte) 0x81; // FIN + TEXT
            echoFrame[1] = (byte) (0x80 | echoPayload.length); // MASK + length
            System.arraycopy(mask, 0, echoFrame, 2, 4);
            for (int i = 0; i < echoPayload.length; i++) {
                echoFrame[6 + i] = (byte) (echoPayload[i] ^ mask[i % 4]);
            }
            out.write(echoFrame);
            out.flush();

            // Wait for echo response
            assertTrue("Server onMessage should be called", onMessageCalled.await(3, TimeUnit.SECONDS));
            assertNull("No server error in onMessage: " + serverError.get(), serverError.get());
            Thread.sleep(500);

            // Read echo response frame
            int echoAvailable = in.available();
            assertTrue("Should receive echo response bytes", echoAvailable > 0);

            byte[] echoResp = new byte[Math.min(echoAvailable, 256)];
            int echoRead = in.read(echoResp, 0, echoResp.length);

            assertTrue("Echo frame should have at least 2 bytes", echoRead >= 2);
            int eByte1 = echoResp[1] & 0xFF;
            int ePayloadLen = eByte1 & 0x7F;
            assertTrue("Echo payload should fit in frame", ePayloadLen > 0 && ePayloadLen <= echoRead - 2);
            String echoResult = new String(echoResp, 2, ePayloadLen, StandardCharsets.UTF_8);
            assertEquals("test", echoResult);
        }
    }
}
