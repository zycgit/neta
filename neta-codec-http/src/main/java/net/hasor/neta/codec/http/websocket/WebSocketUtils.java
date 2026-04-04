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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.cookie.Cookie;
import net.hasor.neta.codec.http.cookie.CookieDecoder;
import net.hasor.neta.codec.http.cookie.CookieEncoder;
import net.hasor.neta.codec.http.websocket.extension.WebSocketExtensionResult;
import net.hasor.neta.codec.http.websocket.extension.WebSocketExtensionSupport;
import net.hasor.neta.codec.http.websocket.extension.WebSocketRuntimeExtension;

/**
 * Utility entry point for WebSocket frames, messages, events, and handshake helpers.
 * <p>
 * Provides the common factory and convenience methods used by tests and normal pipeline code.
 */
public final class WebSocketUtils {
    private static final String DEFAULT_HANDSHAKE_HOST   = "localhost";
    private static final String DEFAULT_HANDSHAKE_ORIGIN = "http://localhost";
    private static final String WS_CLOSE_SENT_KEY        = "neta.websocket.close.sent";
    private static final String WS_CLOSE_RECEIVED_KEY    = "neta.websocket.close.received";

    private WebSocketUtils() {
    }

    static List<WebSocketExtensionResult> parseExtensions(String extensions) {
        if (StringUtils.isBlank(extensions)) {
            return Collections.emptyList();
        }
        return WebSocketExtensionResult.parse(extensions);
    }

    static List<WebSocketExtensionResult> parseExtensions(List<String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return Collections.emptyList();
        }

        List<WebSocketExtensionResult> results = new ArrayList<>(extensions.size());
        for (String extension : extensions) {
            if (StringUtils.isBlank(extension)) {
                continue;
            }
            results.addAll(WebSocketExtensionResult.parse(extension));
        }
        return results.isEmpty() ? Collections.emptyList() : results;
    }

    static List<WebSocketRuntimeExtension> resolveRuntimeExtensions(List<WebSocketExtensionResult> extensionResults, WebSocketSettings settings) {
        if (settings == null || extensionResults == null || extensionResults.isEmpty()) {
            return Collections.emptyList();
        }

        List<WebSocketRuntimeExtension> runtimeExtensions = new ArrayList<>(extensionResults.size());
        for (WebSocketExtensionResult result : extensionResults) {
            WebSocketRuntimeExtension runtimeExtension = null;
            for (WebSocketExtensionSupport support : settings.extensionSupports()) {
                if (support == null || !StringUtils.equalsIgnoreCase(support.extensionName(), result.name())) {
                    continue;
                }

                WebSocketExtensionResult negotiated = support.parseNegotiatedExtension(result.asHeaderValue());
                if (negotiated == null) {
                    break;
                }

                runtimeExtension = support.createRuntimeExtension(negotiated);
                break;
            }
            if (runtimeExtension != null) {
                runtimeExtensions.add(runtimeExtension);
            }
        }
        return runtimeExtensions.isEmpty() ? Collections.emptyList() : runtimeExtensions;
    }

    /** Returns {@code true} when the channel contains a ready WebSocket context. */
    public static boolean isReady(SoChannel<?> channel) {
        return channel != null && isReady(channel.findProtoContext(WebSocketContext.class));
    }

    /** Returns {@code true} when the protocol context can resolve a ready WebSocket context. */
    public static boolean isReady(ProtoContext context) {
        if (context == null) {
            return false;
        }

        WebSocketContext webSocketContext = context.context(WebSocketContext.class);
        if (webSocketContext == null) {
            webSocketContext = context.rootContext(WebSocketContext.class);
        }

        return isReady(webSocketContext);
    }

    /** Returns {@code true} when the WebSocket context exists and the opening handshake is complete. */
    public static boolean isReady(WebSocketContext context) {
        return context != null && context.isReady();
    }

    /** Validates a control frame using server-side close-code semantics by default. */
    public static void validateControlFrame(WebSocketFrame frame) {
        validateControlFrame(frame, false);
    }

    /** Validates a control frame and applies role-sensitive close-code rules based on whether the sender is a client. */
    public static void validateControlFrame(WebSocketFrame frame, boolean senderIsClient) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }

        WebSocketOpcode opcode = frame.opcode();
        if (opcode == null) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "WebSocket frame opcode must not be null.");
        }

        if (!frame.isFinalFragment()) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "control frames must not be fragmented.");
        }

        validateControlPayload(opcode, frame.content(), senderIsClient);
    }

    static void validateControlPayload(WebSocketOpcode opcode, ByteBuf content, boolean senderIsClient) {
        if (content != null && content.readableBytes() > 125) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "control frame payload must not exceed 125 bytes.");
        }

        if (opcode == WebSocketOpcode.CLOSE) {
            validateClosePayload(content, senderIsClient);
        }
    }

    static void validateClosePayload(ByteBuf content, boolean senderIsClient) {
        if (content == null || content.readableBytes() == 0) {
            return;
        }
        if (content.readableBytes() == 1) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "close frame payload must be either empty or at least 2 bytes.");
        }

        int statusCode = ((content.getByte(0) & 0xFF) << 8) | (content.getByte(1) & 0xFF);
        validateCloseStatusCode(statusCode, senderIsClient);

        int reasonLen = content.readableBytes() - 2;
        if (reasonLen <= 0) {
            return;
        }

        byte[] reasonBytes = new byte[reasonLen];
        content.getBytes(2, reasonBytes, 0, reasonLen);
        decodeUtf8(reasonBytes, "close frame reason must be valid UTF-8.");
    }

    static void validateCloseStatusCode(int statusCode, boolean senderIsClient) {
        if (statusCode < WebSocketCode.NORMAL_CLOSURE || statusCode >= 5000) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "close frame status code is invalid: " + statusCode);
        }
        if (statusCode == WebSocketCode.NO_STATUS || statusCode == WebSocketCode.ABNORMAL_CLOSURE) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "close frame status code is invalid: " + statusCode);
        }
        if (statusCode == WebSocketCode.MANDATORY_EXTENSION) {
            if (!senderIsClient) {
                throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "close frame status code is invalid: " + statusCode);
            }
            return;
        }
        if (statusCode == WebSocketCode.RESERVED || statusCode == 1012 || statusCode == 1013 || statusCode == 1014 || statusCode == WebSocketCode.TLS_HANDSHAKE) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "close frame status code is invalid: " + statusCode);
        }

        if (!(statusCode < 1016 || statusCode >= 3000)) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "close frame status code is invalid: " + statusCode);
        }
    }

    static String decodeUtf8(byte[] bytes, String errorMessage) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT);

        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new WebSocketProtocolViolationException(WebSocketCode.INVALID_DATA, errorMessage, e);
        }
    }

    static boolean hasCloseSent(ProtoContext context) {
        return hasChannelFlag(context, WS_CLOSE_SENT_KEY);
    }

    static void markCloseSent(ProtoContext context) {
        setChannelFlag(context, WS_CLOSE_SENT_KEY, true);
    }

    static boolean hasCloseReceived(ProtoContext context) {
        return hasChannelFlag(context, WS_CLOSE_RECEIVED_KEY);
    }

    static void markCloseReceived(ProtoContext context) {
        setChannelFlag(context, WS_CLOSE_RECEIVED_KEY, true);
    }

    static void clearCloseState(ProtoContext context) {
        setChannelFlag(context, WS_CLOSE_SENT_KEY, false);
        setChannelFlag(context, WS_CLOSE_RECEIVED_KEY, false);
    }

    static void closeChannelAfterSend(ProtoContext context, Future<?> future) {
        if (context == null || context.getChannel() == null) {
            return;
        }
        if (future == null) {
            context.getChannel().close();
            return;
        }

        future.onFinal(f -> context.getChannel().close());
    }

    private static boolean hasChannelFlag(ProtoContext context, String key) {
        if (context == null || context.getChannel() == null) {
            return false;
        }

        return Boolean.TRUE.equals(context.getChannel().getAttribute(key));
    }

    private static void setChannelFlag(ProtoContext context, String key, boolean value) {
        if (context == null || context.getChannel() == null) {
            return;
        }

        context.getChannel().setAttribute(key, value ? Boolean.TRUE : null);
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