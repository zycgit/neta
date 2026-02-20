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

import java.util.Collections;
import java.util.List;

/**
 * Default implementation of {@link WebSocketContext}.
 * <p>
 * Created after the WebSocket opening handshake completes and registered on
 * {@link net.hasor.neta.channel.ProtoContext} via
 * {@code context.context(WebSocketContext.class, impl)}.
 * <p>
 * Use the static factory method {@link #fromHandshake} to create an instance
 * from the handshake parameters.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
public class WebSocketContextImpl implements WebSocketContext {
    private final boolean      server;
    private final String       subProtocol;
    private final int          version;
    private final String       requestPath;
    private final List<String> extensions;

    /**
     * Creates a new WebSocketContext.
     * @param server true for server-side, false for client-side
     * @param subProtocol the negotiated sub-protocol (or null)
     * @param version the WebSocket version (typically 13)
     * @param requestPath the request URI path of the upgrade request
     * @param extensions negotiated extensions (or empty list)
     */
    public WebSocketContextImpl(boolean server, String subProtocol, int version, String requestPath, List<String> extensions) {
        this.server = server;
        this.subProtocol = subProtocol;
        this.version = version;
        this.requestPath = requestPath;
        this.extensions = extensions != null ? Collections.unmodifiableList(extensions) : Collections.emptyList();
    }

    /**
     * Creates a server-side {@link WebSocketContext} from handshake parameters.
     * <p>
     * Typically called after {@link WebSocketServerHandshaker#handshakeResponse} succeeds:
     * <pre>{@code
     * FullHttpResponse resp = WebSocketServerHandshaker.handshakeResponse(request);
     * context.context(WebSocketContext.class,
     *     WebSocketContextImpl.fromHandshake(request.uri(),
     *         request.headers().get("Sec-WebSocket-Protocol"),
     *         request.headers().get("Sec-WebSocket-Extensions")));
     * }</pre>
     * @param requestPath the request URI path
     * @param subProtocol the negotiated sub-protocol (may be null)
     * @param extensions comma-separated extensions string (may be null)
     * @return a new server-side WebSocketContext
     */
    public static WebSocketContextImpl fromHandshake(String requestPath, String subProtocol, String extensions) {
        List<String> extList;
        if (extensions != null && !extensions.isEmpty()) {
            String[] parts = extensions.split(",");
            extList = new java.util.ArrayList<>(parts.length);
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    extList.add(trimmed);
                }
            }
        } else {
            extList = Collections.emptyList();
        }
        return new WebSocketContextImpl(true, subProtocol, 13, requestPath, extList);
    }

    @Override
    public boolean isReady() {
        return true; // Instance is only created after handshake completes
    }

    @Override
    public boolean isServer() {
        return this.server;
    }

    @Override
    public boolean isClient() {
        return !this.server;
    }

    @Override
    public String subProtocol() {
        return this.subProtocol;
    }

    @Override
    public int version() {
        return this.version;
    }

    @Override
    public String requestPath() {
        return this.requestPath;
    }

    @Override
    public String extensions() {
        if (this.extensions.isEmpty()) {
            return null;
        }
        return String.join(", ", this.extensions);
    }
}
