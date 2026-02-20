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
package net.hasor.neta.codec.http2;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.constant.HttpMethod;
import net.hasor.neta.codec.http.constant.HttpStatus;
import net.hasor.neta.codec.http.constant.HttpVersion;

/**
 * HTTP/2 frame decoder that converts binary frames into standard {@link HttpObject} instances.
 * <p>
 * This decoder implements the HTTP/2 binary framing layer defined in RFC 9113.
 * It parses the 9-byte frame header, handles stream multiplexing, performs
 * HPACK header decompression, and emits the same {@link HttpObject} types
 * used by HTTP/1.x (e.g., {@link DefaultHttpRequest}, {@link DefaultHttpResponse},
 * {@link DefaultHttpContent}, {@link DefaultLastHttpContent}).
 * <p>
 * <b>Design Principle:</b> Regardless of the underlying HTTP version (1.x, 2, 3),
 * the upper layer always receives the same {@link HttpObject} message types. This
 * allows applications to be protocol-agnostic.
 * <p>
 * Frame format (RFC 9113, Section 4):
 * <pre>
 *   +-----------------------------------------------+
 *   |                 Length (24)                     |
 *   +---------------+---------------+---------------+
 *   |   Type (8)    |   Flags (8)   |
 *   +-+-------------+---------------+--------------+
 *   |R|                 Stream Identifier (31)       |
 *   +=+==============================================+
 *   |                 Frame Payload (0...)            |
 *   +------------------------------------------------+
 * </pre>
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLastDecoder("h2-frames", new Http2FrameDecoder(true));
 *   ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
 * </pre>
 */
public class Http2FrameDecoder implements ProtoHandler<ByteBuf, HttpObject> {
    /** HTTP/2 connection preface: "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n" */
    private static final byte[] CONNECTION_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    /** Frame header size: 9 bytes */
    private static final int    FRAME_HEADER_SIZE  = 9;

    private final boolean                   serverMode;
    private final HpackDecoder              hpackDecoder;
    private final Map<Integer, Http2Stream> streams;
    private final Http2Settings             localSettings;
    private final Http2Settings             remoteSettings;

    private ByteBuf accumulator;
    private boolean prefaceReceived;
    private boolean pendingSettingsAck;

    /** Reusable frame header buffer to avoid per-frame byte[9] allocation. */
    private final byte[] frameHeaderBuf  = new byte[FRAME_HEADER_SIZE];
    /** Reusable preface buffer. */
    private final byte[] prefaceCheckBuf = new byte[CONNECTION_PREFACE.length];

    /**
     * Creates a new HTTP/2 frame decoder with default HPACK settings.
     * @param serverMode true for server-side (expects client preface), false for client-side
     */
    public Http2FrameDecoder(boolean serverMode) {
        this(serverMode, 4096, 8192);
    }

    /**
     * Creates a new HTTP/2 frame decoder with custom HPACK settings.
     * @param serverMode true for server-side (expects client preface), false for client-side
     * @param maxHeaderTableSize maximum HPACK dynamic table size in bytes
     * @param maxHeaderListSize maximum total size of all decoded headers
     */
    public Http2FrameDecoder(boolean serverMode, int maxHeaderTableSize, int maxHeaderListSize) {
        this.serverMode = serverMode;
        this.hpackDecoder = new HpackDecoder(maxHeaderTableSize, maxHeaderListSize);
        this.streams = new HashMap<>();
        this.localSettings = new Http2Settings();
        this.remoteSettings = new Http2Settings();
        this.prefaceReceived = !serverMode; // Client doesn't receive a preface
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        if (this.accumulator != null) {
            this.accumulator.free();
        }
        this.accumulator = ByteBufUtils.queueBuffer(src);

        // Handle connection preface for server mode
        if (!prefaceReceived) {
            if (accumulator.readableBytes() < CONNECTION_PREFACE.length) {
                accumulator.markReader();
                return ProtoStatus.Next;
            }
            byte[] prefaceBytes = this.prefaceCheckBuf;
            accumulator.getBytes(0, prefaceBytes, 0, prefaceBytes.length);
            for (int i = 0; i < CONNECTION_PREFACE.length; i++) {
                if (prefaceBytes[i] != CONNECTION_PREFACE[i]) {
                    throw new HttpProtocolException("HTTP/2: invalid connection preface");
                }
            }
            accumulator.skipReadableBytes(CONNECTION_PREFACE.length);
            prefaceReceived = true;
        }

        // Decode frames
        while (accumulator.readableBytes() >= FRAME_HEADER_SIZE) {
            // Read frame header directly from ByteBuf (no byte[] allocation)
            // Peek at header without consuming - use getBytes for read-ahead
            byte[] headerBuf = this.frameHeaderBuf;
            accumulator.getBytes(0, headerBuf, 0, FRAME_HEADER_SIZE);

            int payloadLength = ((headerBuf[0] & 0xFF) << 16) | ((headerBuf[1] & 0xFF) << 8) | (headerBuf[2] & 0xFF);
            int type = headerBuf[3] & 0xFF;
            int flags = headerBuf[4] & 0xFF;
            int streamId = ((headerBuf[5] & 0x7F) << 24) | ((headerBuf[6] & 0xFF) << 16) | ((headerBuf[7] & 0xFF) << 8) | (headerBuf[8] & 0xFF);

            // Validate frame size
            if (payloadLength > localSettings.maxFrameSize()) {
                throw new HttpProtocolException("HTTP/2: frame size exceeds SETTINGS_MAX_FRAME_SIZE: " + payloadLength);
            }

            int totalFrameSize = FRAME_HEADER_SIZE + payloadLength;
            if (accumulator.readableBytes() < totalFrameSize) {
                // Not enough data for the full frame; wait for more
                break;
            }

            // Skip frame header
            accumulator.skipReadableBytes(FRAME_HEADER_SIZE);

            // Read payload
            byte[] payload = new byte[payloadLength];
            if (payloadLength > 0) {
                accumulator.getBytes(0, payload, 0, payloadLength);
                accumulator.skipReadableBytes(payloadLength);
            }

            // Process frame by type
            processFrame(context, dst, type, flags, streamId, payload);
        }

        accumulator.markReader();
        return ProtoStatus.Next;
    }

    /**
     * Processes a single HTTP/2 frame and emits corresponding HttpObject(s).
     */
    private void processFrame(ProtoContext context, ProtoSndQueue<HttpObject> dst, int type, int flags, int streamId, byte[] payload) {
        switch (type) {
            case Http2FrameType.DATA:
                processDataFrame(context, dst, flags, streamId, payload);
                break;
            case Http2FrameType.HEADERS:
                processHeadersFrame(context, dst, flags, streamId, payload);
                break;
            case Http2FrameType.PRIORITY:
                // Priority is advisory; we acknowledge but don't act on it
                break;
            case Http2FrameType.RST_STREAM:
                processRstStream(streamId, payload);
                break;
            case Http2FrameType.SETTINGS:
                processSettings(flags, payload);
                break;
            case Http2FrameType.PUSH_PROMISE:
                // Server push is rarely used; skip for now
                break;
            case Http2FrameType.PING:
                // PING handled at connection level; skip in decoder
                break;
            case Http2FrameType.GOAWAY:
                processGoaway(payload);
                break;
            case Http2FrameType.WINDOW_UPDATE:
                processWindowUpdate(streamId, payload);
                break;
            case Http2FrameType.CONTINUATION:
                processContinuationFrame(context, dst, flags, streamId, payload);
                break;
            default:
                // Unknown frame types MUST be ignored (RFC 9113, Section 4.1)
                break;
        }
    }

    /**
     * Processes a DATA frame and emits HttpContent / LastHttpContent.
     */
    private void processDataFrame(ProtoContext context, ProtoSndQueue<HttpObject> dst, int flags, int streamId, byte[] payload) {
        int offset = 0;
        int dataLength = payload.length;

        // Handle padding
        if (Http2Flags.padded(flags)) {
            int padLength = payload[0] & 0xFF;
            offset = 1;
            dataLength = payload.length - 1 - padLength;
            if (dataLength < 0) {
                throw new HttpProtocolException("HTTP/2: DATA frame padding exceeds payload");
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
                dst.offerMessage(new DefaultLastHttpContent(content));
                Http2Stream stream = streams.get(streamId);
                if (stream != null) {
                    stream.state(Http2StreamState.HALF_CLOSED_REMOTE);
                }
            } else {
                dst.offerMessage(new DefaultHttpContent(content));
            }
        }
    }

    /**
     * Processes a HEADERS frame and emits HttpRequest or HttpResponse.
     * If END_HEADERS is not set, accumulates for CONTINUATION frames.
     */
    private void processHeadersFrame(ProtoContext context, ProtoSndQueue<HttpObject> dst, int flags, int streamId, byte[] payload) {
        int offset = 0;
        int headerBlockLength = payload.length;

        // Handle padding
        if (Http2Flags.padded(flags)) {
            int padLength = payload[0] & 0xFF;
            offset = 1;
            headerBlockLength = payload.length - 1 - padLength;
        }

        // Handle priority
        if (Http2Flags.priority(flags)) {
            // Skip 5 bytes: stream dependency (4) + weight (1)
            offset += 5;
            headerBlockLength -= 5;
        }

        if (headerBlockLength < 0) {
            throw new HttpProtocolException("HTTP/2: HEADERS frame has negative header block length");
        }

        // Get or create stream
        Http2Stream stream = streams.computeIfAbsent(streamId, id -> new Http2Stream(id, remoteSettings.initialWindowSize()));
        stream.state(Http2StreamState.OPEN);

        if (Http2Flags.endHeaders(flags)) {
            // Complete header block - decode immediately
            HttpHeaders headers = hpackDecoder.decode(payload, offset, headerBlockLength);
            emitHttpMessage(dst, streamId, headers, Http2Flags.endStream(flags));
        } else {
            // Need CONTINUATION frames - accumulate
            ByteBuf headerBlock = context.byteBufAllocator().buffer(headerBlockLength + 256);
            headerBlock.writeBytes(payload, offset, headerBlockLength);
            stream.accumulatedHeaderBlock(headerBlock);
        }
    }

    /**
     * Processes a CONTINUATION frame and appends to the header block being accumulated.
     */
    private void processContinuationFrame(ProtoContext context, ProtoSndQueue<HttpObject> dst, int flags, int streamId, byte[] payload) {
        Http2Stream stream = streams.get(streamId);
        if (stream == null || stream.accumulatedHeaderBlock() == null) {
            throw new HttpProtocolException("HTTP/2: CONTINUATION frame without prior HEADERS on stream " + streamId);
        }

        ByteBuf headerBlock = stream.accumulatedHeaderBlock();
        headerBlock.writeBytes(payload, 0, payload.length);

        if (Http2Flags.endHeaders(flags)) {
            // All header block fragments received - decode
            headerBlock.markWriter();
            int readable = headerBlock.readableBytes();
            byte[] allHeaders = new byte[readable];
            headerBlock.getBytes(0, allHeaders, 0, readable);
            headerBlock.free();
            stream.accumulatedHeaderBlock(null);

            HttpHeaders headers = hpackDecoder.decode(allHeaders, 0, allHeaders.length);
            emitHttpMessage(dst, streamId, headers, false);
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
    private int lastEmittedStreamId = 0;

    /** Returns the stream ID of the last emitted HTTP message. Used by the encoder to set the response stream. */
    int getLastEmittedStreamId() {
        return this.lastEmittedStreamId;
    }

    private void emitHttpMessage(ProtoSndQueue<HttpObject> dst, int streamId, HttpHeaders headers, boolean endStream) {
        this.lastEmittedStreamId = streamId;
        String status = headers.get(":status");
        headers.remove(":status");

        if (status != null) {
            // This is a response
            String scheme = headers.get(":scheme");
            headers.remove(":scheme");
            headers.remove(":method");
            headers.remove(":path");
            headers.remove(":authority");

            int statusCode = Integer.parseInt(status);
            HttpStatus httpStatus = HttpStatus.valueOf(statusCode);
            DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_2_0, httpStatus, headers);
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

            if (method == null || path == null) {
                throw new HttpProtocolException("HTTP/2: missing required pseudo-header :method or :path");
            }

            // Map :authority to host header
            if (authority != null && headers.get("host") == null) {
                headers.add("host", authority);
            }

            HttpMethod httpMethod = HttpMethod.valueOf(method);
            DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_2_0, httpMethod, path, headers);
            dst.offerMessage(request);
        }

        if (endStream) {
            dst.offerMessage(DefaultLastHttpContent.EMPTY_LAST_CONTENT);
            Http2Stream stream = streams.get(streamId);
            if (stream != null) {
                stream.state(Http2StreamState.HALF_CLOSED_REMOTE);
            }
        }
    }

    /** Processes RST_STREAM frame - terminates a stream. */
    private void processRstStream(int streamId, byte[] payload) {
        if (payload.length != 4) {
            throw new HttpProtocolException("HTTP/2: RST_STREAM frame must be 4 bytes, got " + payload.length);
        }
        Http2Stream stream = streams.get(streamId);
        if (stream != null) {
            stream.state(Http2StreamState.CLOSED);
            stream.release();
        }
    }

    /** Processes SETTINGS frame - updates connection parameters. */
    private void processSettings(int flags, byte[] payload) {
        if (Http2Flags.ack(flags)) {
            // SETTINGS ACK - no payload expected
            return;
        }
        if (payload.length % 6 != 0) {
            throw new HttpProtocolException("HTTP/2: SETTINGS frame length must be a multiple of 6");
        }
        for (int i = 0; i < payload.length; i += 6) {
            int id = ((payload[i] & 0xFF) << 8) | (payload[i + 1] & 0xFF);
            long value = ((long) (payload[i + 2] & 0xFF) << 24) | ((payload[i + 3] & 0xFF) << 16) | ((payload[i + 4] & 0xFF) << 8) | (payload[i + 5] & 0xFF);
            remoteSettings.applySetting(id, value);
        }

        // Update HPACK decoder table size if changed
        hpackDecoder.setMaxHeaderTableSize((int) remoteSettings.headerTableSize());

        // Signal that a SETTINGS ACK should be sent back to the peer
        this.pendingSettingsAck = true;
    }

    /** Processes GOAWAY frame. */
    private void processGoaway(byte[] payload) {
        if (payload.length < 8) {
            throw new HttpProtocolException("HTTP/2: GOAWAY frame too short");
        }
        // int lastStreamId = ((payload[0] & 0x7F) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        // long errorCode = ((long)(payload[4] & 0xFF) << 24) | ((payload[5] & 0xFF) << 16) | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
        // Connection-level handling would close streams > lastStreamId
    }

    /** Processes WINDOW_UPDATE frame - adjusts flow control window. */
    private void processWindowUpdate(int streamId, byte[] payload) {
        if (payload.length != 4) {
            throw new HttpProtocolException("HTTP/2: WINDOW_UPDATE frame must be 4 bytes");
        }
        int increment = ((payload[0] & 0x7F) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        if (increment == 0) {
            throw new HttpProtocolException("HTTP/2: WINDOW_UPDATE increment must be non-zero");
        }
        if (streamId > 0) {
            Http2Stream stream = streams.get(streamId);
            if (stream != null) {
                stream.adjustSendWindowSize(increment);
            }
        }
        // Connection-level window update (streamId == 0) handled implicitly
    }

    // ========================= Package-private accessors for Http2ContextImpl =========================

    /**
     * Checks and consumes the pending SETTINGS ACK flag.
     * Returns true if a SETTINGS frame was received and an ACK should be sent.
     */
    boolean consumeSettingsAck() {
        if (this.pendingSettingsAck) {
            this.pendingSettingsAck = false;
            return true;
        }
        return false;
    }

    /** Creates a live {@link Http2Context} backed by this decoder's state. */
    public Http2Context createContext() {
        return new Http2ContextImpl(this);
    }

    /** Returns true if this is server mode. */
    boolean isServerMode() {
        return this.serverMode;
    }

    /** Returns true if the HTTP/2 connection preface has been received. */
    boolean isPrefaceReceived() {
        return this.prefaceReceived;
    }

    /** Returns the peer's (remote) HTTP/2 settings. */
    Http2Settings peerSettings() {
        return this.remoteSettings;
    }

    /** Returns the highest stream ID currently tracked. */
    int lastStreamId() {
        int max = 0;
        for (Integer id : this.streams.keySet()) {
            if (id > max)
                max = id;
        }
        return max;
    }

    @Override
    public void onClose(ProtoContext context) {
        if (accumulator != null) {
            accumulator.free();
            accumulator = null;
        }
        for (Http2Stream stream : streams.values()) {
            stream.release();
        }
        streams.clear();
    }
}
