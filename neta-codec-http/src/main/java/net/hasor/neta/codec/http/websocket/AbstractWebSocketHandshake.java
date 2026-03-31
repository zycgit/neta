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
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoDuplexer;
import net.hasor.neta.channel.ProtoExceptionHolder;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.codec.http.*;

/**
 * Shared foundation for WebSocket opening-handshake duplexers.
 * <p>
 * Function:
 * <pre>
 *   validate HTTP upgrade preconditions
 *   share version-compatibility rules
 *   build handshake results and failure handling helpers
 * </pre>
 * <p>
 * pipeline view:
 * <pre>
 *   HttpObject request/response parts
 *      -> AbstractWebSocketHandshake subclass
 *      -> upgraded WebSocket context + handshake events
 * </pre>
 * <p>
 * Typical usage:
 * <pre>
 *   ctx.addLast("ws-client", new WebSocketClientHandshakeDuplexer(WebSocketVersion.V13));
 *   ctx.addLast("ws-server", new WebSocketServerHandshakeDuplexer(WebSocketVersion.V13));
 * </pre>
 */
public abstract class AbstractWebSocketHandshake implements ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject> {
    private static final Logger           logger         = Logger.getLogger(AbstractWebSocketHandshake.class);
    private static final String           WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    protected final      WebSocketVersion codecVersion;

    protected AbstractWebSocketHandshake(WebSocketVersion codecVersion) {
        if (codecVersion == null) {
            throw new IllegalArgumentException("codecVersion must not be null");
        }

        this.codecVersion = codecVersion;
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        resetState(context);
        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        resetState(context);
    }

    protected abstract void resetState(ProtoContext context);

    // Handshake errors may be wrapped by transport/framework exceptions before they
    // reach onError(...). We therefore need to search the cause chain for the first
    // WebSocketHandshakeException instead of only checking the outermost Throwable.
    // getRootCause(...) is not suitable here because WebSocketHandshakeException may
    // itself wrap another cause, in which case the root cause would no longer be the
    // handshake-domain marker we need for routing logic.
    protected final WebSocketHandshakeException handshakeError(Throwable e) {
        for (Throwable item : ExceptionUtils.getThrowables(e)) {
            if (item instanceof WebSocketHandshakeException) {
                return (WebSocketHandshakeException) item;
            }
        }
        return null;
    }

    //

    protected final boolean isCompatible(WebSocketVersion requestedVersion) {
        if (requestedVersion == this.codecVersion) {
            return true;
        }
        return requestedVersion != null && requestedVersion.isRfc6455Framing() && this.codecVersion.isRfc6455Framing();
    }

    protected final boolean isHttpRequestPart(HttpObject msg) {
        return msg instanceof HttpRequest || msg instanceof HttpHeaders || msg instanceof HttpContent;
    }

    protected final boolean isHttpResponsePart(HttpObject msg) {
        return msg instanceof HttpResponse || msg instanceof HttpHeaders || msg instanceof HttpContent;
    }

    protected final void warnAndDrop(ProtoContext context, String stage, HttpObject msg) {
        long channelId = context.getChannel().getChannelId();
        String typeName = msg == null ? "null" : msg.getClass().getSimpleName();
        logger.warn("[WS-HS] channel=" + channelId + " stage=" + stage + " drop unsupported handshake object type=" + typeName);
    }

    protected final void warnDropReason(ProtoContext context, String stage, String reason) {
        long channelId = context.getChannel().getChannelId();
        logger.warn("[WS-HS] channel=" + channelId + " stage=" + stage + ' ' + reason);
    }

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

    protected final String computeAcceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 algorithm not available", e);
        }
    }

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

    protected final void finishWebSocketUpgrade(ProtoContext context, WebSocketContext webSocketContext) throws Throwable {
        context.context(WebSocketContext.class, webSocketContext);
        context.rootContext(WebSocketContext.class, webSocketContext);
        context.fireEventSnd(HttpThroughEvent.class, HttpThroughEvent.enable());
        context.fireEventRcv(WebSocketHandshakeEvent.class, new WebSocketHandshakeEvent(webSocketContext));
    }
}