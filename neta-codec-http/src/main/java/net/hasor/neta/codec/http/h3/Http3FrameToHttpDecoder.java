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
package net.hasor.neta.codec.http.h3;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.quic.QuicVarInt;
import net.hasor.neta.codec.http.*;

/**
 * HTTP/3 semantic decoder that converts {@link Http3Frame} objects into
 * standard {@link HttpObject} instances.
 * <p>
 * This decoder handles QPACK header decompression, HTTP message creation,
 * and stream state management. It processes HEADERS, DATA, SETTINGS,
 * and GOAWAY frames.
 * <p>
 * <b>Decode path:</b> {@code ByteBuf → Http3Frame → HttpObject}
 * <p>
 * All decoded messages are emitted as standard {@link HttpObject} types
 * ({@link HttpRequest}, {@link HttpResponse}, {@link HttpContent},
 * {@link LastHttpContent}), so the application layer is protocol-agnostic.
 * @see Http3FrameDecoder
 * @see Http3Frame
 */
public class Http3FrameToHttpDecoder implements ProtoHandler<Http3Frame, HttpObject> {
    private static final Logger logger = Logger.getLogger(Http3FrameToHttpDecoder.class);

    private final boolean serverMode;
    private final int     maxTableSize;
    private final int     maxHeaderListSize;

    /**
     * Creates a new HTTP/3 semantic decoder with default QPACK settings.
     * @param serverMode true for server-side (expects requests), false for client-side (expects responses)
     */
    public Http3FrameToHttpDecoder(boolean serverMode) {
        this(serverMode, 4096, 65536);
    }

    /**
     * Creates a new HTTP/3 semantic decoder with custom QPACK settings.
     * @param serverMode true for server-side (expects requests), false for client-side (expects responses)
     * @param maxTableSize maximum QPACK dynamic table size in bytes
     * @param maxHeaderListSize maximum total size of all decoded headers
     */
    public Http3FrameToHttpDecoder(boolean serverMode, int maxTableSize, int maxHeaderListSize) {
        this.serverMode = serverMode;
        this.maxTableSize = maxTableSize;
        this.maxHeaderListSize = maxHeaderListSize;
    }

    @Override
    public void onInit(String name, int poolSize, ProtoContext context) throws Throwable {
        context.context(Http3DecoderContent.class, new Http3DecoderContent(maxTableSize, maxHeaderListSize));
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Http3Frame> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        Http3DecoderContent state = context.context(Http3DecoderContent.class);
        boolean isPrintLog = context.getConfig().isPrintLog();

        while (src.hasMore()) {
            Http3Frame frame = src.takeMessage();
            if (frame == null) {
                continue;
            }

            long streamId = frame.streamId();
            boolean isUnidirectional = (streamId & 0x02) != 0;

            if (isUnidirectional) {
                processControlFrame(context, state, frame, isPrintLog);
            } else {
                processRequestFrame(context, state, dst, frame, isPrintLog);
            }
        }

        return ProtoStatus.Next;
    }

    /**
     * Processes a frame on a bidirectional request stream (HEADERS or DATA).
     */
    private void processRequestFrame(ProtoContext context, Http3DecoderContent state, ProtoSndQueue<HttpObject> dst, Http3Frame frame, boolean isPrintLog) {
        long streamId = frame.streamId();
        Http3Stream stream = state.getOrCreateStream(streamId);
        stream.state(Http3StreamState.OPEN);

        if (frame.type() == Http3FrameType.HEADERS) {
            processHeadersFrame(context, state, dst, stream, frame, isPrintLog);
        } else if (frame.type() == Http3FrameType.DATA) {
            processDataFrame(context, state, dst, stream, frame, isPrintLog);
        }
        // PUSH_PROMISE, reserved/grease frames are silently ignored
    }

    /**
     * Processes a HEADERS frame and emits HttpRequest, HttpResponse, or trailers.
     */
    private void processHeadersFrame(ProtoContext context, Http3DecoderContent state, ProtoSndQueue<HttpObject> dst, Http3Stream stream, Http3Frame frame, boolean isPrintLog) {
        HttpHeaders headers = state.decodeHeaders(frame.payload(), frame.payloadOffset(), frame.payloadLength());

        if (!stream.headersReceived()) {
            // Initial headers - create request or response
            stream.markHeadersReceived();

            if (serverMode) {
                emitHttpRequest(context, state, dst, stream, headers, isPrintLog);
            } else {
                emitHttpResponse(context, dst, stream, headers, isPrintLog);
            }
        } else {
            // Trailers
            emitTrailers(context, state, dst, stream, headers, isPrintLog);
        }

        // If FIN on initial HEADERS (no body): emit empty LastHttpContent
        if (frame.fin() && !stream.trailersReceived() && stream.state() == Http3StreamState.OPEN) {
            dst.offerMessage(new DefaultLastHttpContent(context.byteBufAllocator().buffer(0)));
            stream.state(Http3StreamState.HALF_CLOSED);
            state.closeStream(stream.streamId());

            if (isPrintLog) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H3-RCV] ch=" + channelID + " END stream=" + stream.streamId() + " (headers-only)");
            }
        }
    }

    /**
     * Emits an HttpRequest from decoded HEADERS (server mode).
     */
    private void emitHttpRequest(ProtoContext context, Http3DecoderContent state, ProtoSndQueue<HttpObject> dst, Http3Stream stream, HttpHeaders headers, boolean isPrintLog) {
        String method = headers.getString(":method");
        String path = headers.getString(":path");
        String authority = headers.getString(":authority");
        String scheme = headers.getString(":scheme");

        if (StringUtils.isBlank(method)) {
            method = "GET";
        }
        if (StringUtils.isBlank(path)) {
            path = "/";
        }

        HttpMethod httpMethod = HttpMethod.valueOf(method);
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_3_0, httpMethod, path);
        request.streamId((int) stream.streamId());
        DefaultLastHttpHeaders regularHeaders = new DefaultLastHttpHeaders();

        // Copy non-pseudo headers
        for (String name : headers.headerNames()) {
            if (!StringUtils.startsWith(name, ":")) {
                for (String value : headers.getValues(name)) {
                    regularHeaders.addHeader(name, value);
                }
            }
        }

        // Map pseudo-headers
        if (StringUtils.isNotBlank(authority)) {
            regularHeaders.addHeader(HttpHeaderNames.HOST, authority);
        }
        if (StringUtils.isNotBlank(scheme)) {
            regularHeaders.addHeader(HttpHeaderNames.X_FORWARDED_PROTO, scheme);
        }

        dst.offerMessage(request);
        regularHeaders.streamId((int) stream.streamId());
        dst.offerMessage(regularHeaders);
        state.offerResponseStreamId(stream.streamId());

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-RCV] ch=" + channelID + " REQUEST stream=" + stream.streamId() + " " + method + " " + path);
        }
    }

    /**
     * Emits an HttpResponse from decoded HEADERS (client mode).
     */
    private void emitHttpResponse(ProtoContext context, ProtoSndQueue<HttpObject> dst, Http3Stream stream, HttpHeaders headers, boolean isPrintLog) {
        String statusStr = headers.getString(":status");
        int statusCode = 200;
        if (StringUtils.isNotBlank(statusStr)) {
            statusCode = Integer.parseInt(statusStr);
        }

        HttpStatus status = HttpStatus.valueOf(statusCode);
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_3_0, status);
        response.streamId((int) stream.streamId());
        DefaultLastHttpHeaders regularHeaders = new DefaultLastHttpHeaders();

        for (String name : headers.headerNames()) {
            if (!StringUtils.startsWith(name, ":")) {
                for (String value : headers.getValues(name)) {
                    regularHeaders.addHeader(name, value);
                }
            }
        }

        dst.offerMessage(response);
        regularHeaders.streamId((int) stream.streamId());
        dst.offerMessage(regularHeaders);

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-RCV] ch=" + channelID + " RESPONSE stream=" + stream.streamId() + " status=" + statusCode);
        }
    }

    /**
     * Emits trailers as LastHttpContent.
     */
    private void emitTrailers(ProtoContext context, Http3DecoderContent state, ProtoSndQueue<HttpObject> dst, Http3Stream stream, HttpHeaders headers, boolean isPrintLog) {
        stream.markTrailersReceived();
        DefaultTrailerHttpHeaders trailers = new DefaultTrailerHttpHeaders();

        for (String name : headers.headerNames()) {
            if (!StringUtils.startsWith(name, ":")) {
                for (String value : headers.getValues(name)) {
                    trailers.addHeader(name, value);
                }
            }
        }

        trailers.streamId((int) stream.streamId());
        dst.offerMessage(trailers);
        DefaultLastHttpContent lastContent = new DefaultLastHttpContent(context.byteBufAllocator().buffer(0));
        lastContent.streamId((int) stream.streamId());
        dst.offerMessage(lastContent);
        stream.state(Http3StreamState.HALF_CLOSED);
        state.closeStream(stream.streamId());

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-RCV] ch=" + channelID + " TRAILERS stream=" + stream.streamId());
        }
    }

    /**
     * Processes a DATA frame and emits HttpContent or LastHttpContent.
     */
    private void processDataFrame(ProtoContext context, Http3DecoderContent state, ProtoSndQueue<HttpObject> dst, Http3Stream stream, Http3Frame frame, boolean isPrintLog) {
        int length = frame.payloadLength();
        ByteBuf content = context.byteBufAllocator().buffer(Math.max(length, 1));
        if (length > 0) {
            content.writeBytes(frame.payload(), frame.payloadOffset(), length);
        }
        content.markWriter();

        if (frame.fin()) {
            DefaultLastHttpContent lastContent = new DefaultLastHttpContent(content);
            lastContent.streamId((int) stream.streamId());
            dst.offerMessage(lastContent);
            stream.state(Http3StreamState.HALF_CLOSED);
            state.closeStream(stream.streamId());

            if (isPrintLog) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H3-RCV] ch=" + channelID + " LAST-DATA stream=" + stream.streamId() + " len=" + length);
            }
        } else {
            DefaultHttpContent httpContent = new DefaultHttpContent(content);
            httpContent.streamId((int) stream.streamId());
            dst.offerMessage(httpContent);

            if (isPrintLog) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H3-RCV] ch=" + channelID + " DATA stream=" + stream.streamId() + " len=" + length);
            }
        }
    }

    /**
     * Processes a control frame (SETTINGS, GOAWAY) from a unidirectional stream.
     */
    private void processControlFrame(ProtoContext context, Http3DecoderContent state, Http3Frame frame, boolean isPrintLog) {
        if (frame.type() == Http3FrameType.SETTINGS) {
            processSettingsFrame(context, state, frame, isPrintLog);
        } else if (frame.type() == Http3FrameType.GOAWAY) {
            long[] goawayId = QuicVarInt.decode(frame.payload(), frame.payloadOffset());
            fireEvent(context, HttpConnectionGoAwayEvent.class, new HttpConnectionGoAwayEvent(goawayId[0], 0L, new byte[0]));
            if (isPrintLog) {
                long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
                logger.info("[H3-RCV] ch=" + channelID + " GOAWAY lastAcceptedId=" + goawayId[0]);
            }
        }
    }

    /**
     * Processes a SETTINGS frame payload and applies remote settings.
     */
    private void processSettingsFrame(ProtoContext context, Http3DecoderContent state, Http3Frame frame, boolean isPrintLog) {
        byte[] data = frame.payload();
        int pos = frame.payloadOffset();
        int end = pos + frame.payloadLength();

        while (pos < end) {
            long[] idResult = QuicVarInt.decode(data, pos);
            long settingId = idResult[0];
            pos += (int) idResult[1];

            long[] valResult = QuicVarInt.decode(data, pos);
            long settingValue = valResult[0];
            pos += (int) valResult[1];

            state.applyRemoteSetting(settingId, settingValue);
        }

        state.markSettingsReceived();

        if (isPrintLog) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[H3-RCV] ch=" + channelID + " SETTINGS received");
        }
    }

    /** Returns true if this is server mode. */
    boolean isServerMode() {
        return this.serverMode;
    }

    private <T> void fireEvent(ProtoContext context, Class<T> eventType, T event) {
        try {
            context.fireEvent(eventType, event);
        } catch (Throwable e) {
            logger.error("Error occurred while publishing HTTP/3 event: " + eventType.getSimpleName(), e);
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        Http3DecoderContent state = context.context(Http3DecoderContent.class);
        if (state != null) {
            state.releaseAll();
        }
    }
}
