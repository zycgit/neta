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
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.DefaultHttpHeaders;
import net.hasor.neta.codec.http.HttpProtocolViolationException;

/**
 * Reassembles HTTP/2 frames into semantic {@link Http2Message} objects.
 * <p>
 * Decode path:
 * <pre>
 *   ByteBuf -> Http2Frame -> Http2Message
 * </pre>
 */
public class Http2FrameToMessageDecoder implements ProtoHandler<Http2Frame, Http2Message> {
    private static final Logger  logger = Logger.getLogger(Http2FrameToMessageDecoder.class);
    private final        boolean serverMode;
    private final        int     maxHeaderTableSize;
    private final        int     maxHeaderListSize;

    public Http2FrameToMessageDecoder(boolean serverMode) {
        this(serverMode, 4096, 8192);
    }

    public Http2FrameToMessageDecoder(boolean serverMode, int maxHeaderTableSize, int maxHeaderListSize) {
        this.serverMode = serverMode;
        this.maxHeaderTableSize = maxHeaderTableSize;
        this.maxHeaderListSize = maxHeaderListSize;
    }

    static Http2Frame buildWindowUpdate(int streamId, int increment) {
        byte[] payload = new byte[4];
        payload[0] = (byte) ((increment >> 24) & 0x7F);
        payload[1] = (byte) ((increment >> 16) & 0xFF);
        payload[2] = (byte) ((increment >> 8) & 0xFF);
        payload[3] = (byte) (increment & 0xFF);
        return Http2Frame.windowUpdate(streamId, payload);
    }

    @Override
    public void onInit(String name, int poolSize, ProtoContext context) {
        if (context.context(Http2DecoderContent.class) == null) {
            context.context(Http2DecoderContent.class, new Http2DecoderContent(this.serverMode, this.maxHeaderTableSize, this.maxHeaderListSize));
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Http2Frame> src, ProtoSndQueue<Http2Message> dst) throws Throwable {
        Http2DecoderContent state = context.context(Http2DecoderContent.class);
        while (src.hasMore()) {
            Http2Frame frame = src.takeMessage();
            if (frame == null) {
                continue;
            }
            if (!state.isPrefaceReceived()) {
                state.markPrefaceReceived();
            }
            processFrame(state, context, dst, frame.type(), frame.flags(), frame.streamId(), frame.payload(), frame.payloadOffset(), frame.payloadLength());
        }
        return ProtoStatus.Next;
    }

    private void processFrame(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<Http2Message> dst, int type, int flags, int streamId, byte[] payload, int payloadOffset, int payloadLength) {
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H2-RCV] ch=" + channelID + " " + Http2FrameType.name(type) + " flags=" + Http2Flags.describe(type, flags) + " stream=" + streamId + " len=" + payloadLength);
        }
        switch (type) {
            case Http2FrameType.DATA:
                processDataFrame(state, context, dst, flags, streamId, payload, payloadOffset, payloadLength);
                break;
            case Http2FrameType.HEADERS:
                processHeadersFrame(state, context, dst, flags, streamId, payload, payloadOffset, payloadLength);
                break;
            case Http2FrameType.PRIORITY:
                break;
            case Http2FrameType.RST_STREAM:
                processRstStream(state, context, dst, streamId, payload, payloadOffset, payloadLength);
                break;
            case Http2FrameType.SETTINGS:
                processSettings(state, context, dst, flags, payload, payloadOffset, payloadLength);
                break;
            case Http2FrameType.PUSH_PROMISE:
                break;
            case Http2FrameType.PING:
                processPing(state, dst, flags, payload, payloadOffset, payloadLength);
                break;
            case Http2FrameType.GOAWAY:
                processGoaway(context, dst, payload, payloadOffset, payloadLength);
                break;
            case Http2FrameType.WINDOW_UPDATE:
                processWindowUpdate(state, context, dst, streamId, payload, payloadOffset, payloadLength);
                break;
            case Http2FrameType.CONTINUATION:
                processContinuationFrame(state, context, dst, flags, streamId, payload, payloadOffset, payloadLength);
                break;
            default:
                break;
        }
    }

    private void processDataFrame(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<Http2Message> dst, int flags, int streamId, byte[] payload, int payloadOffset, int payloadLength) {
        int offset = payloadOffset;
        int dataLength = payloadLength;
        if (Http2Flags.padded(flags)) {
            int padLength = payload[offset] & 0xFF;
            offset += 1;
            dataLength = payloadLength - 1 - padLength;
            if (dataLength < 0) {
                throw new HttpProtocolViolationException("HTTP/2: DATA frame padding exceeds payload");
            }
        }
        boolean endStream = Http2Flags.endStream(flags);
        if (dataLength > 0 || endStream) {
            ByteBuf content = context.byteBufAllocator().buffer(Math.max(dataLength, 1));
            if (dataLength > 0) {
                content.writeBytes(payload, offset, dataLength);
            }
            content.markWriter();
            dst.offerMessage(new Http2DataMessage(streamId, content, endStream));
            if (dataLength > 0) {
                state.offerWindowUpdate(buildWindowUpdate(0, dataLength));
                state.offerWindowUpdate(buildWindowUpdate(streamId, dataLength));
            }
        }
    }

    private void processHeadersFrame(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<Http2Message> dst, int flags, int streamId, byte[] payload, int payloadOffset, int payloadLength) {
        int offset = payloadOffset;
        int headerBlockLength = payloadLength;
        if (Http2Flags.padded(flags)) {
            int padLength = payload[offset] & 0xFF;
            offset += 1;
            headerBlockLength = payloadLength - 1 - padLength;
        }
        if (Http2Flags.priority(flags)) {
            offset += 5;
            headerBlockLength -= 5;
        }
        if (headerBlockLength < 0) {
            throw new HttpProtocolViolationException("HTTP/2: HEADERS frame has negative header block length");
        }
        Http2Stream stream = state.getOrCreateStream(streamId);
        stream.state(Http2StreamState.OPEN);
        if (state.serverInitialWindowSize() > 65535) {
            state.offerWindowUpdate(buildWindowUpdate(streamId, state.serverInitialWindowSize() - 65535));
        }
        if (Http2Flags.endHeaders(flags)) {
            DefaultHttpHeaders headers = state.decodeHeaders(payload, offset, headerBlockLength);
            dst.offerMessage(new Http2HeadersMessage(streamId, headers, Http2Flags.endStream(flags)));
        } else {
            ByteBuf headerBlock = context.byteBufAllocator().buffer(headerBlockLength + 256);
            headerBlock.writeBytes(payload, offset, headerBlockLength);
            stream.accumulatedHeaderBlock(headerBlock);
            stream.setEndStreamPending(Http2Flags.endStream(flags));
        }
    }

    private void processContinuationFrame(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<Http2Message> dst, int flags, int streamId, byte[] payload, int payloadOffset, int payloadLength) {
        Http2Stream stream = state.getStream(streamId);
        if (stream == null || stream.accumulatedHeaderBlock() == null) {
            throw new HttpProtocolViolationException("HTTP/2: CONTINUATION frame without prior HEADERS on stream " + streamId);
        }
        ByteBuf headerBlock = stream.accumulatedHeaderBlock();
        headerBlock.writeBytes(payload, payloadOffset, payloadLength);
        if (Http2Flags.endHeaders(flags)) {
            headerBlock.markWriter();
            int readable = headerBlock.readableBytes();
            byte[] allHeaders = new byte[readable];
            headerBlock.getBytes(0, allHeaders, 0, readable);
            boolean endStream = stream.isEndStreamPending();
            headerBlock.free();
            stream.accumulatedHeaderBlock(null);
            DefaultHttpHeaders headers = state.decodeHeaders(allHeaders, 0, allHeaders.length);
            dst.offerMessage(new Http2HeadersMessage(streamId, headers, endStream));
            if (context.getConfig() != null && context.getConfig().isPrintLog()) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H2-RCV] ch=" + channelID + " CONTINUATION stream=" + streamId + " totalHeaderBlockLen=" + readable + " endStream=" + endStream);
            }
        }
    }

    private void processPing(Http2DecoderContent state, ProtoSndQueue<Http2Message> dst, int flags, byte[] payload, int payloadOffset, int payloadLength) {
        if (payloadLength != 8) {
            throw new HttpProtocolViolationException("HTTP/2: PING frame must be 8 bytes, got " + payloadLength);
        }
        byte[] copy = new byte[8];
        System.arraycopy(payload, payloadOffset, copy, 0, 8);
        if (!Http2Flags.ack(flags)) {
            state.offerPingAck(copy);
        }
        dst.offerMessage(new Http2PingMessage(Http2Flags.ack(flags), copy));
    }

    private void processRstStream(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<Http2Message> dst, int streamId, byte[] payload, int payloadOffset, int payloadLength) {
        if (payloadLength != 4) {
            throw new HttpProtocolViolationException("HTTP/2: RST_STREAM frame must be 4 bytes, got " + payloadLength);
        }
        long errorCode = ((long) (payload[payloadOffset] & 0xFF) << 24) | ((long) (payload[payloadOffset + 1] & 0xFF) << 16) | ((long) (payload[payloadOffset + 2] & 0xFF) << 8) | (payload[payloadOffset + 3] & 0xFF);
        state.closeStream(streamId);
        dst.offerMessage(new Http2ResetStreamMessage(streamId, errorCode));
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H2-RCV] ch=" + channelID + " RST_STREAM stream=" + streamId + " errorCode=0x" + Long.toHexString(errorCode));
        }
    }

    private void processSettings(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<Http2Message> dst, int flags, byte[] payload, int payloadOffset, int payloadLength) {
        if (Http2Flags.ack(flags)) {
            dst.offerMessage(new Http2SettingsMessage(true, null));
            return;
        }
        if (payloadLength % 6 != 0) {
            throw new HttpProtocolViolationException("HTTP/2: SETTINGS frame length must be a multiple of 6");
        }
        Map<Integer, Long> settings = new LinkedHashMap<>();
        for (int i = payloadOffset; i < payloadOffset + payloadLength; i += 6) {
            int id = ((payload[i] & 0xFF) << 8) | (payload[i + 1] & 0xFF);
            long value = ((long) (payload[i + 2] & 0xFF) << 24) | ((payload[i + 3] & 0xFF) << 16) | ((payload[i + 4] & 0xFF) << 8) | (payload[i + 5] & 0xFF);
            state.applyRemoteSetting(id, value);
            settings.put(id, value);
            if (context.getConfig() != null && context.getConfig().isPrintLog()) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H2-RCV] ch=" + channelID + " SETTINGS id=" + id + " value=" + value);
            }
        }
        state.markSettingsAckPending();
        dst.offerMessage(new Http2SettingsMessage(false, settings));
    }

    private void processGoaway(ProtoContext context, ProtoSndQueue<Http2Message> dst, byte[] payload, int payloadOffset, int payloadLength) {
        if (payloadLength < 8) {
            throw new HttpProtocolViolationException("HTTP/2: GOAWAY frame too short");
        }
        int lastStreamId = ((payload[payloadOffset] & 0x7F) << 24) | ((payload[payloadOffset + 1] & 0xFF) << 16) | ((payload[payloadOffset + 2] & 0xFF) << 8) | (payload[payloadOffset + 3] & 0xFF);
        long errorCode = ((long) (payload[payloadOffset + 4] & 0xFF) << 24) | ((long) (payload[payloadOffset + 5] & 0xFF) << 16) | ((long) (payload[payloadOffset + 6] & 0xFF) << 8) | (payload[payloadOffset + 7] & 0xFF);
        int debugLength = payloadLength - 8;
        byte[] debugData = new byte[Math.max(debugLength, 0)];
        if (debugLength > 0) {
            System.arraycopy(payload, payloadOffset + 8, debugData, 0, debugLength);
        }
        dst.offerMessage(new Http2GoAwayMessage(lastStreamId, errorCode, debugData));
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H2-RCV] ch=" + channelID + " GOAWAY lastStream=" + lastStreamId + " errorCode=0x" + Long.toHexString(errorCode));
        }
    }

    private void processWindowUpdate(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<Http2Message> dst, int streamId, byte[] payload, int payloadOffset, int payloadLength) {
        if (payloadLength != 4) {
            throw new HttpProtocolViolationException("HTTP/2: WINDOW_UPDATE frame must be 4 bytes");
        }
        int increment = ((payload[payloadOffset] & 0x7F) << 24) | ((payload[payloadOffset + 1] & 0xFF) << 16) | ((payload[payloadOffset + 2] & 0xFF) << 8) | (payload[payloadOffset + 3] & 0xFF);
        if (increment == 0) {
            throw new HttpProtocolViolationException("HTTP/2: WINDOW_UPDATE increment must be non-zero");
        }
        if (streamId > 0) {
            state.adjustStreamSendWindow(streamId, increment);
        }
        dst.offerMessage(new Http2WindowUpdateMessage(streamId, increment));
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H2-RCV] ch=" + channelID + " WINDOW_UPDATE " + (streamId == 0 ? "conn" : "stream=" + streamId) + " increment=" + increment);
        }
    }

    boolean isServerMode() {
        return this.serverMode;
    }

    @Override
    public void onClose(ProtoContext context) {
        Http2DecoderContent state = context.context(Http2DecoderContent.class);
        if (state != null) {
            state.releaseAll();
        }
    }
}