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
 * Resolved WebSocket session context.
 * <p>
 * Exposes handshake outcome, negotiated sub-protocol, version, request path, and extensions to
 * later pipeline stages and routing logic.
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
