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
package net.hasor.neta.codec.http.h2;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoExceptionHolder;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.*;

/**
 * Reassembles HTTP/2 frames into staged {@link HttpObject} instances and HTTP/2 events.
 * <p>
 * This decoder implements the HTTP/2 message-layer state machine. It typically sits behind
 * {@link Http2FrameDecoder} and is responsible for reassembling HEADERS, DATA, CONTINUATION, and
 * related frames into a downstream-consumable {@link HttpObject} stream while translating control
 * semantics such as PING, GOAWAY, RST_STREAM, PRIORITY, and PUSH_PROMISE into HTTP/2 events.
 * <p>
 * A single stream usually appears downstream as an ordered object flow:
 * <pre>
 *   [HttpRequest/HttpResponse] -> [HttpHeaders]* -> [LastHttpHeaders] -> [HttpContent]* -> [LastHttpContent]
 * </pre>
 * HEADERS establishes the start line and header block, while DATA produces content objects.
 * Control frames are not forwarded directly as downstream messages; they are consumed by the
 * protocol layer or converted into corresponding HTTP/2 events.
 * <p>
 * Typical usage:
 * <pre>
 *   ctx.addLastDecoder("h2-frame", new Http2FrameDecoder(true));
 *   ctx.addLastDecoder("h2-object", new Http2ObjectDecoder(true));
 *   ctx.addLast("handler", httpHandler);
 * </pre>
 * <p>
 * Pipeline view:
 * <pre>
 *   socket bytes
 *      -> Http2FrameDecoder
 *      -> Http2Frame
 *      -> Http2ObjectDecoder
 *      -> HttpObject + Http2 events
 *      -> business handler
 * </pre>
 * <p>
 * This layer also maintains stream lifecycle state, header-block reassembly, HPACK decoding,
 * WINDOW_UPDATE feedback, and message-layer protocol error handling.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-25
 */
class Http2ObjectDecoder implements ProtoHandler<Http2Frame, HttpObject> {
    private static final Logger logger = Logger.getLogger(Http2ObjectDecoder.class);
    private final boolean       serverMode;
    private final Http2Settings localSettings;

    public Http2ObjectDecoder(boolean serverMode) {
        this(serverMode, Http2Settings.defaultLocalSettings(serverMode));
    }

    public Http2ObjectDecoder(boolean serverMode, Http2Settings localSettings) {
        this.serverMode = serverMode;
        this.localSettings = localSettings != null ? new Http2Settings(localSettings) : Http2Settings.defaultLocalSettings(serverMode);
    }

    @Override
    public void onInit(String name, int poolSize, ProtoContext context) {
        Http2ContextImpl.ensureInitialized(context, this.serverMode, this.localSettings);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Http2Frame> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        Http2DecoderContent state = context.context(Http2DecoderContent.class);
        while (src.hasMore()) {
            Http2Frame frame = src.takeMessage();
            if (frame == null) {
                continue;
            }

            if (!state.isPrefaceReceived()) {
                state.markPrefaceReceived();
            }

            if (context.getConfig().isPrintLog()) {
                int type = frame.type();
                int flags = frame.flags();
                long streamId = frame.streamId();
                int payloadLength = frame.payloadLength();
                long channelID = context.getChannel().getChannelId();
                logger.info("[H2-RCV] ch=" + channelID + " " + Http2FrameType.name(type) +//
                        " flags=" + Http2Flags.describe(type, flags) + //
                        " stream=" + streamId + //
                        " len=" + payloadLength);
            }

            switch (frame.type()) {
                case Http2FrameType.DATA:
                    processDataFrame(state, context, dst, frame);
                    break;
                case Http2FrameType.HEADERS:
                    processHeadersFrame(state, context, dst, frame);
                    break;
                case Http2FrameType.PRIORITY:
                    processPriorityFrame(context, frame);
                    break;
                case Http2FrameType.RST_STREAM:
                    processRstStream(state, context, dst, frame);
                    break;
                case Http2FrameType.SETTINGS:
                    processSettings(state, context, frame);
                    break;
                case Http2FrameType.PUSH_PROMISE:
                    processPushPromiseFrame(state, context, frame);
                    break;
                case Http2FrameType.PING:
                    processPing(state, context, frame);
                    break;
                case Http2FrameType.GOAWAY:
                    processGoaway(context, frame);
                    break;
                case Http2FrameType.WINDOW_UPDATE:
                    processWindowUpdate(state, context, frame);
                    break;
                case Http2FrameType.CONTINUATION:
                    processContinuationFrame(state, context, dst, frame);
                    break;
                default:
                    break;
            }
        }

        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        Http2DecoderContent state = context.context(Http2DecoderContent.class);
        if (state != null) {
            state.releaseAll();
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
        if (!(e instanceof HttpProtocolStreamException) && !(e instanceof HttpProtocolConnectionException)) {
            return ProtoStatus.Next;
        }

        HttpProtocolException protocolError = (HttpProtocolException) e;
        if (protocolError instanceof HttpProtocolStreamException && protocolError.getStreamId() > 0) {
            handleStreamError(context, protocolError);
            eh.clear();
            return ProtoStatus.Next;
        }

        Http2DecoderContent state = context.context(Http2DecoderContent.class);
        long lastAcceptedStreamId = state != null ? state.lastEmittedStreamId() : 0;
        String message = protocolError.getMessage();
        byte[] debugData = message == null ? null : message.getBytes(StandardCharsets.UTF_8);

        long errorCode = resolveErrorCode(protocolError.errorCode(), Http2ErrorCode.INTERNAL_ERROR);
        Http2Frame frame = goAwayFrame(lastAcceptedStreamId, errorCode, debugData);
        context.sendEncoded(frame);
        this.fireEvent(context, Http2GoawayEvent.class, new Http2GoawayEvent(0, lastAcceptedStreamId, errorCode, debugData).remote(false));

        eh.clear();
        context.getChannel().close();
        return ProtoStatus.Stop;
    }

    //

    private void processDataFrame(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<HttpObject> dst, Http2Frame frame) {
        int flags = frame.flags();
        long streamId = frame.streamId();
        byte[] payload = frame.payload();
        int payloadOffset = frame.payloadOffset();
        int payloadLength = frame.payloadLength();
        Http2Stream stream = state.getStream(streamId);
        if (stream == null) {
            String msg = "HTTP/2: DATA frame received before HEADERS on stream " + streamId;
            throw new HttpProtocolStreamException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }

        int offset = payloadOffset;
        int dataLength = payloadLength;
        if (Http2Flags.padded(flags)) {
            if (payloadLength < 1) {
                String msg = "HTTP/2: DATA frame with PADDED flag must include pad length";
                throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
            }

            int padLength = payload[offset] & 0xFF;
            offset += 1;
            dataLength = payloadLength - 1 - padLength;
            if (dataLength < 0) {
                String msg = "HTTP/2: DATA frame padding exceeds payload";
                throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
            }
        }

        boolean endStream = Http2Flags.endStream(flags);
        if (dataLength > 0 || endStream) {
            ByteBuf content = context.byteBufAllocator().buffer(Math.max(dataLength, 1));
            if (dataLength > 0) {
                content.writeBytes(payload, offset, dataLength);
            }
            content.markWriter();

            state.setLastEmittedStreamId(streamId);
            if (endStream) {
                DefaultLastHttpContent lastContent = new DefaultLastHttpContent(content);
                lastContent.streamId(streamId);
                dst.offerMessage(lastContent);
                stream.setTerminalObjectEmitted(true);
                state.offerResponseStreamId(streamId);
            } else {
                DefaultHttpContent httpContent = new DefaultHttpContent(content);
                httpContent.streamId(streamId);
                dst.offerMessage(httpContent);
            }
            if (dataLength > 0) {
                state.offerWindowUpdate(0, dataLength);
                state.offerWindowUpdate(streamId, dataLength);
            }
        }

        if (endStream) {
            markRemoteHalfClosed(stream);
            this.recordInboundHalfClosed(context, streamId);
        }
    }

    private void processHeadersFrame(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<HttpObject> dst, Http2Frame frame) {
        int flags = frame.flags();
        long streamId = frame.streamId();
        byte[] payload = frame.payload();
        int payloadOffset = frame.payloadOffset();
        int payloadLength = frame.payloadLength();
        int headerBlockLength = payloadLength;

        if (Http2Flags.padded(flags)) {
            if (payloadLength < 1) {
                String msg = "HTTP/2: HEADERS frame with PADDED flag must include pad length";
                throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
            }

            int padLength = payload[payloadOffset] & 0xFF;
            payloadOffset += 1;
            headerBlockLength = payloadLength - 1 - padLength;
        }
        if (Http2Flags.priority(flags)) {
            if (headerBlockLength < 5) {
                String msg = "HTTP/2: HEADERS frame with PADDED flag must include pad length";
                throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
            }

            payloadOffset += 5;
            headerBlockLength -= 5;
        }

        if (headerBlockLength < 0) {
            String msg = "HTTP/2: HEADERS frame with PADDED flag must include pad length";
            throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }

        Http2Stream stream = state.getOrCreateStream(streamId);
        if (stream.state() == Http2StreamState.IDLE) {
            stream.state(Http2StreamState.OPEN);
        }

        if (state.serverInitialWindowSize() > 65535) {
            state.offerWindowUpdate(streamId, state.serverInitialWindowSize() - 65535);
        }

        if (Http2Flags.endHeaders(flags)) {
            DefaultHttpHeaders headers = state.decodeHeaders(payload, payloadOffset, headerBlockLength);
            state.setLastEmittedStreamId(streamId);
            emitHeaders(state, context, dst, stream, streamId, headers, Http2Flags.endStream(flags));
        } else {
            ByteBuf headerBlock = context.byteBufAllocator().buffer(headerBlockLength + 256);
            headerBlock.writeBytes(payload, payloadOffset, headerBlockLength);
            stream.accumulatedHeaderBlock(headerBlock);
            stream.setEndStreamPending(Http2Flags.endStream(flags));
            state.openHeaderBlockOn(streamId, Http2FrameType.HEADERS, -1);
        }
    }

    private void processPriorityFrame(ProtoContext context, Http2Frame frame) {
        long streamId = frame.streamId();
        byte[] payload = frame.payload();
        int payloadOffset = frame.payloadOffset();
        // @formatter:off
        int rawDependency = ((payload[payloadOffset] & 0xFF) << 24) |
                            ((payload[payloadOffset + 1] & 0xFF) << 16) |
                            ((payload[payloadOffset + 2] & 0xFF) << 8) |
                            (payload[payloadOffset + 3] & 0xFF);
        // @formatter:on
        boolean exclusive = (rawDependency & 0x80000000) != 0;
        long streamDependency = Http2Frame.decodeWireStreamId(rawDependency);
        if (streamDependency == streamId) {
            String msg = "HTTP/2: PRIORITY frame cannot depend on itself for stream " + streamId;
            throw new HttpProtocolStreamException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }

        int weight = (payload[payloadOffset + 4] & 0xFF) + 1;
        this.fireEvent(context, Http2PriorityEvent.class, new Http2PriorityEvent(streamId, streamDependency, weight, exclusive).remote(true));
    }

    private void processRstStream(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<HttpObject> dst, Http2Frame frame) {
        long streamId = frame.streamId();
        byte[] payload = frame.payload();
        int payloadOffset = frame.payloadOffset();
        int payloadLength = frame.payloadLength();
        if (payloadLength != 4) {
            String msg = "HTTP/2: RST_STREAM frame must be 4 bytes, got " + payloadLength;
            throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.FRAME_SIZE_ERROR, msg);
        }

        // @formatter:off
        long errorCode = ((long) (payload[payloadOffset] & 0xFF) << 24) |
                         ((long) (payload[payloadOffset + 1] & 0xFF) << 16) |
                         ((long) (payload[payloadOffset + 2] & 0xFF) << 8) |
                         (payload[payloadOffset + 3] & 0xFF);
        // @formatter:on
        Http2Stream stream = state.getStream(streamId);
        if (this.shouldEmitResetTerminal(stream)) {
            dst.offerMessage(this.newResetLastContent(streamId, errorCode));
            stream.setTerminalObjectEmitted(true);
        }
        state.closeStream(streamId);
        state.removeFromResponseQueue(streamId);

        this.fireEvent(context, Http2ResetEvent.class, new Http2ResetEvent(streamId, errorCode).remote(true));
    }

    private void processSettings(Http2DecoderContent state, ProtoContext context, Http2Frame frame) {
        int flags = frame.flags();
        byte[] payload = frame.payload();
        int payloadOffset = frame.payloadOffset();
        int payloadLength = frame.payloadLength();
        if (Http2Flags.ack(flags)) {
            if (payloadLength != 0) {
                String msg = "HTTP/2: SETTINGS ACK frame must have an empty payload";
                throw new HttpProtocolConnectionException(frame.streamId(), Http2ErrorCode.FRAME_SIZE_ERROR, msg);
            }
            return;
        }

        if (payloadLength % 6 != 0) {
            String msg = "HTTP/2: SETTINGS frame length must be a multiple of 6";
            throw new HttpProtocolConnectionException(frame.streamId(), Http2ErrorCode.FRAME_SIZE_ERROR, msg);
        }

        Map<Integer, Long> settings = new LinkedHashMap<>();
        for (int i = payloadOffset; i < payloadOffset + payloadLength; i += 6) {
            // @formatter:off
            int id = ((payload[i] & 0xFF) << 8) |
                     (payload[i + 1] & 0xFF);
            long value = ((long) (payload[i + 2] & 0xFF) << 24) |
                         ((payload[i + 3] & 0xFF) << 16) |
                         ((payload[i + 4] & 0xFF) << 8) |
                         (payload[i + 5] & 0xFF);
            // @formatter:on

            state.applyRemoteSetting(id, value);
            settings.put(id, value);
        }

        state.markSettingsAckPending();
    }

    private void processPushPromiseFrame(Http2DecoderContent state, ProtoContext context, Http2Frame frame) {
        int flags = frame.flags();
        long streamId = frame.streamId();
        byte[] payload = frame.payload();
        int payloadOffset = frame.payloadOffset();
        int payloadLength = frame.payloadLength();
        if (this.serverMode) {
            String msg = "HTTP/2: server endpoint must not receive PUSH_PROMISE frames";
            throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }

        Http2Stream parentStream = state.getStream(streamId);
        if (parentStream == null) {
            String msg = "HTTP/2: PUSH_PROMISE frame received before HEADERS on stream " + streamId;
            throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }
        if (parentStream.state() != Http2StreamState.OPEN && parentStream.state() != Http2StreamState.HALF_CLOSED_LOCAL) {
            String msg = "HTTP/2: PUSH_PROMISE frame must target a stream in open or half-closed(local) state, got " + parentStream.state() + " for stream " + streamId;
            throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }

        int offset = payloadOffset;
        int headerBlockLength = payloadLength;
        if (Http2Flags.padded(flags)) {
            if (payloadLength < 1) {
                String msg = "HTTP/2: PUSH_PROMISE frame with PADDED flag must include pad length";
                throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
            }

            int padLength = payload[offset] & 0xFF;
            offset += 1;
            headerBlockLength = payloadLength - 1 - padLength;
        }
        if (headerBlockLength < 4) {
            String msg = "HTTP/2: PUSH_PROMISE frame must include promised stream id";
            throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }

        // @formatter:off
        int rawPromisedStreamId = ((payload[offset] & 0x7F) << 24) |
                      ((payload[offset + 1] & 0xFF) << 16) |
                      ((payload[offset + 2] & 0xFF) << 8) |
                      (payload[offset + 3] & 0xFF);
        // @formatter:on
        long promisedStreamId = Http2Frame.decodeWireStreamId(rawPromisedStreamId);
        if (promisedStreamId == 0) {
            String msg = "HTTP/2: PUSH_PROMISE promised stream id must be non-zero";
            throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }
        if ((promisedStreamId & 1) != 0) {
            String msg = "HTTP/2: PUSH_PROMISE promised stream id must be server-initiated, got " + promisedStreamId;
            throw new HttpProtocolConnectionException(promisedStreamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }

        long lastRemoteInitiatedStreamId = state.lastRemoteInitiatedStreamId(this.serverMode);
        if (promisedStreamId <= lastRemoteInitiatedStreamId) {
            String msg = "HTTP/2: PUSH_PROMISE promised stream id must be greater than prior remote stream ids, got " + promisedStreamId + " after " + lastRemoteInitiatedStreamId;
            throw new HttpProtocolConnectionException(promisedStreamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }

        Http2Stream promisedStream = state.getOrCreateStream(promisedStreamId);
        if (promisedStream.state() != Http2StreamState.IDLE && promisedStream.state() != Http2StreamState.RESERVED_LOCAL) {
            String msg = "HTTP/2: PUSH_PROMISE promised stream " + promisedStreamId + " is not idle";
            throw new HttpProtocolConnectionException(promisedStreamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }

        offset += 4;
        headerBlockLength -= 4;
        if (Http2Flags.endHeaders(flags)) {
            DefaultHttpHeaders headers = state.decodeHeaders(payload, offset, headerBlockLength);
            promisedStream.state(Http2StreamState.RESERVED_LOCAL);
            this.fireEvent(context, Http2PushPromiseEvent.class, new Http2PushPromiseEvent(streamId, promisedStreamId, headers).remote(true));
        } else {
            ByteBuf headerBlock = context.byteBufAllocator().buffer(headerBlockLength + 256);
            headerBlock.writeBytes(payload, offset, headerBlockLength);
            parentStream.accumulatedHeaderBlock(headerBlock);
            state.openHeaderBlockOn(streamId, Http2FrameType.PUSH_PROMISE, promisedStreamId);
        }
    }

    private void processPing(Http2DecoderContent state, ProtoContext context, Http2Frame frame) {
        long streamId = frame.streamId();
        int flags = frame.flags();
        byte[] payload = frame.payload();
        int payloadOffset = frame.payloadOffset();
        if (frame.payloadLength() != 8) {
            String msg = "HTTP/2: PING frame must be 8 bytes, got " + frame.payloadLength();
            throw new HttpProtocolConnectionException(frame.streamId(), Http2ErrorCode.FRAME_SIZE_ERROR, msg);
        }

        byte[] copy = new byte[8];
        System.arraycopy(payload, payloadOffset, copy, 0, 8);
        if (!Http2Flags.ack(flags)) {
            state.offerPingAck(copy);
            return;
        }

        this.fireEvent(context, Http2PongEvent.class, new Http2PongEvent(streamId, ByteBuf.wrap(copy)).remote(true));
    }

    private void processGoaway(ProtoContext context, Http2Frame frame) {
        long streamId = frame.streamId();
        byte[] payload = frame.payload();
        int payloadOffset = frame.payloadOffset();
        int payloadLength = frame.payloadLength();
        if (payloadLength < 8) {
            String msg = "HTTP/2: GOAWAY frame too short";
            throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.FRAME_SIZE_ERROR, msg);
        }

        // @formatter:off
        int rawLastStreamId = ((payload[payloadOffset] & 0x7F) << 24) |
                      ((payload[payloadOffset + 1] & 0xFF) << 16) |
                      ((payload[payloadOffset + 2] & 0xFF) << 8) |
                      (payload[payloadOffset + 3] & 0xFF);
        long errorCode = ((long) (payload[payloadOffset + 4] & 0xFF) << 24) |
                         ((long) (payload[payloadOffset + 5] & 0xFF) << 16) |
                         ((long) (payload[payloadOffset + 6] & 0xFF) << 8) |
                         (payload[payloadOffset + 7] & 0xFF);
        // @formatter:on
        long lastStreamId = Http2Frame.decodeWireStreamId(rawLastStreamId);

        int debugLength = payloadLength - 8;
        byte[] debugData = new byte[Math.max(debugLength, 0)];
        if (debugLength > 0) {
            System.arraycopy(payload, payloadOffset + 8, debugData, 0, debugLength);
        }

        this.fireEvent(context, Http2GoawayEvent.class, new Http2GoawayEvent(streamId, lastStreamId, errorCode, debugData).remote(true));
    }

    private void processWindowUpdate(Http2DecoderContent state, ProtoContext context, Http2Frame frame) {
        long streamId = frame.streamId();
        byte[] payload = frame.payload();
        int payloadOffset = frame.payloadOffset();
        int payloadLength = frame.payloadLength();
        if (payloadLength != 4) {
            String msg = "HTTP/2: WINDOW_UPDATE frame must be 4 bytes";
            throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.FRAME_SIZE_ERROR, msg);
        }

        // @formatter:off
        int increment = ((payload[payloadOffset] & 0x7F) << 24) |
                        ((payload[payloadOffset + 1] & 0xFF) << 16) |
                        ((payload[payloadOffset + 2] & 0xFF) << 8) |
                        (payload[payloadOffset + 3] & 0xFF);
        // @formatter:on
        if (increment == 0) {
            if (streamId == 0) {
                String msg = "HTTP/2: WINDOW_UPDATE increment must be non-zero";
                throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.FLOW_CONTROL_ERROR, msg);
            } else {
                String msg = "HTTP/2: WINDOW_UPDATE increment must be non-zero";
                throw new HttpProtocolStreamException(streamId, Http2ErrorCode.FLOW_CONTROL_ERROR, msg);
            }
        }
    }

    private void processContinuationFrame(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<HttpObject> dst, Http2Frame frame) {
        int flags = frame.flags();
        long streamId = frame.streamId();
        byte[] payload = frame.payload();
        int payloadOffset = frame.payloadOffset();
        int payloadLength = frame.payloadLength();
        Http2Stream stream = state.getStream(streamId);
        if (stream == null || stream.accumulatedHeaderBlock() == null) {
            String msg = "HTTP/2: CONTINUATION frame without prior header block on stream " + streamId;
            throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }
        if (state.openHeaderBlockStreamId() != streamId) {
            String msg = "HTTP/2: CONTINUATION frame arrived on stream " + streamId + " while stream " + state.openHeaderBlockStreamId() + " owns the open header block";
            throw new HttpProtocolConnectionException(streamId, Http2ErrorCode.PROTOCOL_ERROR, msg);
        }

        ByteBuf headerBlock = stream.accumulatedHeaderBlock();
        headerBlock.writeBytes(payload, payloadOffset, payloadLength);
        if (Http2Flags.endHeaders(flags)) {
            headerBlock.markWriter();
            int readable = headerBlock.readableBytes();
            byte[] allHeaders = new byte[readable];
            headerBlock.getBytes(0, allHeaders, 0, readable);
            boolean endStream = stream.isEndStreamPending();
            int openHeaderBlockType = state.openHeaderBlockType();
            long promisedStreamId = state.openPromisedStreamId();
            headerBlock.free();
            stream.accumulatedHeaderBlock(null);
            state.closeOpenHeaderBlock();

            HttpHeaders headers = state.decodeHeaders(allHeaders, 0, allHeaders.length);
            if (openHeaderBlockType == Http2FrameType.PUSH_PROMISE) {
                Http2Stream promisedStream = state.getOrCreateStream(promisedStreamId);
                promisedStream.state(Http2StreamState.RESERVED_LOCAL);
                this.fireEvent(context, Http2PushPromiseEvent.class, new Http2PushPromiseEvent(streamId, promisedStreamId, headers).remote(true));
            } else {
                emitHeaders(state, context, dst, stream, streamId, headers, endStream);
            }
        }
    }

    private void emitHeaders(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<HttpObject> dst, Http2Stream stream, long streamId, HttpHeaders headers, boolean endStream) {
        if (stream != null && stream.isInitialHeadersEmitted()) {
            emitTrailerHeaders(state, dst, streamId, headers, endStream);
        } else {
            emitInitialHeaders(context, state, dst, streamId, headers, endStream);
            if (stream != null) {
                stream.setInitialHeadersEmitted(true);
            }
        }
        if (endStream) {
            if (stream != null) {
                stream.setTerminalObjectEmitted(true);
            }
            markRemoteHalfClosed(stream);
            this.recordInboundHalfClosed(context, streamId);
        }
    }

    private void recordInboundHalfClosed(ProtoContext context, long streamId) {
        this.fireEvent(context, Http2StreamCloseEvent.class, new Http2StreamCloseEvent(streamId, true).remote(true));
    }

    private void emitInitialHeaders(ProtoContext context, Http2DecoderContent state, ProtoSndQueue<HttpObject> dst, long streamId, HttpHeaders headers, boolean endStream) {
        LastHttpHeaders regularHeaders = new DefaultLastHttpHeaders();
        HttpObject startLine = state.newStartLine(streamId, headers);
        state.setLastEmittedStreamId(streamId);
        if (endStream) {
            state.offerResponseStreamId(streamId);
        }

        copyRegularHeaders(headers, regularHeaders);
        regularHeaders.streamId(streamId);

        if (startLine instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) startLine;
            context.context(HttpVersion.class, response.protocolVersion());
            context.context(HttpScope.class, HttpScope.STREAM);
            dst.offerMessage(response);
            dst.offerMessage(regularHeaders);
        } else {
            HttpRequest request = (HttpRequest) startLine;
            String authority = headers.getString(HttpHeaderNames.PSEUDO_AUTHORITY);
            String protocol = headers.getString(HttpHeaderNames.PSEUDO_PROTOCOL);
            String scheme = headers.getString(HttpHeaderNames.PSEUDO_SCHEME);

            if (StringUtils.isNotBlank(authority) && StringUtils.isBlank(regularHeaders.getString(HttpHeaderNames.HOST))) {
                regularHeaders.addHeader(HttpHeaderNames.HOST, authority);
            }
            if (StringUtils.isNotBlank(protocol)) {
                regularHeaders.addHeader(HttpHeaderNames.PSEUDO_PROTOCOL, protocol);
            }
            if (StringUtils.isNotBlank(scheme)) {
                regularHeaders.addHeader(HttpHeaderNames.X_FORWARDED_PROTO, scheme);
            }

            context.context(HttpVersion.class, request.protocolVersion());
            context.context(HttpScope.class, HttpScope.STREAM);
            dst.offerMessage(request);
            dst.offerMessage(regularHeaders);
        }

        if (endStream) {
            LastHttpContent lastContent = new DefaultLastHttpContent(ByteBuf.EMPTY);
            lastContent.streamId(streamId);

            dst.offerMessage(lastContent);
            Http2Stream stream = state.getStream(streamId);
            if (stream != null) {
                stream.setTerminalObjectEmitted(true);
            }
        }
    }

    private void emitTrailerHeaders(Http2DecoderContent state, ProtoSndQueue<HttpObject> dst, //
            long streamId, HttpHeaders headers, boolean endStream) {
        TrailerHttpHeaders trailerHeaders = new DefaultTrailerHttpHeaders();
        trailerHeaders.streamId(streamId);

        state.setLastEmittedStreamId(streamId);
        copyRegularHeaders(headers, trailerHeaders);

        dst.offerMessage(trailerHeaders);
        if (endStream) {
            state.offerResponseStreamId(streamId);

            LastHttpContent lastContent = new DefaultLastHttpContent(ByteBuf.EMPTY);
            lastContent.streamId(streamId);

            dst.offerMessage(lastContent);
        }
    }

    private void copyRegularHeaders(HttpHeaders source, HttpHeaders target) {
        for (String name : source.headerNames()) {
            if (StringUtils.startsWith(name, ":")) {
                continue;
            }
            for (String value : source.getValues(name)) {
                target.addHeader(name, value);
            }
        }
    }

    //

    private <T> void fireEvent(ProtoContext context, Class<T> eventType, T event) {
        try {
            context.fireEvent(eventType, event);
        } catch (Throwable e) {
            logger.error("Error occurred while publishing HTTP/2 event: " + eventType.getSimpleName(), e);
        }
    }

    private void markRemoteHalfClosed(Http2Stream stream) {
        if (stream == null) {
            return;
        }
        if (stream.state() == Http2StreamState.HALF_CLOSED_LOCAL) {
            stream.state(Http2StreamState.CLOSED);
            return;
        }
        if (stream.state() != Http2StreamState.CLOSED) {
            stream.state(Http2StreamState.HALF_CLOSED_REMOTE);
        }
    }

    private void handleStreamError(ProtoContext context, HttpProtocolException protocolError) {
        long streamId = protocolError.getStreamId();
        Http2DecoderContent state = context.context(Http2DecoderContent.class);
        if (state != null) {
            state.closeStream(streamId);
            state.removeFromResponseQueue(streamId);
        }

        long errorCode = resolveErrorCode(protocolError.errorCode(), Http2ErrorCode.INTERNAL_ERROR);
        Http2Frame frame = resetStreamFrame(streamId, errorCode);
        context.sendEncoded(frame);
        this.fireEvent(context, Http2ResetEvent.class, new Http2ResetEvent(streamId, errorCode).remote(false));
    }

    private boolean shouldEmitResetTerminal(Http2Stream stream) {
        return stream != null && stream.isInitialHeadersEmitted() && !stream.isTerminalObjectEmitted();
    }

    private LastHttpContent newResetLastContent(long streamId, long errorCode) {
        LastHttpContent lastContent = new DefaultLastHttpContent(ByteBuf.EMPTY);
        lastContent.streamId(streamId);
        lastContent.markBad("HTTP/2 stream reset: " + Http2ErrorCode.name(errorCode));
        return lastContent;
    }

    private static Http2Frame resetStreamFrame(long streamId, long errorCode) {
        byte[] payload = new byte[4];
        write32Bits(payload, 0, errorCode);
        return Http2Frame.rstStream(streamId, payload);
    }

    private static Http2Frame goAwayFrame(long lastStreamId, long errorCode, byte[] debugData) {
        byte[] safeDebugData = debugData == null ? new byte[0] : debugData.clone();
        byte[] payload = new byte[8 + safeDebugData.length];
        write31Bits(payload, lastStreamId);
        write32Bits(payload, 4, errorCode);
        System.arraycopy(safeDebugData, 0, payload, 8, safeDebugData.length);
        return Http2Frame.goaway(payload);
    }

    private static void write31Bits(byte[] target, long value) {
        int narrowed = Http2Frame.requireWireStreamId(value);
        target[0] = (byte) ((narrowed >> 24) & 0x7F);
        target[1] = (byte) ((narrowed >> 16) & 0xFF);
        target[2] = (byte) ((narrowed >> 8) & 0xFF);
        target[3] = (byte) (narrowed & 0xFF);
    }

    private static void write32Bits(byte[] target, int offset, long value) {
        target[offset] = (byte) ((value >> 24) & 0xFF);
        target[offset + 1] = (byte) ((value >> 16) & 0xFF);
        target[offset + 2] = (byte) ((value >> 8) & 0xFF);
        target[offset + 3] = (byte) (value & 0xFF);
    }

    private static long resolveErrorCode(long errorCode, long fallback) {
        return errorCode >= 0 ? errorCode : fallback;
    }
}