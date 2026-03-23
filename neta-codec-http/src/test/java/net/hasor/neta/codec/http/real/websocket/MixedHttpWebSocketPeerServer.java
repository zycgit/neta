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
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.neta.codec.http.HttpHeaderNames;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MixedHttpWebSocketPeerServer implements Closeable {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket            serverSocket;
    private final CountDownLatch          startLatch        = new CountDownLatch(1);
    private final CountDownLatch          httpRequestLatch  = new CountDownLatch(1);
    private final CountDownLatch          openLatch         = new CountDownLatch(1);
    private final CountDownLatch          textMessageLatch  = new CountDownLatch(1);
    private final AtomicReference<String> httpRequestMethod = new AtomicReference<>();
    private final AtomicReference<String> httpRequestPath   = new AtomicReference<>();
    private final AtomicReference<String> receivedText      = new AtomicReference<>();
    private final AtomicReference<Throwable> failure        = new AtomicReference<>();
    private final Thread                  worker;

    public MixedHttpWebSocketPeerServer(int port) throws IOException {
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        this.worker = new Thread(() -> {
            this.startLatch.countDown();
            try (Socket socket = this.serverSocket.accept()) {
                socket.setSoTimeout(5000);
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();

                ParsedHttpRequest httpRequest = readHttpRequest(input);
                this.httpRequestMethod.set(httpRequest.method);
                this.httpRequestPath.set(httpRequest.path);
                writeHttpResponse(output, "[HTTP] hello-http");
                this.httpRequestLatch.countDown();

                ParsedHttpRequest upgradeRequest = readHttpRequest(input);
                String webSocketKey = upgradeRequest.headers.get(HttpHeaderNames.SEC_WEBSOCKET_KEY);
                if (webSocketKey == null) {
                    throw new IOException("missing Sec-WebSocket-Key");
                }
                writeHandshakeResponse(output, webSocketKey);
                this.openLatch.countDown();

                String message = readMaskedTextFrame(input);
                this.receivedText.set(message);
                writeTextFrame(output, "[Ack] " + message);
                this.textMessageLatch.countDown();
            } catch (Throwable e) {
                this.failure.compareAndSet(null, e);
                this.httpRequestLatch.countDown();
                this.openLatch.countDown();
                this.textMessageLatch.countDown();
            } finally {
                try {
                    this.serverSocket.close();
                } catch (IOException e) {
                    this.failure.compareAndSet(null, e);
                }
            }
        }, "mixed-http-ws-peer");
        this.worker.setDaemon(true);
    }

    public void start() {
        this.worker.start();
    }

    public void awaitStarted() throws Exception {
        assertTrue(this.startLatch.await(5, TimeUnit.SECONDS));
    }

    public void awaitHttpRequest() throws Exception {
        assertTrue(this.httpRequestLatch.await(5, TimeUnit.SECONDS));
        assertNull(this.failure.get());
    }

    public void awaitOpen() throws Exception {
        assertTrue(this.openLatch.await(5, TimeUnit.SECONDS));
        assertNull(this.failure.get());
    }

    public void awaitTextMessage() throws Exception {
        assertTrue(this.textMessageLatch.await(5, TimeUnit.SECONDS));
        assertNull(this.failure.get());
    }

    public String httpRequestMethod() {
        return this.httpRequestMethod.get();
    }

    public String httpRequestPath() {
        return this.httpRequestPath.get();
    }

    public String receivedText() {
        return this.receivedText.get();
    }

    @Override
    public void close() {
        try {
            this.serverSocket.close();
        } catch (IOException e) {
            this.failure.compareAndSet(null, e);
        }
        try {
            this.worker.join(3000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (this.failure.get() != null) {
            throw new AssertionError(this.failure.get());
        }
    }

    private static ParsedHttpRequest readHttpRequest(InputStream input) throws IOException {
        ByteArrayOutputStream headerOut = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int value = input.read();
            if (value < 0) {
                throw new IOException("unexpected end of stream while reading headers");
            }
            headerOut.write(value);
            if ((matched == 0 && value == '\r') || (matched == 2 && value == '\r')) {
                matched++;
            } else if ((matched == 1 && value == '\n') || (matched == 3 && value == '\n')) {
                matched++;
            } else {
                matched = (value == '\r') ? 1 : 0;
            }
        }

        String headerText = new String(headerOut.toByteArray(), StandardCharsets.US_ASCII);
        String[] lines = headerText.split("\\r\\n");
        String[] requestLine = lines[0].split(" ");
        Map<String, String> headers = new LinkedHashMap<>();
        int contentLength = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int p = line.indexOf(':');
            if (p < 0) {
                continue;
            }
            String name = line.substring(0, p).trim().toLowerCase();
            String value = line.substring(p + 1).trim();
            headers.put(name, value);
            if (HttpHeaderNames.CONTENT_LENGTH.equals(name)) {
                contentLength = Integer.parseInt(value);
            }
        }

        if (contentLength > 0) {
            readExact(input, contentLength);
        }
        return new ParsedHttpRequest(requestLine[0], requestLine[1], headers);
    }

    private static void writeHttpResponse(OutputStream output, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String response = "HTTP/1.1 200 OK\r\n" + "Content-Type: text/plain\r\n" + "Content-Length: " + bodyBytes.length + "\r\n" + "Connection: keep-alive\r\n" + "\r\n";
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.write(bodyBytes);
        output.flush();
    }

    private static void writeHandshakeResponse(OutputStream output, String webSocketKey) throws IOException {
        String acceptKey = computeAcceptKey(webSocketKey);
        String response = "HTTP/1.1 101 Switching Protocols\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n" + "Sec-WebSocket-Accept: " + acceptKey + "\r\n" + "\r\n";
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static String computeAcceptKey(String webSocketKey) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((webSocketKey + WS_GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IOException("failed to compute Sec-WebSocket-Accept", e);
        }
    }

    private static String readMaskedTextFrame(InputStream input) throws IOException {
        int firstByte = input.read();
        int secondByte = input.read();
        if (firstByte < 0 || secondByte < 0) {
            throw new IOException("unexpected end of stream while reading websocket frame header");
        }
        int opcode = firstByte & 0x0F;
        if (opcode != 0x1) {
            throw new IOException("expected text frame but got opcode=" + opcode);
        }
        boolean masked = (secondByte & 0x80) != 0;
        if (!masked) {
            throw new IOException("client websocket frame must be masked");
        }

        int payloadLength = secondByte & 0x7F;
        if (payloadLength == 126) {
            byte[] extended = readExact(input, 2);
            payloadLength = ((extended[0] & 0xFF) << 8) | (extended[1] & 0xFF);
        } else if (payloadLength == 127) {
            throw new IOException("payload too large for test peer");
        }

        byte[] maskKey = readExact(input, 4);
        byte[] payload = readExact(input, payloadLength);
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (payload[i] ^ maskKey[i & 3]);
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static void writeTextFrame(OutputStream output, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        output.write(0x81);
        if (payload.length < 126) {
            output.write(payload.length);
        } else {
            output.write(126);
            output.write((payload.length >>> 8) & 0xFF);
            output.write(payload.length & 0xFF);
        }
        output.write(payload);
        output.flush();
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int readCount = input.read(data, offset, length - offset);
            if (readCount < 0) {
                throw new IOException("unexpected end of stream");
            }
            offset += readCount;
        }
        return data;
    }

    private static final class ParsedHttpRequest {
        private final String              method;
        private final String              path;
        private final Map<String, String> headers;

        private ParsedHttpRequest(String method, String path, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.headers = headers;
        }
    }
}