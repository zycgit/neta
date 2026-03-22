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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;

/**
 * Handles inbound websocket frames and exposes TEXT/BINARY data as message chunks.
 * <p>
 * This handler does not aggregate fragmented websocket messages. Instead it converts each data
 * frame into a {@link WebSocketMessage} chunk and marks chunk boundaries through
 * {@link WebSocketMessage#sequence()}.
 * <p>
 * Control-frame behavior is asymmetric by design:
 * <ul>
 *   <li>PING -> auto PONG, no inbound message published</li>
 *   <li>PONG -> published as {@link PongWebSocketEvent}</li>
 *   <li>CLOSE -> close reply + {@link WebSocketCloseEvent}</li>
 * </ul>
 */
public class WebSocketInboundHandler implements ProtoHandler<WebSocketFrame, WebSocketMessage> {
    private static final Logger          logger = Logger.getLogger(WebSocketInboundHandler.class);
    private              WebSocketOpcode fragmentType;
    private              int             fragmentSequence;
    private              boolean         closeReceived;

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event) throws Throwable {
        Object eventData = event.getData();
        if (eventData instanceof WebSocketCloseEvent) {
            this.closeReceived = true;
            resetFragmentState();
        } else if (eventData instanceof PingWebSocketEvent) {
            sendControlEventFrame(context, (PingWebSocketEvent) eventData, true);
            return false;
        } else if (eventData instanceof PongWebSocketEvent) {
            sendControlEventFrame(context, (PongWebSocketEvent) eventData, false);
            return false;
        }
        return true;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<WebSocketFrame> src, ProtoSndQueue<WebSocketMessage> dst) throws Throwable {
        while (src.hasMore()) {
            WebSocketFrame frame = src.takeMessage();
            if (frame == null) {
                continue;
            }

            try {
                if (this.closeReceived) {
                    continue;
                }
                handleFrame(context, frame, dst);
            } catch (WebSocketProtocolViolationException e) {
                handleProtocolViolation(context, frame, e);
            } finally {
                frame.release();
            }
        }
        return ProtoStatus.Next;
    }

    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        resetFragmentState();
        if (e instanceof WebSocketProtocolViolationException) {
            eh.clear();
        }
        return ProtoStatus.Next;
    }

    private void handleFrame(ProtoContext context, WebSocketFrame frame, ProtoSndQueue<WebSocketMessage> dst) throws Throwable {
        WebSocketOpcode opcode = frame.opcode();
        if (opcode == null) {
            resetFragmentState();
            return;
        }

        if (isControlOpcode(opcode)) {
            validateControlFrame(frame);
        }

        switch (opcode) {
            case TEXT:
            case BINARY:
                handleDataFrame(frame, dst);
                return;
            case CONTINUATION:
                handleContinuation(frame, dst);
                return;
            case PING:
                handlePing(context, frame);
                return;
            case PONG:
                handlePong(context, frame);
                return;
            case CLOSE:
                handleClose(context, frame);
                return;
            default:
        }
    }

    private void handleDataFrame(WebSocketFrame frame, ProtoSndQueue<WebSocketMessage> dst) {
        if (this.fragmentType != null) {
            resetFragmentState();
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "received a new data frame before fragmented message completion.");
        }

        WebSocketOpcode opcode = frame.opcode();
        int sequence = frame.isFinalFragment() ? WebSocketMessage.FINAL_SEQUENCE : WebSocketMessage.START_SEQUENCE;
        dst.offerMessage(createMessage(opcode, sequence, frame));
        if (!frame.isFinalFragment()) {
            this.fragmentType = opcode;
            this.fragmentSequence = 1;
        }
    }

    private void handleContinuation(WebSocketFrame frame, ProtoSndQueue<WebSocketMessage> dst) {
        if (this.fragmentType == null) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "received continuation frame outside fragmented message.");
        }

        int sequence = frame.isFinalFragment() ? WebSocketMessage.FINAL_SEQUENCE : this.fragmentSequence++;
        WebSocketOpcode opcode = this.fragmentType;
        dst.offerMessage(createMessage(opcode, sequence, frame));
        if (frame.isFinalFragment()) {
            resetFragmentState();
        }
    }

    private WebSocketMessage createMessage(WebSocketOpcode opcode, int sequence, WebSocketFrame frame) {
        if (opcode == WebSocketOpcode.TEXT) {
            return WebSocketUtils.textMessage(sequence, frame.content()).streamId(frame.streamId());
        }
        if (opcode == WebSocketOpcode.BINARY) {
            return WebSocketUtils.binaryMessage(sequence, frame.content()).streamId(frame.streamId());
        }
        throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "unsupported websocket data opcode: " + opcode);
    }

    private void handlePing(ProtoContext context, WebSocketFrame frame) {
        WebSocketMessage pong = InternalWebSocketMessage.of(WebSocketOpcode.PONG, retainContent(frame.content())).streamId(frame.streamId());
        context.sendData(pong);
    }

    private void handlePong(ProtoContext context, WebSocketFrame frame) {
        PongWebSocketEvent event = WebSocketUtils.pongEvent(retainContent(frame.content()));
        event.streamId(frame.streamId());
        fireEvent(context, PongWebSocketEvent.class, event);
    }

    private void handleProtocolViolation(ProtoContext context, WebSocketFrame frame, WebSocketProtocolViolationException e) {
        this.closeReceived = true;
        resetFragmentState();

        WebSocketMessage closeReply = InternalWebSocketMessage.of(WebSocketOpcode.CLOSE, closePayload(e.closeStatusCode())).streamId(frame.streamId());
        context.sendData(closeReply);
    }

    private static ByteBuf retainContent(ByteBuf content) {
        return content == null ? ByteBuf.EMPTY : content.retain();
    }

    private static ByteBuf closePayload(int statusCode) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(2, Integer.MAX_VALUE);
        buf.writeByte((byte) ((statusCode >> 8) & 0xFF));
        buf.writeByte((byte) (statusCode & 0xFF));
        buf.markWriter();
        return buf;
    }

    private void handleClose(ProtoContext context, WebSocketFrame frame) throws Throwable {
        ByteBuf content = frame.content();
        validateClosePayload(content);

        int statusCode = WebSocketCode.NO_STATUS;
        String reason = null;
        if (content != null && content.readableBytes() >= 2) {
            statusCode = ((content.getByte(0) & 0xFF) << 8) | (content.getByte(1) & 0xFF);
            validateCloseStatusCode(statusCode);
            int reasonLen = content.readableBytes() - 2;
            if (reasonLen > 0) {
                byte[] reasonBytes = new byte[reasonLen];
                content.getBytes(2, reasonBytes, 0, reasonLen);
                reason = decodeUtf8(reasonBytes);
            }
        }

        this.closeReceived = true;
        resetFragmentState();

        WebSocketMessage closeReply = InternalWebSocketMessage.of(WebSocketOpcode.CLOSE, retainContent(frame.content())).streamId(frame.streamId());
        context.sendData(closeReply);

        WebSocketCloseEvent event = new WebSocketCloseEvent(statusCode, reason);
        event.streamId(frame.streamId());
        fireEvent(context, WebSocketCloseEvent.class, event);
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

    private static boolean isControlOpcode(WebSocketOpcode opcode) {
        return opcode == WebSocketOpcode.PING || opcode == WebSocketOpcode.PONG || opcode == WebSocketOpcode.CLOSE;
    }

    private void validateControlFrame(WebSocketFrame frame) {
        if (!frame.isFinalFragment()) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "control frames must not be fragmented.");
        }
        ByteBuf content = frame.content();
        if (content != null && content.readableBytes() > 125) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "control frame payload must not exceed 125 bytes.");
        }
    }

    private void validateClosePayload(ByteBuf content) {
        if (content != null && content.readableBytes() == 1) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "close frame payload must be either empty or at least 2 bytes.");
        }
    }

    private void validateCloseStatusCode(int statusCode) {
        if (!isValidCloseStatusCode(statusCode)) {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "close frame status code is invalid: " + statusCode);
        }
    }

    private static boolean isValidCloseStatusCode(int statusCode) {
        if (statusCode < WebSocketCode.NORMAL_CLOSURE || statusCode >= 5000) {
            return false;
        }
        if (statusCode == WebSocketCode.NO_STATUS || statusCode == WebSocketCode.ABNORMAL_CLOSURE) {
            return false;
        }
        if (statusCode == WebSocketCode.RESERVED || statusCode == WebSocketCode.MANDATORY_EXTENSION || //
                statusCode == 1012 || statusCode == 1013 || statusCode == 1014 || statusCode == WebSocketCode.TLS_HANDSHAKE) {
            return false;
        }
        return statusCode < 1016 || statusCode >= 3000;
    }

    private String decodeUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new WebSocketProtocolViolationException(WebSocketCode.INVALID_DATA, "close frame reason must be valid UTF-8.", e);
        }
    }

    private <T> void fireEvent(ProtoContext context, Class<T> eventType, T event) {
        try {
            context.fireUserEvent(eventType, event);
        } catch (Throwable e) {
            logger.error("Error occurred while publishing websocket event: " + eventType.getSimpleName(), e);
        }
    }

    private void resetFragmentState() {
        this.fragmentType = null;
        this.fragmentSequence = 0;
    }

    @Override
    public void onClose(ProtoContext context) {
        resetFragmentState();
        this.closeReceived = false;
    }
}