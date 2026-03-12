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
import net.hasor.neta.codec.http.FullHttpRequest;
import net.hasor.neta.codec.http.HttpHeaderNames;

/**
 * A synthetic message emitted into the pipeline immediately after a successful
 * WebSocket handshake.
 * <p>
 * This object is <b>not</b> a {@link WebSocketFrame}, so both
 * {@link WebSocketFrameDecoder} and {@link WebSocketFrameAggregator} pass it through
 * transparently — it reaches downstream handlers unchanged.
 * <p>
 * Downstream handlers can inspect this message to:
 * <ul>
 *   <li>Detect that the WebSocket handshake has completed.</li>
 *   <li>Read the negotiated sub-protocol ({@link #subProtocol()}).</li>
 *   <li>Read the negotiated extensions ({@link #extensions()}).</li>
 *   <li>Branch processing based on version or request path.</li>
 * </ul>
 * <h3>Typical usage in a pipeline handler</h3>
 * <pre>{@code
 * public ProtoStatus onMessage(ProtoContext ctx, ProtoRcvQueue<HttpObject> src,
 *                              ProtoSndQueue<HttpObject> dst) {
 *     while (src.hasMore()) {
 *         HttpObject msg = src.takeMessage();
 *         if (msg instanceof WebSocketHandshakeComplete) {
 *             WebSocketHandshakeComplete hs = (WebSocketHandshakeComplete) msg;
 *             String sub = hs.subProtocol();
 *             // Set up sub-protocol specific processing...
 *             continue;
 *         }
 *         // handle WebSocketMessage / WebSocketCloseMessage ...
 *     }
 *     return ProtoStatus.Next;
 * }
 * }</pre>
 * <h3>Pipeline placement</h3>
 * <pre>
 *   ctx.addLast("http", new HttpServerDuplexe());
 *   ctx.addLastDecoder("http-agg", new HttpObjectAggregator(65536));
 *   ctx.addLast("ws-handshake", new WebSocketServerDuplexer());
 *   ctx.addLastDecoder("ws-decoder", new WebSocketFrameDecoder());
 *   ctx.addLastDecoder("ws-aggregator", new WebSocketFrameAggregator());
 *   ctx.addLastEncoder("ws-encoder", new WebSocketFrameEncoder());
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-12
 * @see WebSocketServerDuplexer
 */
public class HandshakeWebSocketMessage extends AbstractWebSocketMessage {
    private final WebSocketVersion version;
    private final String           requestPath;
    private final String           subProtocol;
    private final String           extensions;

    /**
     * Creates a handshake-complete message.
     * @param version the negotiated WebSocket version
     * @param requestPath the request URI path (e.g. {@code "/chat"})
     * @param subProtocol the negotiated sub-protocol (may be {@code null})
     * @param extensions the negotiated extensions as comma-separated string (may be {@code null})
     */
    public HandshakeWebSocketMessage(WebSocketVersion version, String requestPath, String subProtocol, String extensions) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        this.initEmptyMessage();
        this.version = version;
        this.requestPath = requestPath;
        this.subProtocol = subProtocol;
        this.extensions = extensions;
    }

    public static HandshakeWebSocketMessage from(WebSocketVersion version, FullHttpRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }

        String protocol = request.getString(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        String extensions = request.getString(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);
        return new HandshakeWebSocketMessage(version, request.uri(), protocol, extensions);
    }

    /** Returns the negotiated WebSocket version. */
    public WebSocketVersion version() {
        return this.version;
    }

    /** Returns the request URI path, or {@code null} if not available. */
    public String requestPath() {
        return this.requestPath;
    }

    /** Returns the negotiated sub-protocol from the {@code Sec-WebSocket-Protocol} header, or {@code null} if no sub-protocol was negotiated. */
    public String subProtocol() {
        return this.subProtocol;
    }

    /** Returns the negotiated extensions as a comma-separated string, or {@code null} if none. */
    public String extensions() {
        return this.extensions;
    }

    @Override
    public WebSocketOpcode type() {
        return WebSocketOpcode.HANDSHAKE_COMPLETE;
    }

    @Override
    protected void recycle() {

    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("WebSocketHandshakeComplete{");
        sb.append("version=").append(this.version);
        if (this.requestPath != null) {
            sb.append(", path='").append(this.requestPath).append('\'');
        }
        if (this.subProtocol != null) {
            sb.append(", subProtocol='").append(this.subProtocol).append('\'');
        }
        if (this.extensions != null) {
            sb.append(", extensions='").append(this.extensions).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }
}