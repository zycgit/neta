/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.cookie.Cookie;
import net.hasor.neta.codec.http.cookie.CookieDecoder;
import net.hasor.neta.codec.http.cookie.CookieEncoder;

/**
 * Factory and validation helpers shared across the websocket codec pipeline.
 * <p>
 * This utility centralizes handshake-request creation, frame and message
 * factories, control-frame validation, runtime-extension resolution, and close
 * state tracking helpers.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-15
 */
public final class WebSocketUtils {
    private static final Base64.Encoder                    BASE64_ENCODER         = Base64.getEncoder();
    private static final ThreadLocal<byte[]>               RFC6455_KEY_BYTES      = ThreadLocal.withInitial(() -> new byte[16]);
    private static final ThreadLocal<HandshakeTargetCache> HANDSHAKE_TARGET_CACHE = ThreadLocal.withInitial(HandshakeTargetCache::new);

    private static final class HandshakeTarget {
        private final String requestUri;
        private final String hostHeader;
        private final String originHeader;
        private final String schemeHeader;

        private HandshakeTarget(String requestUri, String hostHeader, String originHeader, String schemeHeader) {
            this.requestUri = requestUri;
            this.hostHeader = hostHeader;
            this.originHeader = originHeader;
            this.schemeHeader = schemeHeader;
        }
    }

    private static final class HandshakeTargetCache {
        private String          uri;
        private HandshakeTarget target;
    }

    private WebSocketUtils() {
    }

    /**
     * Return the ready websocket context from the current or root protocol context.
     * @param context protocol context that may carry websocket state
     * @return ready websocket context, or {@code null} when the handshake is incomplete
     */
    public static WebSocketContext readyContext(ProtoContext context) {
        if (context == null) {
            return null;
        }

        WebSocketContext localContext = context.context(WebSocketContext.class);
        if (localContext != null && localContext.isReady()) {
            return localContext;
        }

        WebSocketContext rootContext = context.rootContext(WebSocketContext.class);
        if (rootContext != null && rootContext.isReady()) {
            return rootContext;
        }

        return WebSocketRegistry.resolve(context);
    }

    /**
     * Determine whether the ready websocket context contains negotiated extensions.
     * @param context protocol context that may carry websocket state
     * @return {@code true} when at least one extension has been negotiated
     */
    public static boolean hasNegotiatedExtensions(ProtoContext context) {
        WebSocketContext webSocketContext = readyContext(context);
        return webSocketContext != null && !webSocketContext.extensionList().isEmpty();
    }

    /**
     * Return the runtime extensions initialized for the ready websocket context.
     * @param context protocol context that may carry websocket state
     * @return initialized runtime extensions, or an empty list when none are active
     */
    public static List<WebSocketExtensionRuntime> runtimeExtensions(ProtoContext context) {
        WebSocketContext webSocketContext = readyContext(context);
        if (webSocketContext instanceof WebSocketContextImpl) {
            return ((WebSocketContextImpl) webSocketContext).runtimeList();
        }
        return Collections.emptyList();
    }

    /**
     * Determine whether the channel already contains a ready websocket context.
     * @param channel channel to inspect
     * @return {@code true} when the websocket handshake has completed
     */
    public static boolean isReady(SoChannel<?> channel) {
        if (channel == null) {
            return false;
        }

        return isReady(WebSocketRegistry.resolve(channel));
    }

    /**
     * Determine whether the protocol context can resolve a ready websocket context.
     * @param context protocol context to inspect
     * @return {@code true} when the websocket handshake has completed
     */
    public static boolean isReady(ProtoContext context) {
        return isReady(readyContext(context));
    }

    /**
     * Determine whether the given websocket context represents a completed handshake.
     * @param context websocket context to inspect
     * @return {@code true} when the context is present and ready
     */
    public static boolean isReady(WebSocketContext context) {
        return context != null && context.isReady();
    }

    /**
     * Validate a control frame using the default server-side close-code rules.
     * @param frame control frame to validate
     */
    public static void validateControlFrame(WebSocketFrame frame) {
        validateControlFrame(frame, false);
    }

    /**
     * Validate a control frame and apply sender-role-specific close-code rules.
     * @param frame control frame to validate
     * @param senderIsClient whether the sender role is the websocket client
     */
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

        InternalUtils.validateControlPayload(opcode, frame.content(), senderIsClient);
    }

    /**
     * Create a client opening-handshake request and merge extra headers and cookies.
     * @param version websocket version to request
     * @param uri request URI
     * @param headers extra request headers to merge into the handshake
     * @param cookies extra cookies to merge into the handshake
     * @return complete HTTP handshake request
     */
    public static FullHttpRequest createHandshake(WebSocketVersion version, String uri, HttpHeaders headers, Cookie... cookies) {
        FullHttpRequest request = createHandshake(version, uri, headers);
        applyAdditionalHeadersAndCookies(request, headers, cookies);
        return request;
    }

    /**
     * Create a standard RFC 8441 HTTP/2 websocket CONNECT request and merge extra headers and cookies.
     * @param version websocket version to request
     * @param uri websocket URI
     * @param headers extra request headers to merge into the handshake
     * @param cookies extra cookies to merge into the handshake
     * @return complete HTTP/2 websocket CONNECT request
     */
    public static FullHttpRequest createHttp2Handshake(WebSocketVersion version, String uri, HttpHeaders headers, Cookie... cookies) {
        FullHttpRequest request = createHttp2Handshake(version, uri, headers);
        applyAdditionalHeadersAndCookies(request, headers, cookies);
        return request;
    }

    /**
     * Create a bare standard RFC 8441 HTTP/2 websocket CONNECT request.
     * @param version websocket version to request
     * @param uri websocket URI
     * @return complete HTTP/2 websocket CONNECT request
     */
    public static FullHttpRequest createHttp2Handshake(WebSocketVersion version, String uri) {
        return createHttp2Handshake(version, uri, null);
    }

    private static void applyAdditionalHeadersAndCookies(FullHttpRequest request, HttpHeaders headers, Cookie... cookies) {
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
    }

    /**
     * Create a bare client opening-handshake request for the given websocket version.
     * @param version websocket version to request
     * @param uri request URI
     * @return complete HTTP handshake request
     */
    public static FullHttpRequest createHandshake(WebSocketVersion version, String uri) {
        return createHandshake(version, uri, null);
    }

    private static FullHttpRequest createHandshake(WebSocketVersion version, String uri, HttpHeaders headers) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }

        HandshakeTarget target = resolveHandshakeTarget(uri, headers);

        if (version == WebSocketVersion.V0) {
            String key1 = randomHixie76Key();
            String key2 = randomHixie76Key();
            byte[] key3 = RandomUtils.nextBytes(8);
            ByteBuf body = ByteBufAllocator.DEFAULT.buffer(key3.length, Integer.MAX_VALUE);
            body.writeBytes(key3, 0, key3.length);
            body.markWriter();

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, target.requestUri, body);
            request.setHeader(HttpHeaderNames.HOST, target.hostHeader);
            request.setHeader(HttpHeaderNames.UPGRADE, "WebSocket");
            request.setHeader(HttpHeaderNames.CONNECTION, "Upgrade");
            request.setHeader(HttpHeaderNames.ORIGIN, target.originHeader);
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_KEY1, key1);
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_KEY2, key2);
            request.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(key3.length));
            return request;
        } else {
            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, target.requestUri);
            request.setHeader(HttpHeaderNames.HOST, target.hostHeader);
            request.setHeader(HttpHeaderNames.UPGRADE, "websocket");
            request.setHeader(HttpHeaderNames.CONNECTION, "Upgrade");
            request.setHeader(HttpHeaderNames.ORIGIN, target.originHeader);
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_KEY, randomRfc6455Key());
            request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_VERSION, String.valueOf(version.code()));
            request.setHeader(HttpHeaderNames.CONTENT_LENGTH, "0");
            return request;
        }
    }

    private static FullHttpRequest createHttp2Handshake(WebSocketVersion version, String uri, HttpHeaders headers) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        if (!version.isRfc6455Framing()) {
            throw new IllegalArgumentException("standard HTTP/2 websocket only supports RFC6455 framing versions");
        }

        HandshakeTarget target = resolveHandshakeTarget(uri, headers);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.CONNECT, target.requestUri);
        request.setHeader(HttpHeaderNames.HOST, target.hostHeader);
        request.setHeader(HttpHeaderNames.ORIGIN, target.originHeader);
        request.setHeader(HttpHeaderNames.X_FORWARDED_PROTO, target.schemeHeader);
        request.setHeader(HttpHeaderNames.PSEUDO_PROTOCOL, HttpHeaderValues.WEBSOCKET);
        request.setHeader(HttpHeaderNames.SEC_WEBSOCKET_VERSION, String.valueOf(version.code()));
        return request;
    }

    private static HandshakeTarget resolveHandshakeTarget(String uri, HttpHeaders headers) {
        if (headers == null) {
            HandshakeTargetCache cache = HANDSHAKE_TARGET_CACHE.get();
            if (uri != null && uri.equals(cache.uri)) {
                return cache.target;
            }

            HandshakeTarget target = resolveHandshakeTargetUncached(uri, null);
            cache.uri = uri;
            cache.target = target;
            return target;
        }
        return resolveHandshakeTargetUncached(uri, headers);
    }

    private static HandshakeTarget resolveHandshakeTargetUncached(String uri, HttpHeaders headers) {
        if (StringUtils.isBlank(uri)) {
            throw new IllegalArgumentException("handshake uri must not be blank");
        }

        URI parsedUri;
        try {
            parsedUri = URI.create(uri);
        } catch (IllegalArgumentException e) {
            parsedUri = null;
        }

        if (parsedUri != null && parsedUri.isAbsolute() && StringUtils.isNotBlank(parsedUri.getHost())) {
            String requestUri = normalizeRequestUri(parsedUri);
            String hostHeader = toHostHeader(parsedUri);
            String originHeader = toOriginHeader(parsedUri, hostHeader);
            String schemeHeader = normalizeHandshakeScheme(parsedUri.getScheme());
            return new HandshakeTarget(requestUri, hostHeader, originHeader, schemeHeader);
        }

        String hostHeader = headers != null ? headers.getString(HttpHeaderNames.HOST) : null;
        String originHeader = headers != null ? headers.getString(HttpHeaderNames.ORIGIN) : null;
        String schemeHeader = headers != null ? headers.getString(HttpHeaderNames.X_FORWARDED_PROTO) : null;
        if (StringUtils.isBlank(schemeHeader) && StringUtils.isNotBlank(originHeader)) {
            try {
                schemeHeader = normalizeHandshakeScheme(URI.create(originHeader).getScheme());
            } catch (IllegalArgumentException e) {
                schemeHeader = null;
            }
        }

        if (StringUtils.isBlank(hostHeader) || StringUtils.isBlank(originHeader) || StringUtils.isBlank(schemeHeader)) {
            throw new IllegalArgumentException("relative websocket handshake URI requires explicit Host and Origin headers or an absolute URI");
        }
        return new HandshakeTarget(uri, hostHeader, originHeader, schemeHeader);
    }

    private static String normalizeRequestUri(URI parsedUri) {
        String rawPath = parsedUri.getRawPath();
        StringBuilder builder = new StringBuilder(StringUtils.isBlank(rawPath) ? "/" : rawPath);
        String rawQuery = parsedUri.getRawQuery();
        if (StringUtils.isNotBlank(rawQuery)) {
            builder.append('?').append(rawQuery);
        }
        return builder.toString();
    }

    private static String toHostHeader(URI parsedUri) {
        String host = formatAuthorityHost(parsedUri.getHost());
        int port = parsedUri.getPort();
        if (port < 0 || port == defaultPort(parsedUri.getScheme())) {
            return host;
        }
        return host + ':' + port;
    }

    private static String toOriginHeader(URI parsedUri, String hostHeader) {
        String scheme = normalizeHandshakeScheme(parsedUri.getScheme());
        return scheme + "://" + hostHeader;
    }

    private static String normalizeHandshakeScheme(String scheme) {
        if (StringUtils.equalsIgnoreCase("ws", scheme)) {
            return "http";
        } else if (StringUtils.equalsIgnoreCase("wss", scheme)) {
            return "https";
        } else {
            return scheme != null ? scheme.toLowerCase() : null;
        }
    }

    private static String formatAuthorityHost(String host) {
        if (host.indexOf(':') >= 0 && !(host.startsWith("[") && host.endsWith("]"))) {
            return '[' + host + ']';
        } else {
            return host;
        }
    }

    private static int defaultPort(String scheme) {
        if (StringUtils.equalsIgnoreCase("ws", scheme) || StringUtils.equalsIgnoreCase("http", scheme)) {
            return 80;
        } else if (StringUtils.equalsIgnoreCase("wss", scheme) || StringUtils.equalsIgnoreCase("https", scheme)) {
            return 443;
        } else {
            throw new IllegalArgumentException("schema '" + scheme + "' invalid.");
        }
    }

    private static String randomRfc6455Key() {
        byte[] keyBytes = RFC6455_KEY_BYTES.get();
        RandomUtils.nextBytes(keyBytes);
        return BASE64_ENCODER.encodeToString(keyBytes);
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
            // These spaces are significant and must survive header-value trimming.
            builder.insert(RandomUtils.nextInt(1, builder.length() - 1), ' ');
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

    /**
     * Create a final unmasked text frame from the given string.
     * @param text text payload
     * @return text frame
     */
    public static WebSocketFrame textFrame(String text) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(text.length() * 3, Integer.MAX_VALUE);
        buf.writeString(text, StandardCharsets.UTF_8);
        buf.markWriter();
        return textFrame(true, false, null, buf);
    }

    /**
     * Create a text frame with explicit fragment and masking flags.
     * @param finalFragment whether this frame is the final fragment
     * @param masked whether the payload should be masked
     * @param maskingKey masking key, or {@code null} when masking is disabled
     * @param content frame payload
     * @return text frame
     */
    public static WebSocketFrame textFrame(boolean finalFragment, boolean masked, byte[] maskingKey, ByteBuf content) {
        return WebSocketFrame.create(WebSocketOpcode.TEXT, finalFragment, masked, maskingKey, content);
    }

    /**
     * Create a final unmasked binary frame from the given bytes.
     * @param data binary payload
     * @return binary frame
     */
    public static WebSocketFrame binaryFrame(byte[] data) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(data.length, Integer.MAX_VALUE);
        buf.writeBytes(data, 0, data.length);
        buf.markWriter();
        return binaryFrame(true, false, null, buf);
    }

    /**
     * Create a binary frame with explicit fragment and masking flags.
     * @param finalFragment whether this frame is the final fragment
     * @param masked whether the payload should be masked
     * @param maskingKey masking key, or {@code null} when masking is disabled
     * @param content frame payload
     * @return binary frame
     */
    public static WebSocketFrame binaryFrame(boolean finalFragment, boolean masked, byte[] maskingKey, ByteBuf content) {
        return WebSocketFrame.create(WebSocketOpcode.BINARY, finalFragment, masked, maskingKey, content);
    }

    /**
     * Create an empty unmasked ping frame.
     * @return ping frame
     */
    public static WebSocketFrame pingFrame() {
        return WebSocketFrame.create(WebSocketOpcode.PING, true, false, null, ByteBuf.EMPTY);
    }

    /**
     * Create a ping frame with explicit masking and payload.
     * @param masked whether the payload should be masked
     * @param maskingKey masking key, or {@code null} when masking is disabled
     * @param content frame payload
     * @return ping frame
     */
    public static WebSocketFrame pingFrame(boolean masked, byte[] maskingKey, ByteBuf content) {
        return WebSocketFrame.create(WebSocketOpcode.PING, true, masked, maskingKey, content);
    }

    /**
     * Create an empty unmasked pong frame.
     * @return pong frame
     */
    public static WebSocketFrame pongFrame() {
        return WebSocketFrame.create(WebSocketOpcode.PONG, true, false, null, ByteBuf.EMPTY);
    }

    /**
     * Create a pong frame with explicit masking and payload.
     * @param masked whether the payload should be masked
     * @param maskingKey masking key, or {@code null} when masking is disabled
     * @param content frame payload
     * @return pong frame
     */
    public static WebSocketFrame pongFrame(boolean masked, byte[] maskingKey, ByteBuf content) {
        return WebSocketFrame.create(WebSocketOpcode.PONG, true, masked, maskingKey, content);
    }

    /**
     * Create an unmasked close frame from a status code and optional reason.
     * @param statusCode websocket close status code
     * @param reason optional close reason text
     * @return close frame
     */
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

    /**
     * Create a close frame with explicit masking and payload.
     * @param masked whether the payload should be masked
     * @param maskingKey masking key, or {@code null} when masking is disabled
     * @param content frame payload
     * @return close frame
     */
    public static WebSocketFrame closeFrame(boolean masked, byte[] maskingKey, ByteBuf content) {
        return WebSocketFrame.create(WebSocketOpcode.CLOSE, true, masked, maskingKey, content);
    }

    /**
     * Create a continuation frame.
     * @param finalFragment whether this frame is the final fragment
     * @param masked whether the payload should be masked
     * @param maskingKey masking key, or {@code null} when masking is disabled
     * @param content frame payload
     * @return continuation frame
     */
    public static WebSocketFrame continuationFrame(boolean finalFragment, boolean masked, byte[] maskingKey, ByteBuf content) {
        return WebSocketFrame.create(WebSocketOpcode.CONTINUATION, finalFragment, masked, maskingKey, content);
    }

    //

    /**
     * Create a final text message chunk.
     * @param content message payload
     * @return text message chunk
     */
    public static TextWebSocketMessage textMessage(ByteBuf content) {
        return textMessage(WebSocketMessage.FINAL_SEQUENCE, content);
    }

    /**
     * Create a text message chunk with the given sequence number.
     * @param sequence message chunk sequence
     * @param content message payload whose ownership is transferred to the message
     * @return text message chunk
     */
    public static TextWebSocketMessage textMessage(int sequence, ByteBuf content) {
        return TextWebSocketMessage.request(sequence, content);
    }

    /**
     * Create a final binary message chunk.
     * @param content message payload
     * @return binary message chunk
     */
    public static BinaryWebSocketMessage binaryMessage(ByteBuf content) {
        return binaryMessage(WebSocketMessage.FINAL_SEQUENCE, content);
    }

    /**
     * Create a binary message chunk with the given sequence number.
     * @param sequence message chunk sequence
     * @param content message payload whose ownership is transferred to the message
     * @return binary message chunk
     */
    public static BinaryWebSocketMessage binaryMessage(int sequence, ByteBuf content) {
        return BinaryWebSocketMessage.request(sequence, content);
    }

    /**
     * Create an empty ping event.
     * @return ping event
     */
    public static PingWebSocketEvent pingEvent() {
        return new PingWebSocketEvent();
    }

    /**
     * Create a ping event carrying an application payload.
     * @param content event payload whose ownership is transferred to the event
     * @return ping event
     */
    public static PingWebSocketEvent pingEvent(ByteBuf content) {
        return new PingWebSocketEvent(content);
    }

    /**
     * Create an empty pong event.
     * @return pong event
     */
    public static PongWebSocketEvent pongEvent() {
        return new PongWebSocketEvent();
    }

    /**
     * Create a pong event carrying an application payload.
     * @param content event payload whose ownership is transferred to the event
     * @return pong event
     */
    public static PongWebSocketEvent pongEvent(ByteBuf content) {
        return new PongWebSocketEvent(content);
    }
}
