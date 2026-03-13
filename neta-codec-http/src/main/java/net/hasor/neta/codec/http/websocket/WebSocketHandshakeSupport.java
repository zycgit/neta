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

final class WebSocketHandshakeSupport {
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocketHandshakeSupport() {
    }

    static WebSocketVersion detectVersion(FullHttpRequest request) {
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

        String key1 = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY1);
        String key2 = request.getString(HttpHeaderNames.SEC_WEBSOCKET_KEY2);
        if (StringUtils.isNotBlank(key1) && StringUtils.isNotBlank(key2)) {
            return WebSocketVersion.V0;
        }

        return null;
    }

    static FullHttpResponse handshakeResponse(FullHttpRequest request) {
        WebSocketVersion version = detectVersion(request);
        if (version == null) {
            throw new IllegalArgumentException("Not a valid WebSocket upgrade request");
        }
        return version == WebSocketVersion.V0 ? handshakeResponseV0(request) : handshakeResponseRfc(request);
    }

    private static FullHttpResponse handshakeResponseRfc(FullHttpRequest request) {
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

    private static String computeAcceptKey(String key) {
        String combined = key + WEBSOCKET_GUID;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(combined.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 algorithm not available", e);
        }
    }

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

    private static byte[] computeHixie76Response(String key1, String key2, byte[] key3) {
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