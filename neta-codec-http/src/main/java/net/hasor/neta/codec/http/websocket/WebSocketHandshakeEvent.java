/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;
import java.util.List;
import net.hasor.neta.codec.http.AbstractHttpEvent;
/**
 * Event published after the websocket opening handshake completes.
 * <p>
 * This event is produced centrally by
 * {@code AbstractWebSocketHandshake#finishWebSocketUpgrade(ProtoContext, WebSocketContext)}.
 * It is emitted after the handshake logic has installed the
 * {@link WebSocketContext} into both the current and root contexts and enabled
 * HTTP pass-through mode, then immediately published through the receive-side
 * event channel.
 * </p>
 * <p>
 * Sequence diagram:
 * <pre>
 * Publish the handshake event after handshake completion
 *   Client/Server Handshake     AbstractWebSocketHandshake        ProtoContext           Application listener
 *              |                           |                          |                           |
 *              | finishWebSocketUpgrade()  |                          |                           |
 *              |-------------------------->|                          |                           |
 *              |                           | install WebSocketContext |                           |
 *              |                           |------------------------->|                           |
 *              |                           | fireEventSnd(HttpThrough)|                           |
 *              |                           |------------------------->|                           |
 *              |                           | fireEventRcv(Handshake)  |                           |
 *              |                           |------------------------->|                           |
 *              |                           |                          | WebSocketHandshakeEvent   |
 *              |                           |                          |-------------------------->|
 * </pre>
 * <p>
 * Wraps the parsed {@link WebSocketContext} and exposes key handshake results.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-22
 */
public class WebSocketHandshakeEvent extends AbstractHttpEvent {
    private final WebSocketContext context;

    /**
     * Create the event from the parsed handshake context.
     * @param streamId streamId
     * @param webSocketContext context produced by the completed handshake
     */
    public WebSocketHandshakeEvent(long streamId, WebSocketContext webSocketContext) {
        if (webSocketContext == null) {
            throw new IllegalArgumentException("webSocketContext must not be null");
        }
        this.streamId(streamId);
        this.context = webSocketContext;
    }

    /**
     * Return {@code true} when the current endpoint is the server side.
     */
    public boolean isServer() {
        return this.context.isServer();
    }

    /**
     * Return {@code true} when the current endpoint is the client side.
     */
    public boolean isClient() {
        return this.context.isClient();
    }

    /**
     * Return the negotiated websocket version number.
     */
    public int version() {
        return this.context.version();
    }

    /**
     * Return the request path.
     */
    public String requestPath() {
        return this.context.requestPath();
    }

    /**
     * Return the request Host header.
     */
    public String requestHost() {
        return this.context.requestHost();
    }

    /**
     * Return the request Origin header.
     */
    public String requestOrigin() {
        return this.context.requestOrigin();
    }

    /**
     * Return the negotiated sub-protocol.
     */
    public String subProtocol() {
        return this.context.subProtocol();
    }

    /**
     * Return the negotiated extension header string.
     */
    public String extensions() {
        return this.context.extensions();
    }

    /**
     * Return the structured extension negotiation results.
     */
    public List<WebSocketExtensionResult> extensionResults() {
        return this.context.extensionList();
    }

    /**
     * Return whether the specified extension was negotiated successfully.
     * @param extensionName extension name
     * @return {@code true} if the extension exists in the negotiation result
     */
    public boolean hasExtension(String extensionName) {
        return this.context.hasExtension(extensionName);
    }

    /**
     * Return a compact summary string for the event.
     */
    @Override
    public String toString() {
        return "WebSocketHandshakeEvent{" + "server=" + this.isServer() + ", version=" + this.version() + ", path='" + this.requestPath() + '\'' + (this.requestHost() != null ? ", host='" + this.requestHost() + '\'' : "") + (this.subProtocol() != null ? ", subProtocol='" + this.subProtocol() + '\'' : "") + '}';
    }
}
