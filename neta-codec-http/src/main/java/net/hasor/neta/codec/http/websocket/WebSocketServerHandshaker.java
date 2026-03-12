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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.codec.http.*;

/**
 * Low-level utility for performing the WebSocket server-side opening handshake.
 * <p>
 * Most pipelines should prefer {@link WebSocketServerDuplexer}, which wraps this
 * logic into a stateful bidirectional handshake stage placed before the frame codec.
 * <p>
 * Supports all WebSocket protocol versions:
 * <ul>
 *   <li><b>V0</b> (Hixie-76 / hybi-00): MD5-based challenge–response using
 *       {@code Sec-WebSocket-Key1}, {@code Sec-WebSocket-Key2}, and an 8-byte body.</li>
 *   <li><b>V7</b> (hybi-07), <b>V8</b> (hybi-08/10), <b>V13</b> (RFC 6455):
 *       SHA-1/GUID–based {@code Sec-WebSocket-Accept} computation.</li>
 * </ul>
 */
public final class WebSocketServerHandshaker {
    /**
     * The concatenation magic string defined in
     * <a href="https://tools.ietf.org/html/rfc6455#section-1.3">RFC 6455 §1.3</a>.
     */
    public static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocketServerHandshaker() {
    }

    /**
     * Detects the WebSocket version from an HTTP upgrade request.
     * @param request the HTTP request to inspect
     * @return the detected version, or {@code null} if it is not a WebSocket upgrade
     */
    public static WebSocketVersion detectVersion(FullHttpRequest request) {
        if (request == null) {
            return null;
        }
        String upgrade = request.getString(HttpHeaderNames.UPGRADE);
        String connection = request.getString(HttpHeaderNames.CONNECTION);

        if (!StringUtils.containsIgnoreCase(connection, HttpHeaderValues.UPGRADE)) {
            return null;
        }
        if (!StringUtils.equalsIgnoreCase(HttpHeaderValues.WEBSOCKET, upgrade) && !StringUtils.equalsIgnoreCase("WebSocket", upgrade)) {
            return null;
        }

        String wsVersion = request.getString(HttpHeaderNames.SEC_WEBSOCKET_VERSION);
        if (StringUtils.isNotBlank(wsVersion)) {
            return WebSocketVersion.of(wsVersion.trim());
        }

        // No Sec-WebSocket-Version header — check for Hixie-76 keys
        String key1 = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY1);
        String key2 = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY2);
        if (StringUtils.isNotBlank(key1) && StringUtils.isNotBlank(key2)) {
            return WebSocketVersion.V0;
        }

        return null;
    }

    /**
     * Validates the incoming HTTP upgrade request and returns true if it looks like
     * a valid WebSocket handshake request (any supported version).
     * @param request the HTTP request to validate
     * @return {@code true} if the request is a WebSocket upgrade request
     */
    public static boolean isWebSocketUpgrade(FullHttpRequest request) {
        return detectVersion(request) != null;
    }

    /**
     * Builds the HTTP 101 response for a WebSocket upgrade request.
     * Automatically detects the version and delegates to the appropriate handshake logic.
     * @param request the client's upgrade request
     * @return the 101 response ready to send
     * @throws IllegalArgumentException if the request is not a valid WebSocket upgrade
     */
    public static FullHttpResponse handshakeResponse(FullHttpRequest request) {
        WebSocketVersion version = detectVersion(request);
        if (version == null) {
            throw new IllegalArgumentException("Not a valid WebSocket upgrade request");
        }
        if (version == WebSocketVersion.V0) {
            return handshakeResponseV0(request);
        } else {
            return handshakeResponseRfc(request, version);
        }
    }

    // =========================================================================
    // RFC 6455 / hybi handshake (V7, V8, V13)
    // =========================================================================

    private static FullHttpResponse handshakeResponseRfc(FullHttpRequest request, WebSocketVersion version) {
        String wsKey = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY);
        if (StringUtils.isBlank(wsKey)) {
            throw new IllegalArgumentException("Missing Sec-WebSocket-Key header");
        }

        String acceptKey = computeAcceptKey(wsKey.trim());

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.SWITCHING_PROTOCOLS);
        response.setHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
        response.setHeader(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT, acceptKey);

        String subProtocol = request.getString(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        if (StringUtils.isNotBlank(subProtocol)) {
            response.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, subProtocol);
        }

        return response;
    }

    /**
     * Computes the {@code Sec-WebSocket-Accept} value as per RFC 6455 §4.2.2:
     * <pre>accept = Base64( SHA-1( key + GUID ) )</pre>
     */
    public static String computeAcceptKey(String key) {
        String combined = key + WEBSOCKET_GUID;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(combined.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 algorithm not available", e);
        }
    }

    // =========================================================================
    // Hixie-76 handshake (V0)
    // =========================================================================

    private static FullHttpResponse handshakeResponseV0(FullHttpRequest request) {
        String key1 = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY1);
        String key2 = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY2);
        if (StringUtils.isBlank(key1) || StringUtils.isBlank(key2)) {
            throw new IllegalArgumentException("Missing Sec-WebSocket-Key1 or Sec-WebSocket-Key2 header");
        }

        ByteBuf body = request.content();
        if (body == null || body.readableBytes() < 8) {
            throw new IllegalArgumentException("Hixie-76 handshake requires 8-byte body");
        }
        byte[] key3 = new byte[8];
        body.getBytes(0, key3, 0, 8);

        byte[] challengeResponse = computeHixie76Response(key1, key2, key3);

        // Build the 16-byte MD5 response body
        ByteBuf bodyBuf = ByteBufAllocator.DEFAULT.buffer(16, Integer.MAX_VALUE);
        bodyBuf.writeBytes(challengeResponse, 0, challengeResponse.length);
        bodyBuf.markWriter();

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.SWITCHING_PROTOCOLS, bodyBuf);
        response.setHeader(HttpHeaderNames.UPGRADE, "WebSocket");
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);

        String origin = request.getString("origin");
        if (StringUtils.isNotBlank(origin)) {
            response.setHeader(HttpHeaderNames.SEC_WEBSOCKET_ORIGIN, origin);
        }

        String host = request.getString(HttpHeaderNames.HOST);
        String uri = request.uri();
        if (StringUtils.isNotBlank(host)) {
            response.setHeader(HttpHeaderNames.SEC_WEBSOCKET_LOCATION, "ws://" + host + (uri != null ? uri : "/"));
        }

        String subProtocol = request.getString(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        if (StringUtils.isNotBlank(subProtocol)) {
            response.setHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, subProtocol);
        }

        return response;
    }

    /**
     * Computes the Hixie-76 challenge response:
     * <ol>
     *   <li>Extract digits from key1, divide by number of spaces → 32-bit int1</li>
     *   <li>Extract digits from key2, divide by number of spaces → 32-bit int2</li>
     *   <li>MD5( big-endian(int1) + big-endian(int2) + key3 )</li>
     * </ol>
     */
    public static byte[] computeHixie76Response(String key1, String key2, byte[] key3) {
        long num1 = extractDigits(key1);
        int spaces1 = countSpaces(key1);
        long num2 = extractDigits(key2);
        int spaces2 = countSpaces(key2);

        if (spaces1 == 0 || spaces2 == 0) {
            throw new IllegalArgumentException("Invalid Sec-WebSocket-Key: no spaces found");
        }

        int part1 = (int) (num1 / spaces1);
        int part2 = (int) (num2 / spaces2);

        byte[] challenge = new byte[16];
        challenge[0] = (byte) ((part1 >> 24) & 0xFF);
        challenge[1] = (byte) ((part1 >> 16) & 0xFF);
        challenge[2] = (byte) ((part1 >> 8) & 0xFF);
        challenge[3] = (byte) (part1 & 0xFF);
        challenge[4] = (byte) ((part2 >> 24) & 0xFF);
        challenge[5] = (byte) ((part2 >> 16) & 0xFF);
        challenge[6] = (byte) ((part2 >> 8) & 0xFF);
        challenge[7] = (byte) (part2 & 0xFF);
        System.arraycopy(key3, 0, challenge, 8, 8);

        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            return md5.digest(challenge);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }

    private static long extractDigits(String key) {
        long result = 0;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c >= '0' && c <= '9') {
                result = result * 10 + (c - '0');
            }
        }
        return result;
    }

    private static int countSpaces(String key) {
        int count = 0;
        for (int i = 0; i < key.length(); i++) {
            if (key.charAt(i) == ' ') {
                count++;
            }
        }
        return count;
    }
}
