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
package net.hasor.neta.codec.spdy;

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
 * SPDY/3.1 frame decoder that converts binary frames into standard {@link HttpObject} instances.
 * <p>
 * SPDY frame format:
 * <pre>
 *   Data frame:
 *   +----------------------------------+
 *   |C| Stream-ID (31 bits)            |
 *   +----------------------------------+
 *   | Flags (8)  |  Length (24 bits)    |
 *   +----------------------------------+
 *   |             Data                 |
 *   +----------------------------------+
 *   Control frame:
 *   +----------------------------------+
 *   |C| Version (15 bits)| Type (16)   |
 *   +----------------------------------+
 *   | Flags (8)  |  Length (24 bits)    |
 *   +----------------------------------+
 *   |             Data                 |
 *   +----------------------------------+
 * </pre>
 * Where C=0 for data frames, C=1 for control frames.
 * <p>
 * <b>Design Principle:</b> All decoded messages are emitted as standard {@link HttpObject}
 * types ({@link HttpRequest}, {@link HttpResponse}, {@link HttpContent}, {@link LastHttpContent}),
 * so the application layer is protocol-agnostic.
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLastDecoder("spdy", new SpdyFrameDecoder(true));
 *   ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
 * </pre>
 */
public class SpdyFrameDecoder implements ProtoHandler<ByteBuf, HttpObject> {
    /** SPDY frame header size: 8 bytes */
    private static final int FRAME_HEADER_SIZE = 8;

    private final boolean              serverMode;
    private final Map<Integer, Object> streams; // track active streams
    private       ByteBuf              accumulator;

    /**
     * Creates a new SPDY frame decoder.
     * @param serverMode true for server-side, false for client-side
     */
    public SpdyFrameDecoder(boolean serverMode) {
        this.serverMode = serverMode;
        this.streams = new HashMap<>();
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        if (this.accumulator != null) {
            this.accumulator.free();
        }
        this.accumulator = ByteBufUtils.queueBuffer(src);

        while (accumulator.readableBytes() >= FRAME_HEADER_SIZE) {
            // Read first 4 bytes
            byte[] header = new byte[FRAME_HEADER_SIZE];
            accumulator.getBytes(0, header, 0, FRAME_HEADER_SIZE);

            boolean controlFrame = (header[0] & 0x80) != 0;
            int flags = header[4] & 0xFF;
            int length = ((header[5] & 0xFF) << 16) | ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);

            int totalSize = FRAME_HEADER_SIZE + length;
            if (accumulator.readableBytes() < totalSize) {
                break; // Need more data
            }

            accumulator.skipReadableBytes(FRAME_HEADER_SIZE);

            byte[] payload = new byte[length];
            if (length > 0) {
                accumulator.getBytes(0, payload, 0, length);
                accumulator.skipReadableBytes(length);
            }

            if (controlFrame) {
                int version = ((header[0] & 0x7F) << 8) | (header[1] & 0xFF);
                int type = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
                processControlFrame(context, dst, version, type, flags, payload);
            } else {
                int streamId = ((header[0] & 0x7F) << 24) | ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
                processDataFrame(context, dst, streamId, flags, payload);
            }
        }

        accumulator.markReader();
        return ProtoStatus.Next;
    }

    /**
     * Processes a SPDY data frame and emits HttpContent / LastHttpContent.
     */
    private void processDataFrame(ProtoContext context, ProtoSndQueue<HttpObject> dst, int streamId, int flags, byte[] payload) {
        boolean fin = SpdyFlags.fin(flags);

        if (payload.length > 0 || fin) {
            ByteBuf content = context.byteBufAllocator().buffer(Math.max(payload.length, 1));
            if (payload.length > 0) {
                content.writeBytes(payload, 0, payload.length);
            }
            content.markWriter();

            if (fin) {
                dst.offerMessage(new DefaultLastHttpContent(content));
                streams.remove(streamId);
            } else {
                dst.offerMessage(new DefaultHttpContent(content));
            }
        }
    }

    /**
     * Processes a SPDY control frame.
     */
    private void processControlFrame(ProtoContext context, ProtoSndQueue<HttpObject> dst, int version, int type, int flags, byte[] payload) {
        switch (type) {
            case SpdyFrameType.SYN_STREAM:
                processSynStream(dst, flags, payload);
                break;
            case SpdyFrameType.SYN_REPLY:
                processSynReply(dst, flags, payload);
                break;
            case SpdyFrameType.RST_STREAM:
                processRstStream(payload);
                break;
            case SpdyFrameType.SETTINGS:
                // Settings - connection-level configuration, handled at lower level
                break;
            case SpdyFrameType.PING:
                // Ping - liveness check, handled at connection level
                break;
            case SpdyFrameType.GOAWAY:
                // Goaway - connection shutdown, handled at connection level
                break;
            case SpdyFrameType.HEADERS:
                processSpdyHeaders(dst, flags, payload);
                break;
            case SpdyFrameType.WINDOW_UPDATE:
                // Window update - flow control, handled at connection level
                break;
            default:
                // Unknown control frame types - ignore
                break;
        }
    }

    /**
     * Processes SYN_STREAM frame - creates a new stream and emits HttpRequest.
     * <pre>
     *   +------------------------------------+
     *   |X|          Stream-ID (31bits)       |
     *   +------------------------------------+
     *   |X| Associated-To-Stream-ID (31bits)  |
     *   +------------------------------------+
     *   | Pri(3) | Unused(5) | Slot (8bits)   |
     *   +------------------------------------+
     *   |          Name/Value header block    |
     *   +------------------------------------+
     * </pre>
     */
    private void processSynStream(ProtoSndQueue<HttpObject> dst, int flags, byte[] payload) {
        if (payload.length < 10) {
            throw new HttpProtocolException("SPDY: SYN_STREAM frame too short");
        }

        int streamId = ((payload[0] & 0x7F) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        // int associatedStreamId = ((payload[4] & 0x7F) << 24) | ((payload[5] & 0xFF) << 16) | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
        // int priority = (payload[8] & 0xFF) >>> 5;

        streams.put(streamId, Boolean.TRUE);

        // Decode header block (starts at offset 10)
        HttpHeaders headers = SpdyHeaderBlockCodec.decode(payload, 10, payload.length - 10);

        boolean fin = SpdyFlags.fin(flags);

        // Extract SPDY pseudo-headers
        String method = headers.get(":method");
        String path = headers.get(":path");
        String spdyVersion = headers.get(":version");
        String host = headers.get(":host");
        String scheme = headers.get(":scheme");

        headers.remove(":method");
        headers.remove(":path");
        headers.remove(":version");
        headers.remove(":host");
        headers.remove(":scheme");

        if (method != null && path != null) {
            // SYN_STREAM from client → HttpRequest
            if (host != null && headers.get("host") == null) {
                headers.add("host", host);
            }
            HttpMethod httpMethod = HttpMethod.valueOf(method);
            DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.SPDY_3_1, httpMethod, path, headers);
            dst.offerMessage(request);
        } else {
            // Incomplete - could be a server push
            String status = headers.get(":status");
            headers.remove(":status");
            if (status != null) {
                int statusCode = Integer.parseInt(status);
                HttpStatus httpStatus = HttpStatus.valueOf(statusCode);
                DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.SPDY_3_1, httpStatus, headers);
                dst.offerMessage(response);
            }
        }

        if (fin) {
            dst.offerMessage(DefaultLastHttpContent.EMPTY_LAST_CONTENT);
            streams.remove(streamId);
        }
    }

    /**
     * Processes SYN_REPLY frame - response headers for a stream.
     * <pre>
     *   +------------------------------------+
     *   |X|          Stream-ID (31bits)       |
     *   +------------------------------------+
     *   |          Name/Value header block    |
     *   +------------------------------------+
     * </pre>
     */
    private void processSynReply(ProtoSndQueue<HttpObject> dst, int flags, byte[] payload) {
        if (payload.length < 4) {
            throw new HttpProtocolException("SPDY: SYN_REPLY frame too short");
        }

        int streamId = ((payload[0] & 0x7F) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);

        // Decode header block (starts at offset 4)
        HttpHeaders headers = SpdyHeaderBlockCodec.decode(payload, 4, payload.length - 4);

        boolean fin = SpdyFlags.fin(flags);

        // Extract status
        String status = headers.get(":status");
        String spdyVersion = headers.get(":version");
        headers.remove(":status");
        headers.remove(":version");

        if (status != null) {
            // Parse status: "200 OK" or just "200"
            int spaceIdx = status.indexOf(' ');
            int statusCode = Integer.parseInt(spaceIdx > 0 ? status.substring(0, spaceIdx) : status);
            HttpStatus httpStatus = HttpStatus.valueOf(statusCode);
            DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.SPDY_3_1, httpStatus, headers);
            dst.offerMessage(response);
        }

        if (fin) {
            dst.offerMessage(DefaultLastHttpContent.EMPTY_LAST_CONTENT);
            streams.remove(streamId);
        }
    }

    /**
     * Processes HEADERS frame - additional headers for an existing stream.
     */
    private void processSpdyHeaders(ProtoSndQueue<HttpObject> dst, int flags, byte[] payload) {
        if (payload.length < 4) {
            return;
        }
        // int streamId = ((payload[0] & 0x7F) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        // Additional headers for existing stream - these are trailing headers in HTTP terms
        if (SpdyFlags.fin(flags)) {
            HttpHeaders trailers = SpdyHeaderBlockCodec.decode(payload, 4, payload.length - 4);
            // Emit as trailing headers would require a more complex mapping
            dst.offerMessage(DefaultLastHttpContent.EMPTY_LAST_CONTENT);
        }
    }

    /** Processes RST_STREAM frame. */
    private void processRstStream(byte[] payload) {
        if (payload.length >= 8) {
            int streamId = ((payload[0] & 0x7F) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
            streams.remove(streamId);
        }
    }

    // ========================= Package-private accessors for SpdyContextImpl =========================

    /** Creates a live {@link SpdyContext} backed by this decoder's state. */
    public SpdyContext createContext() {
        return new SpdyContextImpl(this);
    }

    /** Returns true if this is server mode. */
    boolean isServerMode() {
        return this.serverMode;
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
        streams.clear();
    }
}
