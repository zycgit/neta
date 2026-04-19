/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.nhttp.server.connector;

import java.nio.charset.StandardCharsets;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoExceptionHolder;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.websocket.WebSocketFrame;
import net.hasor.nhttp.server.WebSocketHandler;
import net.hasor.nhttp.server.WebSocketSession;

/**
 * IO-thread handler that dispatches decoded {@link WebSocketFrame} objects to the
 * application's {@link WebSocketHandler}.
 *
 * <p>The container layer ({@code RequestManager}) stores the active
 * {@link WebSocketHandler} and {@link WebSocketSession} in the pipeline context via
 * {@code context.rootContext(WebSocketHandler.class, handler)} and
 * {@code context.rootContext(WebSocketSession.class, session)} when processing the
 * {@link RequestDispatchCallback#onWebSocketOpen} callback. This handler retrieves
 * them for each incoming frame.</p>
 *
 * <h3>Frame dispatch</h3>
 * <ul>
 *   <li>{@code TEXT}: decoded as UTF-8 string, forwarded to
 *       {@link WebSocketHandler#onMessage(WebSocketSession, String)}.</li>
 *   <li>{@code BINARY}: forwarded as {@code byte[]} to
 *       {@link WebSocketHandler#onMessage(WebSocketSession, byte[])}.</li>
 *   <li>{@code PING}: responds with PONG automatically.</li>
 *   <li>{@code PONG}: ignored (no-op).</li>
 *   <li>{@code CLOSE}: marks session closed and notifies
 *       {@link WebSocketHandler#onClose}.</li>
 * </ul>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
class WebSocketFrameHandler implements ProtoHandler<WebSocketFrame, Object> {
    private static final Logger logger = Logger.getLogger(WebSocketFrameHandler.class);

    @Override
    public ProtoStatus onMessage(ProtoContext ctx, ProtoRcvQueue<WebSocketFrame> src, ProtoSndQueue<Object> dst) {
        while (src.hasMore()) {
            WebSocketFrame frame = src.takeMessage();
            if (frame != null) {
                dispatchFrame(ctx, frame);
            }
        }
        return ProtoStatus.Next;
    }

    private void dispatchFrame(ProtoContext ctx, WebSocketFrame frame) {
        WebSocketHandler wsHandler = ctx.rootContext(WebSocketHandler.class);
        WebSocketSession wsSession = ctx.rootContext(WebSocketSession.class);
        if (wsHandler == null || wsSession == null) {
            return;
        }

        try {
            switch (frame.opcode()) {
                case TEXT: {
                    ByteBuf textContent = frame.content();
                    String text = textContent.getString(textContent.readerIndex(), textContent.readableBytes(), StandardCharsets.UTF_8);
                    wsHandler.onMessage(wsSession, text);
                    break;
                }
                case BINARY: {
                    ByteBuf binContent = frame.content();
                    byte[] data = new byte[binContent.readableBytes()];
                    binContent.readBytes(data);
                    wsHandler.onMessage(wsSession, data);
                    break;
                }
                case PING:
                    try {
                        wsSession.sendPong();
                    } catch (Exception e) {
                        logger.warn("Failed to send PONG", e);
                    }
                    break;
                case PONG:
                    // No-op — PONG is an unsolicited keep-alive response
                    break;
                case CLOSE: {
                    int statusCode = 1000;
                    String reason = "";
                    ByteBuf closeContent = frame.content();
                    if (closeContent != null && closeContent.readableBytes() >= 2) {
                        statusCode = closeContent.readUInt16();
                        int remaining = closeContent.readableBytes();
                        if (remaining > 0) {
                            byte[] reasonBytes = new byte[remaining];
                            closeContent.readBytes(reasonBytes);
                            reason = new String(reasonBytes, StandardCharsets.UTF_8);
                        }
                    }
                    // Notify the application handler before we close the session.
                    wsHandler.onClose(wsSession, statusCode, reason);
                    // Echo the CLOSE frame back (per RFC 6455 §5.5.1) and mark the session
                    // closed so that subsequent isOpen() checks return false.
                    // DefaultWebSocketSession.close() is idempotent: if already closed it is
                    // a no-op, so calling it here is safe even if the handler called it too.
                    try {
                        wsSession.close(statusCode, reason);
                    } catch (Exception e) {
                        logger.warn("Failed to echo CLOSE frame", e);
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (Exception e) {
            logger.warn("Error dispatching WebSocket frame", e);
            wsHandler.onError(wsSession, e);
        }
    }

    @Override
    public void onClose(ProtoContext ctx) {
        WebSocketHandler wsHandler = ctx.rootContext(WebSocketHandler.class);
        WebSocketSession wsSession = ctx.rootContext(WebSocketSession.class);
        if (wsHandler != null && wsSession != null && wsSession.isOpen()) {
            try {
                wsHandler.onClose(wsSession, 1001, "Connection closed");
            } catch (Throwable e) {
                logger.warn("WebSocketHandler.onClose() threw an exception", e);
            }
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext ctx, Throwable e, ProtoExceptionHolder eh) {
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
