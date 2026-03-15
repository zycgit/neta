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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.cookie.Cookie;
import net.hasor.neta.codec.http.cookie.CookieDecoder;
import net.hasor.neta.codec.http.cookie.CookieEncoder;

/**
 * Utility methods for creating WebSocket frames, messages, events, and client handshake requests.
 * <p>
 * This class intentionally exposes two public construction levels for frame objects:
 * <ul>
 *   <li>Simple overloads for common business-side frame creation.</li>
 *   <li>Complete overloads for frame-level code that must control FIN, masking, and payload details explicitly.</li>
 * </ul>
 */
public final class WebSocketUtils {
    private static final String DEFAULT_HANDSHAKE_HOST   = "localhost";
    private static final String DEFAULT_HANDSHAKE_ORIGIN = "http://localhost";

    private WebSocketUtils() {
    }

    /** Creates a client opening-handshake request for the requested websocket version and applies custom headers and cookies. */
    public static FullHttpRequest createHandshake(WebSocketVersion version, String uri, HttpHeaders headers, Cookie... cookies) {
        FullHttpRequest request = createHandshake(version, uri);
        List<Cookie> mergedCookies = new ArrayList<>();

        if (headers != null && headers.headerSize() > 0) {
            for (String name : headers.headerNames()) {
                if (StringUtils.equalsIgnoreCase(name, HttpHeaderNames.COOKIE)) {
                    for (String value : headers.getValues(name)) {
                        mergedCookies.addAll(CookieDecoder.decode(value));
                    }
                    continue;
                }

                request.removeHeader(name);
                for (String value : headers.getValues(name)) {
                    request.addHeader(name, value);
                }
            }
        }

        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie != null) {
                    mergedCookies.add(cookie);
                }
            }
        }

        request.removeHeader(HttpHeaderNames.COOKIE);
        if (!mergedCookies.isEmpty()) {
            request.setHeader(HttpHeaderNames.COOKIE, CookieEncoder.encode(mergedCookies));
        }
        return request;
    }

    /** Creates a client opening-handshake request for the requested websocket version. */
    public static FullHttpRequest createHandshake(WebSocketVersion version, String uri) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }

        if (version == WebSocketVersion.V0) {
            String key1 = randomHixie76Key();
            String key2 = randomHixie76Key();
            byte[] key3 = RandomUtils.nextBytes(8);
            ByteBuf body = ByteBufAllocator.DEFAULT.buffer(key3.length, Integer.MAX_VALUE);
            body.writeBytes(key3, 0, key3.length);
            body.markWriter();

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri, body);
            request.setHeader(HttpHeaderNames.HOST, DEFAULT_HANDSHAKE_HOST);
            request.setHeader(HttpHeaderNames.UPGRADE, "WebSocket");
            request.setHeader(HttpHeaderNames.CONNECTION, "Upgrade");
            request.setHeader(HttpHeaderNames.ORIGIN, DEFAULT_HANDSHAKE_ORIGIN);
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_KEY1, key1);
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_KEY2, key2);
            request.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(key3.length));
            return request;
        } else {
            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
            request.setHeader(HttpHeaderNames.HOST, DEFAULT_HANDSHAKE_HOST);
            request.setHeader(HttpHeaderNames.UPGRADE, "websocket");
            request.setHeader(HttpHeaderNames.CONNECTION, "Upgrade");
            request.setHeader(HttpHeaderNames.ORIGIN, DEFAULT_HANDSHAKE_ORIGIN);
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_KEY, randomRfc6455Key());
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_VERSION, String.valueOf(version.code()));
            request.setHeader(HttpHeaderNames.CONTENT_LENGTH, "0");
            return request;
        }
    }

    private static String randomRfc6455Key() {
        return Base64.getEncoder().encodeToString(RandomUtils.nextBytes(16));
    }

    private static String randomHixie76Key() {
        int spaces = RandomUtils.nextInt(1, 12);
        int part = RandomUtils.nextInt(1, Integer.MAX_VALUE / spaces);
        long product = (long) part * spaces;
        StringBuilder builder = new StringBuilder(Long.toString(product));

        int noiseCount = RandomUtils.nextInt(1, 12);
        for (int i = 0; i < noiseCount; i++) {
            builder.insert(randomInsertIndex(builder.length()), randomVisibleNonDigitChar());
        }
        for (int i = 0; i < spaces; i++) {
            builder.insert(randomInsertIndex(builder.length()), ' ');
        }
        return builder.toString();
    }

    private static int randomInsertIndex(int length) {
        return length <= 0 ? 0 : RandomUtils.nextInt(0, length);
    }

    private static char randomVisibleNonDigitChar() {
        while (true) {
            int ascii = RandomUtils.nextInt(0x21, 0x7E);
            if (ascii < '0' || ascii > '9') {
                return (char) ascii;
            }
        }
    }

    //

    public static WebSocketFrame textFrame(String text) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(text.length() * 3, Integer.MAX_VALUE);
        buf.writeString(text, StandardCharsets.UTF_8);
        buf.markWriter();
        return textFrame(true, false, null, buf);
    }

    public static WebSocketFrame textFrame(boolean finalFragment, boolean masked, byte[] maskingKey, ByteBuf content) {
        return WebSocketFrame.create(WebSocketOpcode.TEXT, finalFragment, masked, maskingKey, content);
    }

    public static WebSocketFrame binaryFrame(byte[] data) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(data.length, Integer.MAX_VALUE);
        buf.writeBytes(data, 0, data.length);
        buf.markWriter();
        return binaryFrame(true, false, null, buf);
    }

    public static WebSocketFrame binaryFrame(boolean finalFragment, boolean masked, byte[] maskingKey, ByteBuf content) {
        return WebSocketFrame.create(WebSocketOpcode.BINARY, finalFragment, masked, maskingKey, content);
    }

    public static WebSocketFrame pingFrame() {
        return WebSocketFrame.create(WebSocketOpcode.PING, true, false, null, ByteBuf.EMPTY);
    }

    public static WebSocketFrame pingFrame(boolean masked, byte[] maskingKey, ByteBuf content) {
        return WebSocketFrame.create(WebSocketOpcode.PING, true, masked, maskingKey, content);
    }

    public static WebSocketFrame pongFrame() {
        return WebSocketFrame.create(WebSocketOpcode.PONG, true, false, null, ByteBuf.EMPTY);
    }

    public static WebSocketFrame pongFrame(boolean masked, byte[] maskingKey, ByteBuf content) {
        return WebSocketFrame.create(WebSocketOpcode.PONG, true, masked, maskingKey, content);
    }

    public static WebSocketFrame closeFrame(int statusCode, String reason) {
        String reasonStr = reason != null ? reason : "";
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(2 + reasonStr.length() * 3, Integer.MAX_VALUE);
        buf.writeByte((byte) ((statusCode >> 8) & 0xFF));
        buf.writeByte((byte) (statusCode & 0xFF));
        if (!reasonStr.isEmpty()) {
            buf.writeString(reasonStr, StandardCharsets.UTF_8);
        }
        buf.markWriter();
        return WebSocketFrame.create(WebSocketOpcode.CLOSE, true, false, null, buf);
    }

    public static WebSocketFrame closeFrame(boolean masked, byte[] maskingKey, ByteBuf content) {
        return WebSocketFrame.create(WebSocketOpcode.CLOSE, true, masked, maskingKey, content);
    }

    public static WebSocketFrame continuationFrame(boolean finalFragment, boolean masked, byte[] maskingKey, ByteBuf content) {
        return WebSocketFrame.create(WebSocketOpcode.CONTINUATION, finalFragment, masked, maskingKey, content);
    }

    //

    public static TextWebSocketMessage textMessage(ByteBuf content) {
        return textMessage(WebSocketMessage.FINAL_SEQUENCE, content);
    }

    public static TextWebSocketMessage textMessage(int sequence, ByteBuf content) {
        return TextWebSocketMessage.request(sequence, content);
    }

    public static BinaryWebSocketMessage binaryMessage(ByteBuf content) {
        return binaryMessage(WebSocketMessage.FINAL_SEQUENCE, content);
    }

    public static BinaryWebSocketMessage binaryMessage(int sequence, ByteBuf content) {
        return BinaryWebSocketMessage.request(sequence, content);
    }

    public static PingWebSocketEvent pingEvent() {
        return new PingWebSocketEvent();
    }

    public static PingWebSocketEvent pingEvent(ByteBuf content) {
        return new PingWebSocketEvent(content);
    }

    public static PongWebSocketEvent pongEvent() {
        return new PongWebSocketEvent();
    }

    public static PongWebSocketEvent pongEvent(ByteBuf content) {
        return new PongWebSocketEvent(content);
    }
}
