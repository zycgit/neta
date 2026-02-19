/*
 * WebSocket Example Test - validates the code examples from http_websocket.md
 */
package net.hasor.neta.example.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.constant.HttpMethod;
import net.hasor.neta.codec.http.constant.HttpStatus;
import net.hasor.neta.codec.http.constant.HttpVersion;
import net.hasor.neta.codec.http.websocket.DefaultWebSocketFrame;
import net.hasor.neta.codec.http.websocket.WebSocketFrame;
import net.hasor.neta.codec.http.websocket.WebSocketOpcode;
import net.hasor.neta.codec.http.websocket.WebSocketServerHandshaker;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests WebSocket examples from http_websocket.md documentation.
 * Uses raw Java Socket for WebSocket client to verify the handshake and frame exchange.
 */
public class HttpWebSocketExampleTest {

    private NetManager neta;
    private int        port;

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    @Before
    public void setUp() throws Exception {
        port = findFreePort();
        neta = new NetManager();
    }

    @After
    public void tearDown() throws IOException {
        if (neta != null) {
            neta.shutdown();
        }
    }

    /**
     * Test: WebSocket handshake (from http_websocket.md §服务端 WebSocket 实现)
     * Verifies:
     * - WebSocketServerHandshaker.isWebSocketUpgrade detects upgrade requests
     * - WebSocketServerHandshaker.handshakeResponse generates correct 101 response
     * - Sec-WebSocket-Accept key is correctly computed
     */
    @Test
    public void testWebSocketHandshake() throws Exception {
        // --- Server setup (from doc) ---
        ProtoInitializer httpProto = ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(65536));
        };

        neta.bind(new InetSocketAddress("0.0.0.0", port), httpProto, SoConfig.TCP());

        neta.subscribe(PlayLoad::isInbound, (payload) -> {
            Object msg = payload.getData();

            if (msg instanceof FullHttpRequest) {
                FullHttpRequest request = (FullHttpRequest) msg;

                // from doc: check if WebSocket upgrade
                if (WebSocketServerHandshaker.isWebSocketUpgrade(request)) {
                    // from doc: generate handshake response
                    FullHttpResponse handshakeResp = WebSocketServerHandshaker.handshakeResponse(request);
                    ((NetChannel) payload.getSource()).sendData(handshakeResp);
                    return;
                }

                // fallback for non-WebSocket requests
                ByteBuf body = ByteBufAllocator.DEFAULT.buffer(32);
                body.writeString("Not a WebSocket request", StandardCharsets.UTF_8);
                body.markWriter();
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.BAD_REQUEST, body);
                response.headers().set("Content-Length", String.valueOf(body.readableBytes()));
                ((NetChannel) payload.getSource()).sendData(response);
            }
        });

        Thread.sleep(200);

        // --- Client: send WebSocket upgrade via raw socket ---
        String wsKey = "dGhlIHNhbXBsZSBub25jZQ==";
        String expectedAccept = WebSocketServerHandshaker.computeAcceptKey(wsKey);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));

            // Send HTTP upgrade request (from doc §握手请求格式)
            String upgradeRequest = "GET /chat HTTP/1.1\r\n" + "Host: 127.0.0.1:" + port + "\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n" + "Sec-WebSocket-Key: " + wsKey + "\r\n" + "Sec-WebSocket-Version: 13\r\n" + "\r\n";
            out.write(upgradeRequest.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Read response
            String statusLine = reader.readLine();
            assertNotNull("Should receive a response", statusLine);
            assertTrue("Should be 101 Switching Protocols, got: " + statusLine, statusLine.contains("101"));

            // Read headers
            String acceptKey = null;
            String upgradeHeader = null;
            String connectionHeader = null;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase();
                if (lower.startsWith("sec-websocket-accept:")) {
                    acceptKey = line.substring(line.indexOf(':') + 1).trim();
                } else if (lower.startsWith("upgrade:")) {
                    upgradeHeader = line.substring(line.indexOf(':') + 1).trim();
                } else if (lower.startsWith("connection:")) {
                    connectionHeader = line.substring(line.indexOf(':') + 1).trim();
                }
            }

            // Verify handshake response (from doc §握手响应格式)
            assertEquals("Sec-WebSocket-Accept must match", expectedAccept, acceptKey);
            assertEquals("websocket", upgradeHeader.toLowerCase());
            assertTrue("Connection must contain Upgrade", connectionHeader.toLowerCase().contains("upgrade"));
        }
    }

    /**
     * Test: WebSocketServerHandshaker.computeAcceptKey (from doc §计算 Accept Key)
     * RFC 6455 §4.2.2: Base64(SHA-1(key + GUID))
     */
    @Test
    public void testComputeAcceptKey() throws Exception {
        // from doc: known test vector
        String key = "dGhlIHNhbXBsZSBub25jZQ==";
        String accept = WebSocketServerHandshaker.computeAcceptKey(key);
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", accept);
    }

    /**
     * Test: WebSocketServerHandshaker.isWebSocketUpgrade (from doc §验证升级请求)
     */
    @Test
    public void testIsWebSocketUpgrade() {
        // Valid upgrade request (from doc)
        DefaultFullHttpRequest valid = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/chat");
        valid.headers().set("Upgrade", "websocket");
        valid.headers().set("Connection", "Upgrade");
        valid.headers().set("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
        valid.headers().set("Sec-WebSocket-Version", "13");
        assertTrue(WebSocketServerHandshaker.isWebSocketUpgrade(valid));

        // Missing Sec-WebSocket-Key → not a valid upgrade
        DefaultFullHttpRequest invalid = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/chat");
        invalid.headers().set("Upgrade", "websocket");
        invalid.headers().set("Connection", "Upgrade");
        // no Sec-WebSocket-Key
        invalid.headers().set("Sec-WebSocket-Version", "13");
        assertFalse(WebSocketServerHandshaker.isWebSocketUpgrade(invalid));
    }

    /**
     * Test: Sub-protocol support (from doc §子协议支持)
     */
    @Test
    public void testSubProtocolEcho() {
        // from doc: request with Sec-WebSocket-Protocol
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/chat");
        request.headers().set("Upgrade", "websocket");
        request.headers().set("Connection", "Upgrade");
        request.headers().set("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
        request.headers().set("Sec-WebSocket-Version", "13");
        request.headers().set("Sec-WebSocket-Protocol", "chat, superchat");

        FullHttpResponse response = WebSocketServerHandshaker.handshakeResponse(request);
        // from doc: "response.headers().get('Sec-WebSocket-Protocol') == 'chat, superchat'"
        assertEquals("chat, superchat", response.headers().get("Sec-WebSocket-Protocol"));
    }

    /**
     * Test: DefaultWebSocketFrame factory methods (from doc §创建帧)
     */
    @Test
    public void testFrameFactoryMethods() {
        // Text frame (from doc)
        WebSocketFrame textFrame = DefaultWebSocketFrame.text("Hello, WebSocket!");
        assertEquals(WebSocketOpcode.TEXT, textFrame.opcode());
        assertTrue(textFrame.isFinalFragment());

        // Binary frame (from doc)
        byte[] data = new byte[] { 0x01, 0x02, 0x03 };
        WebSocketFrame binaryFrame = DefaultWebSocketFrame.binary(data);
        assertEquals(WebSocketOpcode.BINARY, binaryFrame.opcode());
        assertEquals(3, binaryFrame.content().readableBytes());

        // Ping frame (from doc)
        WebSocketFrame pingFrame = DefaultWebSocketFrame.ping();
        assertEquals(WebSocketOpcode.PING, pingFrame.opcode());

        // Pong frame (from doc)
        WebSocketFrame pongFrame = DefaultWebSocketFrame.pong();
        assertEquals(WebSocketOpcode.PONG, pongFrame.opcode());

        // Close frame (from doc)
        WebSocketFrame closeFrame = DefaultWebSocketFrame.close(1000, "Normal closure");
        assertEquals(WebSocketOpcode.CLOSE, closeFrame.opcode());
    }

    /**
     * Test: WebSocketOpcode enum (from doc §帧操作码)
     */
    @Test
    public void testWebSocketOpcodes() {
        assertEquals(0x0, WebSocketOpcode.CONTINUATION.code());
        assertEquals(0x1, WebSocketOpcode.TEXT.code());
        assertEquals(0x2, WebSocketOpcode.BINARY.code());
        assertEquals(0x8, WebSocketOpcode.CLOSE.code());
        assertEquals(0x9, WebSocketOpcode.PING.code());
        assertEquals(0xA, WebSocketOpcode.PONG.code());

        assertEquals(WebSocketOpcode.TEXT, WebSocketOpcode.of(0x1));
        assertEquals(WebSocketOpcode.CLOSE, WebSocketOpcode.of(0x8));
    }

    // -- helpers --

    /**
     * Test: Custom frame construction (from doc §自定义帧)
     */
    @Test
    public void testCustomFrameConstruction() {
        // from doc: create a masked text frame
        byte[] maskKey = new byte[] { 0x12, 0x34, 0x56, 0x78 };
        ByteBuf content = ByteBufAllocator.DEFAULT.buffer(32);
        content.writeString("Masked message", StandardCharsets.UTF_8);
        content.markWriter();

        WebSocketFrame frame = new DefaultWebSocketFrame(WebSocketOpcode.TEXT,  // opcode
                true,                  // FIN
                true,                  // masked
                maskKey,               // masking key (4 bytes)
                content                // payload
        );

        assertEquals(WebSocketOpcode.TEXT, frame.opcode());
        assertTrue(frame.isFinalFragment());
        assertTrue(frame.isMasked());
        assertArrayEquals(maskKey, frame.maskingKey());
    }
}
