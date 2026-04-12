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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.bytebuf.CompositeByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoQueue;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.HttpEvent;

/**
 * Decode inbound websocket frames into message chunks and control events.
 * <p>
 * Data frames are converted into {@link WebSocketMessage} chunks while preserving
 * fragmentation semantics. Control frames are validated, emitted as events where
 * appropriate, or answered automatically for ping and close handling.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-22
 */
public class WebSocketInboundHandler implements ProtoHandler<WebSocketFrame, WebSocketMessage> {
    private static final Logger              logger                 = Logger.getLogger(WebSocketInboundHandler.class);
    private static final byte[]              EMPTY_BYTES            = new byte[0];
    private final        boolean             aggregateFragments;
    private final        int                 maxMessagePayloadLength;
    private              WebSocketOpcode     fragmentType;
    private              int                 fragmentSequence;
    private              boolean             closeReceived;
    private final        ProtoQueue<ByteBuf> aggregatedContentQueue = new ProtoQueue<>(-1);
    private              long                aggregatedStreamId;
    private              CharsetDecoder      textDecoder;
    private              byte[]              utf8CarryBytes         = EMPTY_BYTES;

    /**
     * Create an inbound handler with fragment passthrough and no size limit.
     */
    public WebSocketInboundHandler() {
        this(false, Integer.MAX_VALUE);
    }

    /**
     * Create an inbound handler and control whether fragmented messages are aggregated.
     * @param aggregateFragments whether fragmented messages should be aggregated
     */
    public WebSocketInboundHandler(boolean aggregateFragments) {
        this(aggregateFragments, Integer.MAX_VALUE);
    }

    /**
     * Create an inbound handler with fragment aggregation and message size limits.
     * @param aggregateFragments whether fragmented messages should be aggregated
     * @param maxMessagePayloadLength maximum allowed payload length per logical message
     */
    public WebSocketInboundHandler(boolean aggregateFragments, int maxMessagePayloadLength) {
        if (maxMessagePayloadLength <= 0) {
            throw new IllegalArgumentException("maxMessagePayloadLength must be greater than 0.");
        }

        this.aggregateFragments = aggregateFragments;
        this.maxMessagePayloadLength = maxMessagePayloadLength;
    }

    /**
     * Handle inbound-side events such as close, ping, and pong notifications.
     */
    @Override
    public boolean onEvent(ProtoContext context, SoEvent event) throws Throwable {
        Object eventData = event.getData();
        if (eventData instanceof WebSocketCloseEvent) {
            this.closeReceived = true;
            InternalUtils.markCloseReceived(context);
            resetFragmentState();
        } else if (eventData instanceof PingWebSocketEvent) {
            PingWebSocketEvent pingEvent = (PingWebSocketEvent) eventData;
            sendControlEventFrame(context, pingEvent, pingEvent.content(), true);
            return false;
        } else if (eventData instanceof PongWebSocketEvent) {
            PongWebSocketEvent pongEvent = (PongWebSocketEvent) eventData;
            sendControlEventFrame(context, pongEvent, pongEvent.content(), false);
            return false;
        }

        return true;
    }

    /**
     * Decode inbound websocket frames into messages and emitted control events.
     */
    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<WebSocketFrame> src, ProtoSndQueue<WebSocketMessage> dst) throws Throwable {
        while (src.hasMore()) {
            WebSocketFrame frame = src.takeMessage();
            if (frame == null) {
                continue;
            }

            try {
                if (this.closeReceived || InternalUtils.hasCloseReceived(context)) {
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

    /**
     * Reset decoder state after an inbound failure and swallow protocol violations.
     */
    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        resetState();
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
            WebSocketUtils.validateControlFrame(frame, resolveRemoteClientMode(context));
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
            resetState();
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "received a new data frame before fragmented message completion.");
        }

        ensureMessagePayloadLength(frame.content().readableBytes());

        WebSocketOpcode opcode = frame.opcode();
        if (opcode == WebSocketOpcode.TEXT) {
            startTextValidation();
            validateTextChunk(frame.content(), frame.isFinalFragment());
        }

        if (this.aggregateFragments && !frame.isFinalFragment()) {
            startAggregation(frame);
            return;
        }

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

        if (this.fragmentType == WebSocketOpcode.TEXT) {
            validateTextChunk(frame.content(), frame.isFinalFragment());
        }

        if (this.aggregateFragments) {
            appendAggregation(frame);
            if (frame.isFinalFragment()) {
                dst.offerMessage(finishAggregation());
            }
            return;
        }

        int sequence = frame.isFinalFragment() ? WebSocketMessage.FINAL_SEQUENCE : this.fragmentSequence++;
        WebSocketOpcode opcode = this.fragmentType;
        dst.offerMessage(createMessage(opcode, sequence, frame));
        if (frame.isFinalFragment()) {
            resetFragmentState();
        }
    }

    private WebSocketMessage createMessage(WebSocketOpcode opcode, int sequence, WebSocketFrame frame) {
        return createMessage(opcode, sequence, retainContent(frame.content()), frame.streamId());
    }

    private WebSocketMessage createMessage(WebSocketOpcode opcode, int sequence, ByteBuf content, long streamId) {
        if (opcode == WebSocketOpcode.TEXT) {
            return WebSocketUtils.textMessage(sequence, content).streamId(streamId);
        } else if (opcode == WebSocketOpcode.BINARY) {
            return WebSocketUtils.binaryMessage(sequence, content).streamId(streamId);
        } else {
            throw new WebSocketProtocolViolationException(WebSocketCode.PROTOCOL_ERROR, "unsupported websocket data opcode: " + opcode);
        }
    }

    private void startAggregation(WebSocketFrame frame) {
        this.fragmentType = frame.opcode();
        this.fragmentSequence = 1;
        this.aggregatedStreamId = frame.streamId();
        this.aggregatedContentQueue.offerMessage(frame.content().retain());
    }

    private void appendAggregation(WebSocketFrame frame) {
        ByteBuf content = frame.content();
        ensureMessagePayloadLength(this.aggregatedReadableBytes() + content.readableBytes());
        this.aggregatedContentQueue.offerMessage(content.retain());
    }

    private WebSocketMessage finishAggregation() {
        ByteBuf content = this.takeAggregatedContent();
        WebSocketOpcode opcode = this.fragmentType;
        long streamId = this.aggregatedStreamId;

        this.aggregatedStreamId = 0;
        resetFragmentState();

        return createMessage(opcode, WebSocketMessage.FINAL_SEQUENCE, content, streamId);
    }

    private void handlePing(ProtoContext context, WebSocketFrame frame) {
        WebSocketMessage pong = InternalWebSocketMessage.of(frame.streamId(), WebSocketOpcode.PONG, retainContent(frame.content()));
        context.sendData(pong);
    }

    private void handlePong(ProtoContext context, WebSocketFrame frame) {
        PongWebSocketEvent event = WebSocketUtils.pongEvent(retainContent(frame.content()));
        event.streamId(frame.streamId());
        fireEvent(context, PongWebSocketEvent.class, event);
    }

    private void handleProtocolViolation(ProtoContext context, WebSocketFrame frame, WebSocketProtocolViolationException e) {
        this.closeReceived = true;
        InternalUtils.markCloseReceived(context);
        resetState();

        if (InternalUtils.hasCloseSent(context)) {
            InternalUtils.executeCloseAction(context, WebSocketCloseType.TERMINATE);
            return;
        }

        WebSocketMessage closeReply = InternalWebSocketMessage.of(frame.streamId(), WebSocketOpcode.CLOSE, closePayload(e.closeStatusCode()));
        InternalUtils.markCloseSent(context);
        InternalUtils.executeCloseAction(context, WebSocketCloseType.SEND_CLOSE_AND_TERMINATE, context.sendData(closeReply));
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

        int statusCode = WebSocketCode.NO_STATUS;
        String reason = null;
        if (content != null && content.readableBytes() >= 2) {
            statusCode = ((content.getByte(0) & 0xFF) << 8) | (content.getByte(1) & 0xFF);
            int reasonLen = content.readableBytes() - 2;
            if (reasonLen > 0) {
                byte[] reasonBytes = new byte[reasonLen];
                content.getBytes(2, reasonBytes, 0, reasonLen);
                reason = InternalUtils.decodeUtf8(reasonBytes, "close frame reason must be valid UTF-8.");
            }
        }

        this.closeReceived = true;
        InternalUtils.markCloseReceived(context);
        resetFragmentState();

        WebSocketCloseEvent event = new WebSocketCloseEvent(statusCode, reason);
        event.streamId(frame.streamId());
        fireEvent(context, WebSocketCloseEvent.class, event);

        if (InternalUtils.hasCloseSent(context)) {
            InternalUtils.executeCloseAction(context, WebSocketCloseType.TERMINATE);
            return;
        }

        ByteBuf replyContent = buildCloseReplyContent(context, content);
        WebSocketMessage closeReply = InternalWebSocketMessage.of(frame.streamId(), WebSocketOpcode.CLOSE, replyContent);
        InternalUtils.markCloseSent(context);
        InternalUtils.executeCloseAction(context, WebSocketCloseType.SEND_CLOSE_AND_TERMINATE, context.sendData(closeReply));
    }

    private void sendControlEventFrame(ProtoContext context, HttpEvent event, ByteBuf content, boolean ping) {
        try {
            WebSocketMessage controlMessage;
            if (ping) {
                controlMessage = InternalWebSocketMessage.of(event.streamId(), WebSocketOpcode.PING, retainContent(content));
            } else {
                controlMessage = InternalWebSocketMessage.of(event.streamId(), WebSocketOpcode.PONG, retainContent(content));
            }
            context.sendData(controlMessage);
        } finally {
            event.release();
        }
    }

    private static boolean isControlOpcode(WebSocketOpcode opcode) {
        return opcode == WebSocketOpcode.PING || opcode == WebSocketOpcode.PONG || opcode == WebSocketOpcode.CLOSE;
    }

    private <T> void fireEvent(ProtoContext context, Class<T> eventType, T event) {
        try {
            context.fireEvent(eventType, event);
        } catch (Throwable e) {
            logger.error("Error occurred while publishing websocket event: " + eventType.getSimpleName(), e);
        }
    }

    private void ensureMessagePayloadLength(int payloadLength) {
        if (payloadLength > this.maxMessagePayloadLength) {
            throw new WebSocketProtocolViolationException(WebSocketCode.MESSAGE_TOO_BIG, "websocket message payload exceeds configured max length: " + payloadLength + " > " + this.maxMessagePayloadLength);
        }
    }

    private void resetState() {
        releaseAggregatedContent();
        resetFragmentState();
        resetTextValidation();
    }

    private void releaseAggregatedContent() {
        this.aggregatedContentQueue.skipMessage(this.aggregatedContentQueue.queueSize());
        this.aggregatedStreamId = 0;
    }

    private int aggregatedReadableBytes() {
        int readable = 0;
        for (ByteBuf buf : this.aggregatedContentQueue.peekMessage(this.aggregatedContentQueue.queueSize())) {
            if (buf != null) {
                readable += buf.readableBytes();
            }
        }
        return readable;
    }

    private ByteBuf takeAggregatedContent() {
        int size = this.aggregatedContentQueue.queueSize();
        if (size <= 0) {
            return ByteBuf.EMPTY;
        }
        if (size == 1) {
            return this.aggregatedContentQueue.takeMessage();
        }

        CompositeByteBuf composite = ByteBufUtils.compositeBuffer();
        for (ByteBuf buf : this.aggregatedContentQueue.takeMessage(size)) {
            composite.addComponent(buf);
        }
        return composite;
    }

    private void resetFragmentState() {
        this.fragmentType = null;
        this.fragmentSequence = 0;
    }

    private void startTextValidation() {
        this.textDecoder = StandardCharsets.UTF_8.newDecoder();
        this.textDecoder.onMalformedInput(CodingErrorAction.REPORT);
        this.textDecoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        this.utf8CarryBytes = EMPTY_BYTES;
    }

    private void resetTextValidation() {
        this.textDecoder = null;
        this.utf8CarryBytes = EMPTY_BYTES;
    }

    private void validateTextChunk(ByteBuf content, boolean finalFragment) {
        if (this.textDecoder == null) {
            startTextValidation();
        }

        byte[] incoming = toBytes(content);
        byte[] source;
        if (this.utf8CarryBytes.length == 0) {
            source = incoming;
        } else {
            source = new byte[this.utf8CarryBytes.length + incoming.length];
            System.arraycopy(this.utf8CarryBytes, 0, source, 0, this.utf8CarryBytes.length);
            System.arraycopy(incoming, 0, source, this.utf8CarryBytes.length, incoming.length);
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(source);
        CharBuffer charBuffer = CharBuffer.allocate(Math.max(1, source.length));
        try {
            while (true) {
                CoderResult decode = this.textDecoder.decode(byteBuffer, charBuffer, finalFragment);
                if (decode.isOverflow()) {
                    charBuffer.clear();
                    continue;
                }
                if (decode.isError()) {
                    decode.throwException();
                }
                break;
            }

            if (finalFragment) {
                while (true) {
                    CoderResult flush = this.textDecoder.flush(charBuffer);
                    if (flush.isOverflow()) {
                        charBuffer.clear();
                        continue;
                    }
                    if (flush.isError()) {
                        flush.throwException();
                    }
                    break;
                }
                if (byteBuffer.hasRemaining()) {
                    throw new CharacterCodingException();
                }
                resetTextValidation();
                return;
            }

            if (byteBuffer.hasRemaining()) {
                this.utf8CarryBytes = new byte[byteBuffer.remaining()];
                byteBuffer.get(this.utf8CarryBytes);
            } else {
                this.utf8CarryBytes = EMPTY_BYTES;
            }
        } catch (CharacterCodingException e) {
            resetTextValidation();
            throw new WebSocketProtocolViolationException(WebSocketCode.INVALID_DATA, "text frame payload must be valid UTF-8.", e);
        }
    }

    private byte[] toBytes(ByteBuf content) {
        if (content == null || content.readableBytes() == 0) {
            return EMPTY_BYTES;
        }
        byte[] bytes = new byte[content.readableBytes()];
        content.getBytes(0, bytes, 0, bytes.length);
        return bytes;
    }

    private ByteBuf buildCloseReplyContent(ProtoContext context, ByteBuf content) {
        if (content == null || content.readableBytes() == 0) {
            return ByteBuf.EMPTY;
        }

        int statusCode = ((content.getByte(0) & 0xFF) << 8) | (content.getByte(1) & 0xFF);
        try {
            InternalUtils.validateCloseStatusCode(statusCode, resolveLocalClientMode(context));
            return retainContent(content);
        } catch (WebSocketProtocolViolationException e) {
            return ByteBuf.EMPTY;
        }
    }

    private boolean resolveRemoteClientMode(ProtoContext context) {
        WebSocketContext webSocketContext = WebSocketRegistry.resolve(context);
        return webSocketContext != null && webSocketContext.isServer();
    }

    private boolean resolveLocalClientMode(ProtoContext context) {
        WebSocketContext webSocketContext = WebSocketRegistry.resolve(context);
        return webSocketContext != null && webSocketContext.isClient();
    }

    /**
     * Clear frame-aggregation state and close markers when the channel closes.
     */
    @Override
    public void onClose(ProtoContext context) {
        resetState();
        this.closeReceived = false;
        InternalUtils.clearCloseState(context);
    }
}