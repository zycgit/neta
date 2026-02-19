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
import net.hasor.neta.codec.http.DefaultFullHttpResponse;
import net.hasor.neta.codec.http.FullHttpRequest;
import net.hasor.neta.codec.http.FullHttpResponse;
import net.hasor.neta.codec.http.constant.HttpHeaderNames;
import net.hasor.neta.codec.http.constant.HttpHeaderValues;
import net.hasor.neta.codec.http.constant.HttpStatus;
import net.hasor.neta.codec.http.constant.HttpVersion;

/**
 * Utility class for performing the WebSocket server-side opening handshake as
 * described in <a href="https://tools.ietf.org/html/rfc6455#section-4.2">RFC 6455 §4.2</a>.
 * <h3>Handshake flow</h3>
 * <ol>
 *   <li>Client sends an HTTP/1.1 GET request with:
 *       <ul>
 *         <li>{@code Upgrade: websocket}</li>
 *         <li>{@code Connection: Upgrade}</li>
 *         <li>{@code Sec-WebSocket-Key: &lt;base64-16-bytes&gt;}</li>
 *         <li>{@code Sec-WebSocket-Version: 13}</li>
 *       </ul>
 *   </li>
 *   <li>Server responds with HTTP 101 Switching Protocols and a computed
 *       {@code Sec-WebSocket-Accept} header.</li>
 * </ol>
 * <h3>Usage</h3>
 * <pre>
 *   FullHttpResponse resp = WebSocketServerHandshaker.handshakeResponse(request);
 *   channel.sendData(resp);
 * </pre>
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
     * Validates the incoming HTTP upgrade request and returns true if it looks like
     * a valid WebSocket handshake request.
     * @param request the HTTP request to validate
     * @return {@code true} if the request is a WebSocket upgrade request
     */
    public static boolean isWebSocketUpgrade(FullHttpRequest request) {
        if (request == null) {
            return false;
        }
        String upgrade = request.headers().get(HttpHeaderNames.UPGRADE);
        String connection = request.headers().get(HttpHeaderNames.CONNECTION);
        String wsKey = request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_KEY);
        String wsVersion = request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_VERSION);

        return HttpHeaderValues.WEBSOCKET.equalsIgnoreCase(upgrade) && connection != null && connection.toLowerCase().contains(HttpHeaderValues.UPGRADE) && wsKey != null && !wsKey.isEmpty() && "13".equals(wsVersion);
    }

    /**
     * Builds the HTTP 101 Switching Protocols response for a WebSocket upgrade.
     * <p>The response includes:
     * <ul>
     *   <li>{@code HTTP/1.1 101 Switching Protocols}</li>
     *   <li>{@code Upgrade: websocket}</li>
     *   <li>{@code Connection: Upgrade}</li>
     *   <li>{@code Sec-WebSocket-Accept: <computed>}</li>
     * </ul>
     * @param request the client's upgrade request
     * @return the 101 response ready to send
     * @throws IllegalArgumentException if {@code Sec-WebSocket-Key} is missing
     */
    public static FullHttpResponse handshakeResponse(FullHttpRequest request) {
        String wsKey = request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_KEY);
        if (wsKey == null || wsKey.isEmpty()) {
            throw new IllegalArgumentException("Missing Sec-WebSocket-Key header");
        }

        String acceptKey = computeAcceptKey(wsKey.trim());

        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpStatus.SWITCHING_PROTOCOLS);
        response.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
        response.headers().set(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT, acceptKey);

        // Honour requested sub-protocol if any
        String subProtocol = request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        if (subProtocol != null && !subProtocol.isEmpty()) {
            response.headers().set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, subProtocol);
        }

        return response;
    }

    /**
     * Computes the {@code Sec-WebSocket-Accept} value for the given
     * {@code Sec-WebSocket-Key} as per RFC 6455 §4.2.2:
     * <pre>
     *   accept = Base64( SHA-1( key + GUID ) )
     * </pre>
     * @param key the value of the {@code Sec-WebSocket-Key} header
     * @return the base64-encoded SHA-1 accept token
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
}
