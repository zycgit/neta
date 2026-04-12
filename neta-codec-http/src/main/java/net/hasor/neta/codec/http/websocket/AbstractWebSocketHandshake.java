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
import java.util.*;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.routing.PartitionKey;
import net.hasor.neta.codec.http.*;

/**
 * Shared base implementation for websocket opening-handshake duplexers.
 * <p>
 * Main responsibilities:
 * <pre>
 *   Validate HTTP upgrade preconditions
 *   Share version compatibility rules
 *   Provide helper methods for handshake success and failure handling
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-22
 */
public abstract class AbstractWebSocketHandshake implements ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject> {
    private static final Logger           logger         = Logger.getLogger(AbstractWebSocketHandshake.class);
    private static final String           WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"; // RFC 6455
    protected final      WebSocketVersion codecVersion;

    /**
     * Create the base handshake duplexer.
     * @param codecVersion frame-format version to use after handshake negotiation completes
     */
    protected AbstractWebSocketHandshake(WebSocketVersion codecVersion) {
        if (codecVersion == null) {
            throw new IllegalArgumentException("codecVersion must not be null");
        }

        this.codecVersion = codecVersion;
    }

    /**
     * Complete the websocket upgrade and install the handshake result context.
     * Also enables HTTP pass-through mode and publishes the handshake-complete event on the receive side.
     * @param context protocol context
     * @param wsContext parsed websocket context
     * @throws Throwable thrown if subsequent events fail to publish
     */
    protected final void finishWebSocketUpgrade(ProtoContext context, WebSocketContext wsContext, long streamId) throws Throwable {
        WebSocketRegistryKey endpointKey = resolveEndpointKey(context, streamId);

        context.context(WebSocketContext.class, wsContext);
        if (endpointKey.isConnectionScope()) {
            context.rootContext(WebSocketContext.class, wsContext);
        }
        WebSocketRegistry.bind(context, endpointKey, wsContext);

        context.fireEventRcv(HttpThroughEvent.class, new HttpThroughEvent(true, streamId));
        context.fireEventSnd(HttpThroughEvent.class, new HttpThroughEvent(true, streamId));
        context.fireEventSnd(WebSocketHandshakeEvent.class, new WebSocketHandshakeEvent(streamId, wsContext));
        context.fireEventRcv(WebSocketHandshakeEvent.class, new WebSocketHandshakeEvent(streamId, wsContext));
        publishRootHandshakeEvent(context, true, new WebSocketHandshakeEvent(streamId, wsContext));
        publishRootHandshakeEvent(context, false, new WebSocketHandshakeEvent(streamId, wsContext));
    }

    /**
     * Reset handshake state when an error occurs.
     */
    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        resetState(context);
        return ProtoStatus.Next;
    }

    /**
     * Reset handshake state when the duplexer closes.
     */
    @Override
    public void onClose(ProtoContext context) {
        resetState(context);

        WebSocketRegistryKey endpointKey = WebSocketRegistry.resolveEndpointKey(context);
        WebSocketRegistry.remove(context, endpointKey);
        if (endpointKey == null || endpointKey.isConnectionScope()) {
            context.rootContext(WebSocketContext.class, null);
        }
        context.context(WebSocketContext.class, null);
    }

    /**
     * Reset the handshake-related state held by the concrete implementation.
     * @param context protocol context
     */
    protected abstract void resetState(ProtoContext context);

    // A handshake exception may be wrapped by transport or framework exceptions before it reaches onError(...).
    // Walk the cause chain and locate the first WebSocketHandshakeException so handshake-domain failures can still be recognized.

    /**
     * Extract a handshake exception from the exception chain.
     * @param e original exception
     * @return handshake exception, or {@code null} if none exists
     */
    protected final WebSocketHandshakeException handshakeError(Throwable e) {
        for (Throwable item : ExceptionUtils.getThrowables(e)) {
            if (item instanceof WebSocketHandshakeException) {
                return (WebSocketHandshakeException) item;
            }
        }
        return null;
    }

    //

    /**
     * Return whether the requested version is compatible with the current codec version.
     * @param requestedVersion requested version
     * @return {@code true} if compatible
     */
    protected final boolean isCompatible(WebSocketVersion requestedVersion) {
        if (requestedVersion == this.codecVersion) {
            return true;
        }
        return requestedVersion != null && requestedVersion.isRfc6455Framing() && this.codecVersion.isRfc6455Framing();
    }

    /**
     * Return whether the object belongs to an HTTP request.
     * @param msg HTTP object
     * @return {@code true} if it is a request-side fragment
     */
    protected final boolean isHttpRequestPart(HttpObject msg) {
        return msg instanceof HttpRequest || msg instanceof HttpHeaders || msg instanceof HttpContent;
    }

    /**
     * Return whether the object belongs to an HTTP response.
     * @param msg HTTP object
     * @return {@code true} if it is a response-side fragment
     */
    protected final boolean isHttpResponsePart(HttpObject msg) {
        return msg instanceof HttpResponse || msg instanceof HttpHeaders || msg instanceof HttpContent;
    }

    /**
     * Split a comma-separated header into trimmed values.
     * @param headerValue raw header value
     * @return parsed values, or an empty list
     */
    protected final List<String> parseHeaderValues(String headerValue) {
        if (StringUtils.isBlank(headerValue)) {
            return Collections.emptyList();
        }

        String[] parts = headerValue.split(",");
        List<String> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            String value = part != null ? part.trim() : null;
            if (StringUtils.isNotBlank(value)) {
                values.add(value);
            }
        }

        if (values.isEmpty()) {
            return Collections.emptyList();
        } else {
            return values;
        }
    }

    /**
     * Locate one registered extension support by extension name.
     * @param supports registered supports
     * @param extensionName extension name to locate
     * @return matching support, or {@code null}
     */
    protected final WebSocketExtension findExtensionSupport(List<WebSocketExtension> supports, String extensionName) {
        if (supports == null || StringUtils.isBlank(extensionName)) {
            return null;
        }

        for (WebSocketExtension support : supports) {
            if (support != null && StringUtils.equalsIgnoreCase(extensionName, support.extensionName())) {
                return support;
            }
        }
        return null;
    }

    /**
     * Extract the single header fragment for the specified extension name.
     * @param items parsed extension items
     * @param extensionName extension name to locate
     * @param duplicatedMessage error used when duplicates exist
     * @return single header fragment, or {@code null}
     */
    protected final String findSingleExtensionHeaderValue(List<WebSocketExtensionResult> items, String extensionName, String duplicatedMessage) {
        if (items == null || items.isEmpty() || StringUtils.isBlank(extensionName)) {
            return null;
        }

        String resolved = null;
        for (WebSocketExtensionResult item : items) {
            if (item == null || !StringUtils.equalsIgnoreCase(extensionName, item.name())) {
                continue;
            }
            if (resolved != null) {
                throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, duplicatedMessage);
            }
            resolved = item.asHeaderValue();
        }
        return resolved;
    }

    /**
     * Reject duplicated extension names in one parsed extension list.
     * @param items parsed extension items
     * @param duplicatedPrefix error prefix for duplicated names
     */
    protected final void ensureNoDuplicateExtensions(List<WebSocketExtensionResult> items, String duplicatedPrefix) {
        if (items == null || items.isEmpty()) {
            return;
        }

        Set<String> names = new HashSet<>();
        for (WebSocketExtensionResult item : items) {
            if (item == null || StringUtils.isBlank(item.name())) {
                continue;
            }

            String normalized = item.name().toLowerCase();
            if (!names.add(normalized)) {
                throw new WebSocketHandshakeException(HttpStatus.BAD_REQUEST, duplicatedPrefix + item.name());
            }
        }
    }

    /**
     * Copy the handshake request while replacing the requested extension header.
     * @param request original request snapshot
     * @param requestedHeader one extension header fragment
     * @return copied request snapshot
     */
    protected final WebSocketHandshakeRequest copyHandshakeRequest(WebSocketHandshakeRequest request, String requestedHeader) {
        WebSocketHandshakeRequest copy = new WebSocketHandshakeRequest(request.version(), request.requestPath(), request.requestedProtocols(), requestedHeader, request.headers());
        copy.streamId(request.streamId());
        return copy;
    }

    private static WebSocketRegistryKey resolveEndpointKey(ProtoContext context, long streamId) {
        HttpScope scope = InternalUtils.resolveHttpScope(context);
        PartitionKey partitionKey = PartitionKey.findKey(context);
        HttpVersion httpVersion = context != null ? context.context(HttpVersion.class) : null;
        boolean streamScopedPartition = partitionKey != null && !PartitionKey.defaultKey().equals(partitionKey);
        boolean streamScopedHttp = httpVersion != null && httpVersion.majorVersion() >= 2;
        if (streamId > 0 && (scope == HttpScope.STREAM || streamScopedPartition || streamScopedHttp)) {
            return WebSocketRegistryKey.streamScope(streamId);
        }
        return WebSocketRegistryKey.connectionScope();
    }

    private static void publishRootHandshakeEvent(ProtoContext context, boolean rcvDirection, WebSocketHandshakeEvent event) {
        if (context == null || event == null) {
            return;
        }

        PartitionKey partitionKey = PartitionKey.findKey(context);
        if (partitionKey == null || PartitionKey.defaultKey().equals(partitionKey)) {
            return;
        }

        if (!(context.getSoContext() instanceof SoContextService)) {
            return;
        }

        SoChannel<?> channel = context.getChannel();
        if (channel == null) {
            return;
        }

        SoEvent rootEvent = new SoEvent() {
            @Override
            public SoChannel<?> getSource() {
                return channel;
            }

            @Override
            public Class<?> getEventType() {
                return WebSocketHandshakeEvent.class;
            }

            @Override
            public Object getData() {
                return event;
            }
        };

        SoContextService soContext = (SoContextService) context.getSoContext();
        if (rcvDirection) {
            soContext.notifyRcvEvent(channel.getChannelId(), null, rootEvent);
        } else {
            soContext.notifySndEvent(channel.getChannelId(), null, rootEvent);
        }
    }

    /**
     * Join header fragments back into one websocket extension header.
     * @param headerValues header fragments to join
     * @return normalized header value, or {@code null}
     */
    protected final String joinHeaderValues(List<String> headerValues) {
        if (headerValues == null || headerValues.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (String item : headerValues) {
            if (StringUtils.isBlank(item)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(item);
        }

        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Log and drop an unsupported handshake object.
     * @param context protocol context
     * @param stage current handshake stage
     * @param msg dropped object
     */
    protected final void warnAndDrop(ProtoContext context, String stage, HttpObject msg) {
        long channelId = context.getChannel().getChannelId();
        String typeName = msg == null ? "null" : msg.getClass().getSimpleName();
        logger.warn("[WS-HS] channel=" + channelId + " stage=" + stage + " drop unsupported handshake object type=" + typeName);
    }

    /**
     * Log the reason why a handshake object or stage was dropped.
     * @param context protocol context
     * @param stage current handshake stage
     * @param reason explanation text
     */
    protected final void warnDropReason(ProtoContext context, String stage, String reason) {
        long channelId = context.getChannel().getChannelId();
        logger.warn("[WS-HS] channel=" + channelId + " stage=" + stage + ' ' + reason);
    }

    /**
     * Detect the websocket version from handshake request fragments.
     * @param request handshake request snapshot
     * @return detected version, or {@code null} if it cannot be recognized
     */
    protected final WebSocketVersion detectVersion(HttpMessageParts request) {
        if (request == null) {
            return null;
        }

        String upgrade = request.header(HttpHeaderNames.UPGRADE);
        String connection = request.header(HttpHeaderNames.CONNECTION);
        if (!StringUtils.containsIgnoreCase(connection, HttpHeaderValues.UPGRADE)) {
            return null;
        }
        if (!StringUtils.equalsIgnoreCase(HttpHeaderValues.WEBSOCKET, upgrade) && !StringUtils.equalsIgnoreCase("WebSocket", upgrade)) {
            return null;
        }

        String wsVersion = request.header(HttpHeaderNames.SEC_WEBSOCKET_VERSION);
        if (StringUtils.isNotBlank(wsVersion)) {
            return WebSocketVersion.of(wsVersion.trim());
        }

        String key1 = request.header(HttpHeaderNames.SEC_WEBSOCKET_KEY1);
        String key2 = request.header(HttpHeaderNames.SEC_WEBSOCKET_KEY2);
        if (StringUtils.isNotBlank(key1) && StringUtils.isNotBlank(key2)) {
            return WebSocketVersion.V0;
        }

        return null;
    }

    /**
     * Compute the Accept key used by the RFC 6455 handshake response.
     * @param key client request key
     * @return server response key
     */
    protected final String computeAcceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 algorithm not available", e);
        }
    }

    /**
     * Compute the Hixie-76 handshake response body.
     * @param key1 first key
     * @param key2 second key
     * @param key3 8-byte payload associated with the third key
     * @return response bytes
     */
    protected final byte[] computeHixie76Response(String key1, String key2, byte[] key3) {
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