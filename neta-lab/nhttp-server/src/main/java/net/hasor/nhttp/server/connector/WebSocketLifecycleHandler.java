/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.connector;

import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.websocket.WebSocketHandshakeEvent;
import net.hasor.nhttp.server.WebSocketHandler;
import net.hasor.nhttp.server.WebSocketSession;

/**
 * IO-thread handler that intercepts {@link WebSocketHandshakeEvent} to notify the
 * container layer of a new WebSocket connection, and forwards connection-close
 * notifications on TCP disconnect.
 *
 * <h3>Responsibilities</h3>
 * <ol>
 *   <li>On {@link WebSocketHandshakeEvent}: delegates to
 *       {@link RequestDispatchCallback#onWebSocketOpen} so the container can create
 *       the {@link WebSocketSession} and invoke the application
 *       {@link WebSocketHandler}. The container stores the handler and session in
 *       {@code context.rootContext()} for retrieval by
 *       {@link WebSocketFrameHandler}.</li>
 *   <li>On TCP close ({@link #onClose}): delegates to
 *       {@link RequestDispatchCallback#onConnectionClose} so the container can
 *       clean up any active WebSocket or HTTP session on this connection.</li>
 *   <li>{@link #onMessage}: pass-through — forwards every {@link HttpObject}
 *       downstream unchanged.</li>
 * </ol>
 *
 * <p>This handler is placed at the top of the application-layer pipeline, before
 * the HTTP / WebSocket routing fork, so it sees all connections regardless of
 * protocol.</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
class WebSocketLifecycleHandler implements ProtoHandler<HttpObject, HttpObject> {
    private static final Logger logger = Logger.getLogger(WebSocketLifecycleHandler.class);

    private final boolean                 secure;
    private final RequestDispatchCallback callback;

    WebSocketLifecycleHandler(boolean secure, RequestDispatchCallback callback) {
        this.secure = secure;
        this.callback = callback;
    }

    @Override
    public boolean onEvent(ProtoContext ctx, SoEvent event) {
        Object data = event.getData();
        if (data instanceof WebSocketHandshakeEvent) {
            WebSocketHandshakeEvent handshake = (WebSocketHandshakeEvent) data;
            // Skip if container already registered a session on this context (e.g. reconnect race)
            if (ctx.rootContext(WebSocketSession.class) != null) {
                return true;
            }
            NetChannel channel = (NetChannel) ctx.getChannel();
            try {
                this.callback.onWebSocketOpen(ctx, handshake, channel, this.secure);
            } catch (Throwable e) {
                logger.warn("RequestDispatchCallback.onWebSocketOpen() threw an exception", e);
            }
        }
        return true;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext ctx, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) {
        // Pass every HttpObject downstream unchanged
        while (src.hasMore()) {
            dst.offerMessage(src.takeMessage());
        }
        return ProtoStatus.Next;
    }

    /**
     * Connection (or stream) close. For HTTP/1.1, this fires when the TCP connection
     * closes. For HTTP/2, it fires per stream.
     *
     * <p>TCP-level connection lifecycle (open/close) is handled by
     * {@link ConnectionLifecycleHandler} at the outermost pipeline layer; this
     * method does not forward the event to {@link RequestDispatchCallback}.</p>
     */
    @Override
    public void onClose(ProtoContext ctx) {
        // TCP connection lifecycle is already reported by ConnectionLifecycleHandler.
        // Nothing to do here for non-WebSocket connections.
    }

    @Override
    public ProtoStatus onError(ProtoContext ctx, Throwable e, ProtoExceptionHolder eh) {
        // Forward error to an active WebSocket handler if present
        WebSocketHandler wsHandler = ctx.rootContext(WebSocketHandler.class);
        WebSocketSession wsSession = ctx.rootContext(WebSocketSession.class);
        if (wsHandler != null && wsSession != null) {
            try {
                wsHandler.onError(wsSession, e);
            } catch (Throwable t) {
                logger.warn("WebSocketHandler.onError() threw an exception", t);
            }
        }
        return ProtoStatus.Next;
    }
}
