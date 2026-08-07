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
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.bytebuf.CompositeByteBuf;
import net.hasor.neta.channel.*;
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
    private static final Logger               logger                      = Logger.getLogger(WebSocketInboundHandler.class);
    private static final byte[]               EMPTY_BYTES                 = new byte[0];
    private static final int                  UTF8_SCRATCH_SIZE           = 4096;
    private static final ThreadLocal<byte[]>  UTF8_SCRATCH                = ThreadLocal.withInitial(() -> new byte[UTF8_SCRATCH_SIZE]);
    private final boolean                     aggregateFragments;
    private final int                         maxMessagePayloadLength;
    private WebSocketOpcode                   fragmentType;
    private int                               fragmentSequence;
    private boolean                           closeReceived;
    private CompositeByteBuf                  aggregatedContent;
    private long                              aggregatedStreamId;
    private int                               aggregatedReadableBytes;
    private int                               utf8PendingBytes;
    private int                               utf8CodePoint;
    private int                               utf8MinCodePoint;

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
        while (true) {
            WebSocketFrame frame = src.takeMessage();
            if (frame == null) {
                break;
            }

            try {
                if (this.closeReceived || InternalUtils.hasCloseReceived(context)) {
                    continue;
                }
                handleFrame(context, frame, dst);
            } catch (WebSocketProtocolViolationException e) {
                handleProtocolViolation(context, frame, e);
            } finally {
                if (frame != null) {
                    frame.release();
                }
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
                return;
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
            if (!frame.isFinalFragment()) {
                appendAggregation(frame);
                return;
            }

            if (frame.isFinalFragment()) {
                ensureMessagePayloadLength(this.aggregatedReadableBytes + frame.content().readableBytes());
                dst.offerMessage(finishAggregation(frame));
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
        return createMessage(opcode, sequence, transferContent(frame), frame.streamId());
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
        this.aggregatedReadableBytes = 0;
        this.addAggregatedComponent(transferContent(frame));
    }

    private void appendAggregation(WebSocketFrame frame) {
        ByteBuf content = frame.content();
        ensureMessagePayloadLength(this.aggregatedReadableBytes + content.readableBytes());
        this.addAggregatedComponent(transferContent(frame));
    }

    private WebSocketMessage finishAggregation(WebSocketFrame finalFrame) {
        ByteBuf content = this.takeAggregatedContent(finalFrame);
        WebSocketOpcode opcode = this.fragmentType;
        long streamId = this.aggregatedStreamId;

        this.aggregatedStreamId = 0;
        resetFragmentState();

        return createMessage(opcode, WebSocketMessage.FINAL_SEQUENCE, content, streamId);
    }

    private void handlePing(ProtoContext context, WebSocketFrame frame) {
        WebSocketMessage pong = InternalWebSocketMessage.of(frame.streamId(), WebSocketOpcode.PONG, transferContent(frame));
        context.sendData(pong);
    }

    private void handlePong(ProtoContext context, WebSocketFrame frame) {
        PongWebSocketEvent event = WebSocketUtils.pongEvent(transferContent(frame));
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

    private static ByteBuf transferContent(WebSocketFrame frame) {
        ByteBuf content = frame == null ? null : frame.transferContent();
        return content == null ? ByteBuf.EMPTY : content;
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

        ByteBuf replyContent = buildCloseReplyContent(context, frame, content);
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
        if (this.aggregatedContent != null) {
            this.aggregatedContent.release();
            this.aggregatedContent = null;
        }
        this.aggregatedStreamId = 0;
        this.aggregatedReadableBytes = 0;
    }

    private ByteBuf takeAggregatedContent(WebSocketFrame finalFrame) {
        ByteBuf finalContent = finalFrame == null ? null : finalFrame.content();
        int finalReadable = finalContent == null ? 0 : finalContent.readableBytes();

        if (this.aggregatedContent == null) {
            return finalReadable <= 0 ? ByteBuf.EMPTY : transferContent(finalFrame);
        }

        if (finalReadable > 0) {
            this.addAggregatedComponent(transferContent(finalFrame));
        }
        CompositeByteBuf composite = this.aggregatedContent;
        this.aggregatedContent = null;
        this.aggregatedReadableBytes = 0;
        if (composite.readableBytes() <= 0) {
            composite.release();
            return ByteBuf.EMPTY;
        }

        return composite;
    }

    private void addAggregatedComponent(ByteBuf content) {
        if (content == null) {
            return;
        }

        int readable = content.readableBytes();
        if (readable <= 0) {
            content.release();
            return;
        }

        if (this.aggregatedContent == null) {
            this.aggregatedContent = ByteBufUtils.compositeBuffer();
        }
        this.aggregatedContent.addComponent(content);
        this.aggregatedReadableBytes += readable;
    }

    private void resetFragmentState() {
        this.fragmentType = null;
        this.fragmentSequence = 0;
    }

    private void startTextValidation() {
        this.utf8PendingBytes = 0;
        this.utf8CodePoint = 0;
        this.utf8MinCodePoint = 0;
    }

    private void resetTextValidation() {
        this.utf8PendingBytes = 0;
        this.utf8CodePoint = 0;
        this.utf8MinCodePoint = 0;
    }

    private void validateTextChunk(ByteBuf content, boolean finalFragment) {
        if (this.fragmentType == null && this.utf8PendingBytes == 0 && this.utf8CodePoint == 0) {
            startTextValidation();
        }

        try {
            int readable = content == null ? 0 : content.readableBytes();
            if (readable > 0) {
                byte[] scratch = UTF8_SCRATCH.get();
                int offset = 0;
                while (offset < readable) {
                    int chunk = Math.min(readable - offset, UTF8_SCRATCH_SIZE);
                    content.getBytes(offset, scratch, 0, chunk);
                    for (int i = 0; i < chunk; i++) {
                        this.consumeUtf8Byte(scratch[i] & 0xFF);
                    }
                    offset += chunk;
                }
            }
            if (finalFragment) {
                if (this.utf8PendingBytes != 0) {
                    throw new WebSocketProtocolViolationException(WebSocketCode.INVALID_DATA, "text frame payload must be valid UTF-8.");
                }
                resetTextValidation();
            }
        } catch (WebSocketProtocolViolationException e) {
            resetTextValidation();
            throw e;
        }
    }

    private void consumeUtf8Byte(int currentByte) {
        if (this.utf8PendingBytes == 0) {
            if (currentByte <= 0x7F) {
                return;
            }
            if (currentByte >= 0xC2 && currentByte <= 0xDF) {
                this.utf8PendingBytes = 1;
                this.utf8CodePoint = currentByte & 0x1F;
                this.utf8MinCodePoint = 0x80;
                return;
            }
            if (currentByte >= 0xE0 && currentByte <= 0xEF) {
                this.utf8PendingBytes = 2;
                this.utf8CodePoint = currentByte & 0x0F;
                this.utf8MinCodePoint = 0x800;
                return;
            }
            if (currentByte >= 0xF0 && currentByte <= 0xF4) {
                this.utf8PendingBytes = 3;
                this.utf8CodePoint = currentByte & 0x07;
                this.utf8MinCodePoint = 0x10000;
                return;
            }
            throw new WebSocketProtocolViolationException(WebSocketCode.INVALID_DATA, "text frame payload must be valid UTF-8.");
        }

        if ((currentByte & 0xC0) != 0x80) {
            throw new WebSocketProtocolViolationException(WebSocketCode.INVALID_DATA, "text frame payload must be valid UTF-8.");
        }

        this.utf8CodePoint = (this.utf8CodePoint << 6) | (currentByte & 0x3F);
        this.utf8PendingBytes--;
        if (this.utf8PendingBytes != 0) {
            return;
        }

        int codePoint = this.utf8CodePoint;
        if (codePoint < this.utf8MinCodePoint || codePoint > 0x10FFFF || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new WebSocketProtocolViolationException(WebSocketCode.INVALID_DATA, "text frame payload must be valid UTF-8.");
        }
        this.utf8CodePoint = 0;
        this.utf8MinCodePoint = 0;
    }

    private ByteBuf buildCloseReplyContent(ProtoContext context, WebSocketFrame frame, ByteBuf content) {
        if (content == null || content.readableBytes() == 0) {
            return ByteBuf.EMPTY;
        }

        int statusCode = ((content.getByte(0) & 0xFF) << 8) | (content.getByte(1) & 0xFF);
        try {
            InternalUtils.validateCloseStatusCode(statusCode, resolveLocalClientMode(context));
            return transferContent(frame);
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