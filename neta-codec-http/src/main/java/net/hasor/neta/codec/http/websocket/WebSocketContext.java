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

/**
 * Protocol context interface for WebSocket connections.
 * <p>
 * Exposes WebSocket handshake and session state — including the negotiated
 * sub-protocol ({@code Sec-WebSocket-Protocol}) — so that
 * {@link net.hasor.neta.channel.ProtoRouting} can branch on sub-protocol.
 * </p>
 * <p>
 * Registered on {@link net.hasor.neta.channel.ProtoContext} via
 * {@code context.context(WebSocketContext.class, impl)}.
 * </p>
 * <p>
 * Usage in {@link net.hasor.neta.channel.ProtoRouting}:
 * <pre>{@code
 * (context, rcvUp, rcvDown) -> {
 *     WebSocketContext ws = context.context(WebSocketContext.class);
 *     if (ws != null && ws.isReady()) {
 *         String sub = ws.subProtocol();
 *         if ("graphql-transport-ws".equals(sub)) {
 *             return "graphql";
 *         }
 *         return "default-ws";
 *     }
 *     return null; // handshake not complete yet, defer routing
 * }
 * }</pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
public interface WebSocketContext {

    /** Returns {@code true} when the WebSocket opening handshake is complete. */
    boolean isReady();

    /** Returns {@code true} if this endpoint is the server side. */
    boolean isServer();

    /** Returns {@code true} if this endpoint is the client side. */
    boolean isClient();

    /**
     * Returns the negotiated sub-protocol from the {@code Sec-WebSocket-Protocol} header,
     * or {@code null} if no sub-protocol was negotiated.
     */
    String subProtocol();

    /**
     * Returns the WebSocket version (e.g. 13 for RFC 6455).
     */
    int version();

    /**
     * Returns the WebSocket request URI path (e.g. {@code "/chat"}).
     * Available on the server side after the opening handshake.
     * @return the request path, or {@code null} if not available
     */
    String requestPath();

    /**
     * Returns the negotiated extensions as a comma-separated string
     * (e.g. {@code "permessage-deflate"}), or {@code null} if none.
     */
    String extensions();
}
