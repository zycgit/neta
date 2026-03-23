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
import java.util.concurrent.ThreadLocalRandom;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;

/**
 * Converts outbound message chunks and control events into {@link WebSocketFrame} flow.
 * <p>
 * Function:
 * <pre>
 *   map TEXT/BINARY message chunks to frames
 *   preserve fragmentation through sequence markers
 *   generate ping, pong, and close control frames
 * </pre>
 * <p>
 * pipeline view:
 * <pre>
 *   WebSocketMessage / WebSocket events -> WebSocketOutboundHandler -> WebSocketFrame
 * </pre>
 * <p>
 * Typical usage:
 * <pre>
 *   ctx.addLastEncoder("ws-outbound", new WebSocketOutboundHandler());
 * </pre>
 */
public class WebSocketOutboundHandler implements ProtoHandler<WebSocketMessage, WebSocketFrame> {
    private WebSocketOpcode fragmentType;
    private int             expectedSequence;

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event) throws Throwable {
        Object eventData = event.getData();
        if (eventData instanceof PingWebSocketEvent) {
            sendControlEventFrame(context, (PingWebSocketEvent) eventData, true);
            return false;
        } else if (eventData instanceof PongWebSocketEvent) {
            sendControlEventFrame(context, (PongWebSocketEvent) eventData, false);
            return false;
        }
        return true;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<WebSocketMessage> src, ProtoSndQueue<WebSocketFrame> dst) throws Throwable {
        while (src.hasMore()) {
            WebSocketMessage msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            boolean consumed = false;
            try {
                dst.offerMessage(this.toFrame(context, msg));
                consumed = true;
            } finally {
                if (consumed) {
                    msg.release();
                }
            }
        }
        return ProtoStatus.Next;
    }

    private WebSocketFrame toFrame(ProtoContext context, WebSocketMessage msg) {
        WebSocketOpcode type = msg.type();
        int sequence = msg.sequence();
        boolean masked = masked(context);
        if (type == null) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "WebSocket message type must not be null.");
        }

        WebSocketFrame frame;
        if (type == WebSocketOpcode.PING || type == WebSocketOpcode.PONG || type == WebSocketOpcode.CLOSE) {
            frame = buildControlMessage(context, msg, masked);
        } else if (type != WebSocketOpcode.TEXT && type != WebSocketOpcode.BINARY) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "only TEXT, BINARY, and internal control WebSocketMessage types are encodable. actual=" + type);
        } else if (sequence == WebSocketMessage.FINAL_SEQUENCE) {
            frame = buildFinalChunk(msg, masked);
        } else if (sequence == WebSocketMessage.START_SEQUENCE) {
            frame = buildStartChunk(msg, masked);
        } else if (sequence > 0) {
            frame = buildMiddleChunk(msg, masked);
        } else {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "WebSocket message sequence is invalid: " + sequence);
        }
        frame.streamId(msg.streamId());
        return frame;
    }

    private void sendControlEventFrame(ProtoContext context, AbstractWebSocketEvent event, boolean ping) {
        try {
            ByteBuf content = event instanceof PingWebSocketEvent ? ((PingWebSocketEvent) event).content() : ((PongWebSocketEvent) event).content();
            WebSocketMessage controlMessage = InternalWebSocketMessage.of(ping ? WebSocketOpcode.PING : WebSocketOpcode.PONG, content).streamId(event.streamId());
            context.sendData(controlMessage);
        } finally {
            event.release();
        }
    }

    private WebSocketFrame buildControlMessage(ProtoContext context, WebSocketMessage msg, boolean masked) {
        if (msg.sequence() != WebSocketMessage.FINAL_SEQUENCE) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "control WebSocketMessage sequence must be FINAL_SEQUENCE.");
        }
        return buildControlFrame(msg.type(), retainContent(msg.content()), masked);
    }

    private WebSocketFrame buildControlFrame(WebSocketOpcode opcode, ByteBuf content, boolean masked) {
        if (content.readableBytes() > 125) {
            content.release();
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "control WebSocketMessage payload must not exceed 125 bytes.");
        }
        if (this.fragmentType != null) {
            content.release();
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "control WebSocketMessage must not be sent during fragmented websocket message streaming.");
        }
        if (opcode == WebSocketOpcode.PING) {
            return WebSocketUtils.pingFrame(masked, maskingKey(masked), content);
        }
        if (opcode == WebSocketOpcode.PONG) {
            return WebSocketUtils.pongFrame(masked, maskingKey(masked), content);
        }
        if (opcode == WebSocketOpcode.CLOSE) {
            return WebSocketUtils.closeFrame(masked, maskingKey(masked), content);
        }
        content.release();
        throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "unsupported internal control opcode: " + opcode);
    }

    private WebSocketFrame buildStartChunk(WebSocketMessage msg, boolean masked) {
        if (this.fragmentType != null) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "fragmented websocket message already started.");
        }

        this.fragmentType = msg.type();
        this.expectedSequence = 1;
        if (msg.type() == WebSocketOpcode.TEXT) {
            return WebSocketUtils.textFrame(false, masked, maskingKey(masked), retainContent(msg.content()));
        }
        return WebSocketUtils.binaryFrame(false, masked, maskingKey(masked), retainContent(msg.content()));
    }

    private WebSocketFrame buildMiddleChunk(WebSocketMessage msg, boolean masked) {
        if (this.fragmentType == null) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "continuation message chunk requires an active fragmented websocket message.");
        }
        if (msg.type() != this.fragmentType) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "message chunk type mismatch inside fragmented websocket message.");
        }
        if (msg.sequence() != this.expectedSequence) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "fragmented websocket message sequence mismatch. expected=" + this.expectedSequence + ", actual=" + msg.sequence());
        }

        this.expectedSequence++;
        return WebSocketUtils.continuationFrame(false, masked, maskingKey(masked), retainContent(msg.content()));
    }

    private WebSocketFrame buildFinalChunk(WebSocketMessage msg, boolean masked) {
        if (this.fragmentType == null) {
            if (msg.type() == WebSocketOpcode.TEXT) {
                return WebSocketUtils.textFrame(true, masked, maskingKey(masked), retainContent(msg.content()));
            }
            return WebSocketUtils.binaryFrame(true, masked, maskingKey(masked), retainContent(msg.content()));
        }
        if (msg.type() != this.fragmentType) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "final message chunk type mismatch inside fragmented websocket message.");
        }

        WebSocketFrame frame = WebSocketUtils.continuationFrame(true, masked, maskingKey(masked), retainContent(msg.content()));
        this.fragmentType = null;
        this.expectedSequence = 0;
        return frame;
    }

    @Override
    public void onClose(ProtoContext context) {
        resetState();
    }

    private void resetState() {
        this.fragmentType = null;
        this.expectedSequence = 0;
    }

    private boolean masked(ProtoContext context) {
        return resolveClientMode(context) && resolveVersion(context).isRfc6455Framing();
    }

    private byte[] maskingKey(boolean masked) {
        return masked ? newMaskingKey() : null;
    }

    private WebSocketVersion resolveVersion(ProtoContext context) {
        WebSocketContext wsContext = requireHandshakeContext(context);
        WebSocketVersion detectedVersion = WebSocketVersion.of(wsContext.version());
        if (detectedVersion != null) {
            return detectedVersion;
        }
        throw new IllegalStateException("WebSocketContext contains unsupported version: " + wsContext.version());
    }

    private boolean resolveClientMode(ProtoContext context) {
        return requireHandshakeContext(context).isClient();
    }

    private WebSocketContext requireHandshakeContext(ProtoContext context) {
        WebSocketContext wsContext = context.context(WebSocketContext.class);
        if (wsContext != null && wsContext.isReady()) {
            return wsContext;
        }
        throw new IllegalStateException("WebSocketOutboundHandler requires WebSocketContext from a completed handshake.");
    }

    private static ByteBuf retainContent(ByteBuf content) {
        return content == null ? ByteBuf.EMPTY : content.retain();
    }

    static byte[] newMaskingKeyForEvent() {
        int random = ThreadLocalRandom.current().nextInt();
        return new byte[] { (byte) (random >> 24), (byte) (random >> 16), (byte) (random >> 8), (byte) random };
    }

    private static byte[] newMaskingKey() {
        return newMaskingKeyForEvent();
    }
}