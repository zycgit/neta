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
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;

/**
 * Semantic HTTP/2 frame decoder that converts {@link Http2Frame} objects into
 * standard {@link HttpObject} instances.
 * <p>
 * This handler processes the typed HTTP/2 frames emitted by {@link Http2FrameDecoder},
 * performs HPACK header decompression, manages stream state, and emits the same
 * {@link HttpObject} types used by HTTP/1.x (e.g., {@link DefaultHttpRequest},
 * {@link DefaultHttpResponse}, {@link DefaultHttpContent}, {@link DefaultLastHttpContent}).
 * <p>
 * <b>Design Principle:</b> Regardless of the underlying HTTP version (1.x, 2, 3),
 * the upper layer always receives the same {@link HttpObject} message types, enabling
 * protocol-agnostic application logic.
 * <p>
 * <b>Decode path:</b> {@code ByteBuf → Http2Frame → HttpObject}
 * @see Http2Frame
 * @see Http2FrameDecoder
 */
public class Http2FrameToHttpDecoder implements ProtoHandler<Http2Frame, HttpObject> {
    private static final Logger        logger = Logger.getLogger(Http2FrameToHttpDecoder.class);
    private final        boolean       serverMode;
    private final        Http2Settings localSettings;
    private final        int           maxHeaderTableSize;
    private final        int           maxHeaderListSize;

    /**
     * Creates a new HTTP/2 frame-to-HttpObject decoder with default HPACK settings.
     * @param serverMode true for server-side, false for client-side
     */
    public Http2FrameToHttpDecoder(boolean serverMode) {
        this(serverMode, 4096, 8192);
    }

    /**
     * Creates a new HTTP/2 frame-to-HttpObject decoder with custom HPACK settings.
     * @param serverMode true for server-side, false for client-side
     * @param maxHeaderTableSize maximum HPACK dynamic table size in bytes
     * @param maxHeaderListSize maximum total size of all decoded headers
     */
    public Http2FrameToHttpDecoder(boolean serverMode, int maxHeaderTableSize, int maxHeaderListSize) {
        this.serverMode = serverMode;
        this.localSettings = new Http2Settings();
        this.maxHeaderTableSize = maxHeaderTableSize;
        this.maxHeaderListSize = maxHeaderListSize;
    }

    /** Builds a WINDOW_UPDATE frame with the given stream ID and increment. */
    static Http2Frame buildWindowUpdate(int streamId, int increment) {
        byte[] payload = new byte[4];
        payload[0] = (byte) ((increment >> 24) & 0x7F);
        payload[1] = (byte) ((increment >> 16) & 0xFF);
        payload[2] = (byte) ((increment >> 8) & 0xFF);
        payload[3] = (byte) (increment & 0xFF);
        return Http2Frame.windowUpdate(streamId, payload);
    }

    @Override
    public void onInit(ProtoContext context) {
        context.context(Http2DecoderContent.class, new Http2DecoderContent(this.serverMode, this.maxHeaderTableSize, this.maxHeaderListSize));
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<Http2Frame> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        Http2DecoderContent state = context.context(Http2DecoderContent.class);
        while (src.hasMore()) {
            Http2Frame frame = src.takeMessage();
            if (frame == null) {
                continue;
            }
            // Mark preface as received once we start getting frames
            // (binary decoder only emits frames after validating the preface)
            if (!state.isPrefaceReceived()) {
                state.markPrefaceReceived();
            }

            processFrame(state, context, dst, frame.type(), frame.flags(), frame.streamId(), frame.payload(), frame.payloadOffset(), frame.payloadLength());
        }
        return ProtoStatus.Next;
    }

    /**
     * Processes a single HTTP/2 frame and emits corresponding HttpObject(s).
     */
    private void processFrame(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<HttpObject> dst, int type, int flags, int streamId, byte[] payload, int payloadOffset, int payloadLength) {
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            logger.info("[H2-FRAME] type=" + type + " flags=" + flags + " streamId=" + streamId + " payloadLen=" + payloadLength);
        }
        switch (type) {
            case Http2FrameType.DATA:
                processDataFrame(state, context, dst, flags, streamId, payload, payloadOffset, payloadLength);
                break;
            case Http2FrameType.HEADERS:
                processHeadersFrame(state, context, dst, flags, streamId, payload, payloadOffset, payloadLength);
                break;
            case Http2FrameType.PRIORITY:
                // Priority is advisory; we acknowledge but don't act on it
                break;
            case Http2FrameType.RST_STREAM:
                processRstStream(state, streamId, payload, payloadOffset, payloadLength);
                break;
            case Http2FrameType.SETTINGS:
                processSettings(state, context, flags, payload, payloadOffset, payloadLength);
                break;
            case Http2FrameType.PUSH_PROMISE:
                // Server push is rarely used; skip for now
                break;
            case Http2FrameType.PING:
                processPing(state, flags, payload, payloadOffset, payloadLength);
                break;
            case Http2FrameType.GOAWAY:
                processGoaway(payload, payloadOffset, payloadLength);
                break;
            case Http2FrameType.WINDOW_UPDATE:
                processWindowUpdate(state, streamId, payload, payloadOffset, payloadLength);
                break;
            case Http2FrameType.CONTINUATION:
                processContinuationFrame(state, context, dst, flags, streamId, payload, payloadOffset, payloadLength);
                break;
            default:
                // Unknown frame types MUST be ignored (RFC 9113, Section 4.1)
                break;
        }
    }

    /**
     * Processes a DATA frame and emits HttpContent / LastHttpContent.
     */
    private void processDataFrame(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<HttpObject> dst, int flags, int streamId, byte[] payload, int payloadOffset, int payloadLength) {
        int offset = payloadOffset;
        int dataLength = payloadLength;

        // Handle padding
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

            if (endStream) {
                DefaultLastHttpContent lastContent = new DefaultLastHttpContent(content);
                lastContent.streamId(streamId);
                dst.offerMessage(lastContent);
                // Record stream ID for response association (DATA with END_STREAM completes the request)
                state.offerResponseStreamId(streamId);
                Http2Stream stream = state.getStream(streamId);
                if (stream != null) {
                    stream.state(Http2StreamState.HALF_CLOSED_REMOTE);
                }
            } else {
                DefaultHttpContent chunk = new DefaultHttpContent(content);
                chunk.streamId(streamId);
                dst.offerMessage(chunk);
            }

            // RFC 9113 §6.9: Send WINDOW_UPDATE to replenish flow control windows
            // so the client can continue sending DATA frames.
            if (dataLength > 0) {
                state.offerWindowUpdate(buildWindowUpdate(0, dataLength));        // connection-level
                state.offerWindowUpdate(buildWindowUpdate(streamId, dataLength)); // stream-level
            }
        }
    }

    /**
     * Processes a HEADERS frame and emits HttpRequest or HttpResponse.
     * If END_HEADERS is not set, accumulates for CONTINUATION frames.
     */
    private void processHeadersFrame(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<HttpObject> dst, int flags, int streamId, byte[] payload, int payloadOffset, int payloadLength) {
        int offset = payloadOffset;
        int headerBlockLength = payloadLength;

        // Handle padding
        if (Http2Flags.padded(flags)) {
            int padLength = payload[offset] & 0xFF;
            offset += 1;
            headerBlockLength = payloadLength - 1 - padLength;
        }

        // Handle priority
        if (Http2Flags.priority(flags)) {
            // Skip 5 bytes: stream dependency (4) + weight (1)
            offset += 5;
            headerBlockLength -= 5;
        }

        if (headerBlockLength < 0) {
            throw new HttpProtocolViolationException("HTTP/2: HEADERS frame has negative header block length");
        }

        // Get or create stream
        Http2Stream stream = state.getOrCreateStream(streamId);
        stream.state(Http2StreamState.OPEN);

        // Proactively expand stream-level flow control window.
        // Some HTTP/2 clients (e.g. OkHttp) do not apply SETTINGS INITIAL_WINDOW_SIZE
        // to their stream send windows, relying instead on WINDOW_UPDATE frames.
        // Send a per-stream WINDOW_UPDATE to ensure the client's send window matches
        // the server's desired initial window, preventing upload stalls.
        if (state.serverInitialWindowSize() > 65535) {
            state.offerWindowUpdate(buildWindowUpdate(streamId, state.serverInitialWindowSize() - 65535));
        }

        if (Http2Flags.endHeaders(flags)) {
            // Complete header block - decode immediately
            HttpHeaders headers = state.decodeHeaders(payload, offset, headerBlockLength);
            emitHttpMessage(state, dst, streamId, headers, Http2Flags.endStream(flags));
        } else {
            // Need CONTINUATION frames - accumulate.
            // Save the END_STREAM flag now: the HEADERS frame determines whether the
            // stream will be half-closed once all header fragments arrive (via CONTINUATION).
            ByteBuf headerBlock = context.byteBufAllocator().buffer(headerBlockLength + 256);
            headerBlock.writeBytes(payload, offset, headerBlockLength);
            stream.accumulatedHeaderBlock(headerBlock);
            stream.setEndStreamPending(Http2Flags.endStream(flags));
        }
    }

    /**
     * Processes a CONTINUATION frame and appends to the header block being accumulated.
     */
    private void processContinuationFrame(Http2DecoderContent state, ProtoContext context, ProtoSndQueue<HttpObject> dst, int flags, int streamId, byte[] payload, int payloadOffset, int payloadLength) {
        Http2Stream stream = state.getStream(streamId);
        if (stream == null || stream.accumulatedHeaderBlock() == null) {
            throw new HttpProtocolViolationException("HTTP/2: CONTINUATION frame without prior HEADERS on stream " + streamId);
        }

        ByteBuf headerBlock = stream.accumulatedHeaderBlock();
        headerBlock.writeBytes(payload, payloadOffset, payloadLength);

        if (Http2Flags.endHeaders(flags)) {
            // All header block fragments received - decode
            headerBlock.markWriter();
            int readable = headerBlock.readableBytes();
            byte[] allHeaders = new byte[readable];
            headerBlock.getBytes(0, allHeaders, 0, readable);
            // Read the deferred END_STREAM flag before releasing stream state
            boolean endStream = stream.isEndStreamPending();
            headerBlock.free();
            stream.accumulatedHeaderBlock(null);

            HttpHeaders headers = state.decodeHeaders(allHeaders, 0, allHeaders.length);
            emitHttpMessage(state, dst, streamId, headers, endStream);
        }
    }

    /**
     * Converts decoded HTTP/2 pseudo-headers + regular headers into standard HttpObject.
     * <p>
     * HTTP/2 pseudo-headers (RFC 9113, Section 8.3):
     * <ul>
     *   <li>{@code :method} → HttpRequest.method()</li>
     *   <li>{@code :path} → HttpRequest.uri()</li>
     *   <li>{@code :scheme} → stored as header</li>
     *   <li>{@code :authority} → stored as "host" header</li>
     *   <li>{@code :status} → HttpResponse.status()</li>
     * </ul>
     */
    private void emitHttpMessage(Http2DecoderContent state, ProtoSndQueue<HttpObject> dst, int streamId, HttpHeaders headers, boolean endStream) {
        state.setLastEmittedStreamId(streamId);
        // When a complete request is emitted (endStream), record its stream ID
        // so the encoder can associate the response with the correct stream.
        if (endStream) {
            state.offerResponseStreamId(streamId);
        }
        String status = headers.get(":status");
        headers.remove(":status");

        if (status != null) {
            // This is a response
            headers.remove(":scheme");
            headers.remove(":method");
            headers.remove(":path");
            headers.remove(":authority");

            int statusCode = Integer.parseInt(status);
            HttpStatus httpStatus = HttpStatus.valueOf(statusCode);
            DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_2_0, httpStatus, headers);
            response.streamId(streamId);
            dst.offerMessage(response);
        } else {
            // This is a request
            String method = headers.get(":method");
            String path = headers.get(":path");
            String authority = headers.get(":authority");
            String scheme = headers.get(":scheme");
            headers.remove(":method");
            headers.remove(":path");
            headers.remove(":authority");
            headers.remove(":scheme");

            if (StringUtils.isBlank(method) || StringUtils.isBlank(path)) {
                throw new HttpProtocolViolationException("HTTP/2: missing required pseudo-header :method or :path");
            }

            // Map :authority to host header
            if (StringUtils.isNotBlank(authority) && StringUtils.isBlank(headers.get(HttpHeaderNames.HOST))) {
                headers.add(HttpHeaderNames.HOST, authority);
            }
            // Map :scheme → X-Forwarded-Proto so downstream handlers know the original scheme
            if (StringUtils.isNotBlank(scheme)) {
                headers.add(HttpHeaderNames.X_FORWARDED_PROTO, scheme);
            }

            HttpMethod httpMethod = HttpMethod.valueOf(method);
            DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_2_0, httpMethod, path, headers);
            request.streamId(streamId);
            dst.offerMessage(request);
        }

        if (endStream) {
            DefaultLastHttpContent emptyLast = new DefaultLastHttpContent();
            emptyLast.streamId(streamId);
            dst.offerMessage(emptyLast);
            Http2Stream stream = state.getStream(streamId);
            if (stream != null) {
                stream.state(Http2StreamState.HALF_CLOSED_REMOTE);
            }
        }
    }

    /**
     * Processes PING frame. If it's not an ACK, queue the opaque data for sending back a PING ACK.
     */
    private void processPing(Http2DecoderContent state, int flags, byte[] payload, int payloadOffset, int payloadLength) {
        if (payloadLength != 8) {
            throw new HttpProtocolViolationException("HTTP/2: PING frame must be 8 bytes, got " + payloadLength);
        }
        if (!Http2Flags.ack(flags)) {
            byte[] copy = new byte[8];
            System.arraycopy(payload, payloadOffset, copy, 0, 8);
            state.offerPingAck(copy);
        }
    }

    /** Processes RST_STREAM frame - terminates a stream. */
    private void processRstStream(Http2DecoderContent state, int streamId, byte[] payload, int payloadOffset, int payloadLength) {
        if (payloadLength != 4) {
            throw new HttpProtocolViolationException("HTTP/2: RST_STREAM frame must be 4 bytes, got " + payloadLength);
        }
        state.closeStream(streamId);
    }

    /** Processes SETTINGS frame - updates connection parameters. */
    private void processSettings(Http2DecoderContent state, ProtoContext context, int flags, byte[] payload, int payloadOffset, int payloadLength) {
        if (Http2Flags.ack(flags)) {
            return;
        }
        if (payloadLength % 6 != 0) {
            throw new HttpProtocolViolationException("HTTP/2: SETTINGS frame length must be a multiple of 6");
        }
        for (int i = payloadOffset; i < payloadOffset + payloadLength; i += 6) {
            int id = ((payload[i] & 0xFF) << 8) | (payload[i + 1] & 0xFF);
            long value = ((long) (payload[i + 2] & 0xFF) << 24) | ((payload[i + 3] & 0xFF) << 16) | ((payload[i + 4] & 0xFF) << 8) | (payload[i + 5] & 0xFF);
            if (context.getConfig() != null && context.getConfig().isPrintLog()) {
                logger.info("[H2-REMOTE-SETTING] id=" + id + " value=" + value);
            }
            state.applyRemoteSetting(id, value);
        }

        state.markSettingsAckPending();
    }

    /** Processes GOAWAY frame. */
    private void processGoaway(byte[] payload, int payloadOffset, int payloadLength) {
        if (payloadLength < 8) {
            throw new HttpProtocolViolationException("HTTP/2: GOAWAY frame too short");
        }
    }

    /** Processes WINDOW_UPDATE frame - adjusts flow control window. */
    private void processWindowUpdate(Http2DecoderContent state, int streamId, byte[] payload, int payloadOffset, int payloadLength) {
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
    }

    /** Returns true if this is server mode. */
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
