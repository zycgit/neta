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
import java.util.List;
/**
 * Context object representing the parsed websocket handshake result.
 * <p>
 * Exposes handshake results, negotiated sub-protocol, version, request path,
 * and extension information to later pipeline stages and routing logic.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-20
 */
public interface WebSocketContext {
    /**
     * Return {@code true} after the websocket opening handshake completes.
     */
    boolean isReady();

    /**
     * Return {@code true} when the current endpoint is the server side.
     */
    boolean isServer();

    /**
     * Return {@code true} when the current endpoint is the client side.
     */
    boolean isClient();

    /**
     * Return the sub-protocol negotiated from the {@code Sec-WebSocket-Protocol} header.
     * Returns {@code null} if no sub-protocol was negotiated.
     */
    String subProtocol();

    /**
     * Return the websocket version number.
     * For example, RFC 6455 corresponds to version 13.
     */
    int version();

    /**
     * Return the URI path from the handshake request, such as {@code "/chat"}.
     * This value becomes available after the handshake completes.
     * @return request path, or {@code null} if unavailable
     */
    String requestPath();

    /**
     * Return the request Host header captured from the opening handshake.
     * @return request Host header, or {@code null} if unavailable
     */
    String requestHost();

    /**
     * Return the request Origin header captured from the opening handshake.
     * @return request Origin header, or {@code null} if unavailable
     */
    String requestOrigin();

    /**
     * Return the negotiated extension string in comma-separated form,
     * for example {@code "permessage-deflate"}; returns {@code null} if no
     * extensions were negotiated.
     */
    String extensions();

    /**
     * Return structured extension negotiation results; returns an empty list if no extensions were negotiated.
     */
    List<WebSocketExtensionResult> extensionList();

    /**
     * Return {@code true} when an extension with the specified name was negotiated successfully.
     * @param name extension name
     * @return {@code true} if the extension was negotiated successfully
     */
    boolean hasExtension(String name);
}
