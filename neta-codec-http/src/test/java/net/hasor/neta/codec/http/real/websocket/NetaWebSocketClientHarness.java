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
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import static org.junit.Assert.*;

public class NetaWebSocketClientHarness implements Closeable {
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final NetManager    neta;
    private final int           port;
    private final NetChannel    channel;
    private final Deque<byte[]> inboundChunks;
    private       byte[]        currentChunk;
    private       int           currentOffset;

    private NetaWebSocketClientHarness(NetManager neta, int port, NetChannel channel) {
        this.neta = neta;
        this.port = port;
        this.channel = channel;
        this.inboundChunks = new ArrayDeque<>();
        this.channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> {
            Object data = d.getData();
            if (!(data instanceof ByteBuf)) {
                return;
            }
            ByteBuf buf = (ByteBuf) data;
            byte[] copy = new byte[buf.readableBytes()];
            buf.getBytes(0, copy, 0, copy.length);
            synchronized (this.inboundChunks) {
                this.inboundChunks.addLast(copy);
            }
        });
    }

    public static NetaWebSocketClientHarness connectSocketOnly(int port) throws Exception {
        return connect(port);
    }

    public static NetaWebSocketClientHarness connectMixed(int port) throws Exception {
        return connect(port);
    }

    private static NetaWebSocketClientHarness connect(int port) throws Exception {
        NetManager neta = new NetManager();
        NetChannel channel = neta.connectSync(new InetSocketAddress("127.0.0.1", port), ctx -> {
        }, SoConfig.TCP());
        return new NetaWebSocketClientHarness(neta, port, channel);
    }

    public void handshake(String path, long timeoutMs) throws Exception {
        // @formatter:off
        String key = Base64.getEncoder().encodeToString(("neta-" + System.nanoTime()).getBytes(StandardCharsets.US_ASCII));
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + this.port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "\r\n";
        // @formatter:on
        this.sendAscii(request);

        HttpResponseView response = this.readHttpResponse(timeoutMs);
        assertEquals(101, response.statusCode());
        assertEquals("websocket", response.header("upgrade"));
        assertTrue(response.header("connection").toLowerCase().contains("upgrade"));
        assertEquals(computeAcceptKey(key), response.header("sec-websocket-accept"));
    }

    public void upgrade(String path, long timeoutMs) throws Exception {
        this.handshake(path, timeoutMs);
    }

    public HttpResponseView sendHttpGet(String path, long timeoutMs) throws Exception {
        // @formatter:off
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + this.port + "\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n";
        // @formatter:on
        this.sendAscii(request);
        return this.readHttpResponse(timeoutMs);
    }

    public void sendText(String text) throws Exception {
        this.sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    public void sendBinary(String text) throws Exception {
        this.sendFrame(0x2, text.getBytes(StandardCharsets.UTF_8));
    }

    public void sendPing(String text) throws Exception {
        this.sendFrame(0x9, text.getBytes(StandardCharsets.UTF_8));
    }

    public void sendPong(String text) throws Exception {
        this.sendFrame(0xA, text.getBytes(StandardCharsets.UTF_8));
    }

    public WebSocketFrameView awaitFrame(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int firstByte = this.readByte(deadline);
        int secondByte = this.readByte(deadline);
        int opcode = firstByte & 0x0F;
        int payloadLength = secondByte & 0x7F;
        if (payloadLength == 126) {
            byte[] extended = this.readExact(deadline, 2);
            payloadLength = ((extended[0] & 0xFF) << 8) | (extended[1] & 0xFF);
        } else if (payloadLength == 127) {
            throw new IOException("payload too large for test client");
        }

        if ((secondByte & 0x80) != 0) {
            this.readExact(deadline, 4);
        }
        return new WebSocketFrameView(opcode, this.readExact(deadline, payloadLength));
    }

    @Override
    public void close() throws IOException {
        this.channel.close().await();
        this.neta.shutdown();
    }

    private void sendAscii(String text) throws Exception {
        this.channel.sendData(ByteBuf.wrap(text.getBytes(StandardCharsets.US_ASCII))).get();
    }

    private void sendFrame(int opcode, byte[] payload) throws Exception {
        byte[] maskKey = new byte[] { 0x01, 0x23, 0x45, 0x67 };
        this.channel.sendData(ByteBuf.wrap(buildRfc6455Frame(opcode, true, true, maskKey, payload))).get();
    }

    private HttpResponseView readHttpResponse(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String headerText = this.readHttpHeader(deadline);
        String[] lines = headerText.split("\\r\\n");
        String[] statusLine = lines[0].split(" ", 3);
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
            if ("content-length".equals(name)) {
                contentLength = Integer.parseInt(value);
            }
        }

        byte[] body = contentLength > 0 ? this.readExact(deadline, contentLength) : new byte[0];
        return new HttpResponseView(Integer.parseInt(statusLine[1]), headers, new String(body, StandardCharsets.UTF_8));
    }

    private String readHttpHeader(long deadline) throws Exception {
        StringBuilder builder = new StringBuilder();
        int matched = 0;
        while (matched < 4) {
            int value = this.readByte(deadline);
            builder.append((char) value);
            if ((matched == 0 && value == '\r') || (matched == 2 && value == '\r')) {
                matched++;
            } else if ((matched == 1 && value == '\n') || (matched == 3 && value == '\n')) {
                matched++;
            } else {
                matched = (value == '\r') ? 1 : 0;
            }
        }
        return builder.toString();
    }

    private byte[] readExact(long deadline, int length) throws Exception {
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            result[i] = (byte) this.readByte(deadline);
        }
        return result;
    }

    private int readByte(long deadline) throws Exception {
        while (System.currentTimeMillis() < deadline) {
            if (this.currentChunk != null && this.currentOffset < this.currentChunk.length) {
                return this.currentChunk[this.currentOffset++] & 0xFF;
            }

            synchronized (this.inboundChunks) {
                this.currentChunk = this.inboundChunks.pollFirst();
            }
            this.currentOffset = 0;
            if (this.currentChunk != null) {
                continue;
            }
            Thread.sleep(10L);
        }
        fail("Timed out waiting for inbound bytes.");
        return -1;
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

    private static byte[] buildRfc6455Frame(int opcode, boolean fin, boolean masked, byte[] maskKey, byte[] payload) {
        int headerSize = 2;
        if (payload.length >= 126 && payload.length <= 65535) {
            headerSize += 2;
        } else if (payload.length > 65535) {
            headerSize += 8;
        }
        if (masked) {
            headerSize += 4;
        }

        byte[] frame = new byte[headerSize + payload.length];
        int writeIndex = 0;
        frame[writeIndex++] = (byte) ((fin ? 0x80 : 0x00) | (opcode & 0x0F));

        int lengthMarker = payload.length < 126 ? payload.length : (payload.length <= 65535 ? 126 : 127);
        frame[writeIndex++] = (byte) ((masked ? 0x80 : 0x00) | lengthMarker);
        if (lengthMarker == 126) {
            frame[writeIndex++] = (byte) ((payload.length >>> 8) & 0xFF);
            frame[writeIndex++] = (byte) (payload.length & 0xFF);
        } else if (lengthMarker == 127) {
            long length = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                frame[writeIndex++] = (byte) ((length >>> shift) & 0xFF);
            }
        }

        if (masked) {
            System.arraycopy(maskKey, 0, frame, writeIndex, 4);
            writeIndex += 4;
            for (int i = 0; i < payload.length; i++) {
                frame[writeIndex++] = (byte) (payload[i] ^ maskKey[i & 3]);
            }
        } else {
            System.arraycopy(payload, 0, frame, writeIndex, payload.length);
        }
        return frame;
    }

    public static final class HttpResponseView {
        private final int                 statusCode;
        private final Map<String, String> headers;
        private final String              body;

        private HttpResponseView(int statusCode, Map<String, String> headers, String body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
        }

        public int statusCode() {
            return this.statusCode;
        }

        public String body() {
            return this.body;
        }

        public String header(String name) {
            return this.headers.get(name);
        }
    }

    public static final class WebSocketFrameView {
        private final int    opcode;
        private final byte[] payload;

        private WebSocketFrameView(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }

        public int opcode() {
            return this.opcode;
        }

        public String text() {
            return new String(this.payload, StandardCharsets.UTF_8);
        }
    }
}