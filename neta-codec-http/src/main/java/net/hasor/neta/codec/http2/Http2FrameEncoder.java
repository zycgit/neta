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
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;

/**
 * HTTP/2 frame encoder that converts standard {@link HttpObject} instances into
 * HTTP/2 binary frames.
 * <p>
 * This encoder takes the same {@link HttpObject} types used by HTTP/1.x
 * ({@link HttpRequest}, {@link HttpResponse}, {@link HttpContent}, {@link LastHttpContent})
 * and serializes them into HTTP/2 wire format with HPACK-compressed headers.
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
 *   ctx.addLastEncoder("h2-frames", new Http2FrameEncoder(true));
 * </pre>
 */
public class Http2FrameEncoder implements ProtoHandler<HttpObject, ByteBuf> {
    /** HTTP/2 connection preface sent by the client */
    private static final byte[] CLIENT_PREFACE    = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int    FRAME_HEADER_SIZE = 9;

    private final boolean       serverMode;
    private final HpackEncoder  hpackEncoder;
    private final AtomicInteger nextStreamId;

    private boolean prefaceSent;
    private int     currentStreamId = 0;

    /** Sets the stream ID to use for the next response encoding. Called by Http2ServerDuplexe. */
    void setResponseStreamId(int streamId) {
        this.currentStreamId = streamId;
    }

    /**
     * Creates a new HTTP/2 frame encoder with default HPACK settings.
     * @param serverMode true for server-side, false for client-side
     */
    public Http2FrameEncoder(boolean serverMode) {
        this(serverMode, 4096);
    }

    /**
     * Creates a new HTTP/2 frame encoder with custom HPACK settings.
     * @param serverMode true for server-side, false for client-side
     * @param maxHeaderTableSize maximum HPACK dynamic table size in bytes
     */
    public Http2FrameEncoder(boolean serverMode, int maxHeaderTableSize) {
        this.serverMode = serverMode;
        this.hpackEncoder = new HpackEncoder(maxHeaderTableSize);
        // Client uses odd stream IDs starting from 1, server uses even starting from 2
        this.nextStreamId = new AtomicInteger(serverMode ? 2 : 1);
        this.prefaceSent = serverMode; // Server doesn't send the connection preface
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
        // Send client preface if needed
        if (!prefaceSent) {
            ByteBuf prefaceBuf = context.byteBufAllocator().buffer(CLIENT_PREFACE.length);
            prefaceBuf.writeBytes(CLIENT_PREFACE, 0, CLIENT_PREFACE.length);
            prefaceBuf.markWriter();
            dst.offerMessage(prefaceBuf);

            // Also send initial SETTINGS frame
            writeSettingsFrame(context, dst);
            prefaceSent = true;
        }

        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            if (msg instanceof FullHttpResponse) {
                encodeFullResponse(context, (FullHttpResponse) msg, dst);
            } else if (msg instanceof FullHttpRequest) {
                encodeFullRequest(context, (FullHttpRequest) msg, dst);
            } else if (msg instanceof HttpResponse) {
                encodeResponseHeaders(context, (HttpResponse) msg, dst);
            } else if (msg instanceof HttpRequest) {
                encodeRequestHeaders(context, (HttpRequest) msg, dst);
            } else if (msg instanceof LastHttpContent) {
                encodeLastContent(context, (LastHttpContent) msg, dst);
            } else if (msg instanceof HttpContent) {
                encodeContent(context, (HttpContent) msg, dst);
            }
        }

        return ProtoStatus.Next;
    }

    /**
     * Encodes a complete HTTP response (headers + body) as HEADERS + DATA frames.
     */
    private void encodeFullResponse(ProtoContext context, FullHttpResponse response, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf body = response.content();
        boolean hasBody = body != null && body.readableBytes() > 0;

        // Encode pseudo-headers + regular headers directly into HPACK encoder
        // Avoids creating intermediate HttpHeaders object (LinkedHashMap allocation)
        hpackEncoder.beginEncode();
        hpackEncoder.encodeHeaderDirect(":status", String.valueOf(response.status().code()));
        encodeHeadersDirect(response.headers());

        int headerBlockLen = hpackEncoder.encodedLength();
        byte[] headerBlock = hpackEncoder.encodedBuffer();

        if (!hasBody) {
            // HEADERS frame with END_STREAM + END_HEADERS
            writeFrame(context, dst, Http2FrameType.HEADERS, Http2Flags.END_STREAM | Http2Flags.END_HEADERS, currentStreamId, headerBlock, 0, headerBlockLen);
        } else {
            // HEADERS frame with END_HEADERS (no END_STREAM)
            writeFrame(context, dst, Http2FrameType.HEADERS, Http2Flags.END_HEADERS, currentStreamId, headerBlock, 0, headerBlockLen);

            // DATA frame with END_STREAM - write body directly from source ByteBuf
            writeDataFrameDirect(context, dst, Http2Flags.END_STREAM, currentStreamId, body);
        }
    }

    /**
     * Encodes a complete HTTP request (headers + body) as HEADERS + DATA frames.
     */
    private void encodeFullRequest(ProtoContext context, FullHttpRequest request, ProtoSndQueue<ByteBuf> dst) {
        currentStreamId = nextStreamId.getAndAdd(2);

        ByteBuf body = request.content();
        boolean hasBody = body != null && body.readableBytes() > 0;

        // Encode pseudo-headers + regular headers directly into HPACK encoder
        hpackEncoder.beginEncode();
        hpackEncoder.encodeHeaderDirect(":method", request.method().name());
        hpackEncoder.encodeHeaderDirect(":path", request.uri());
        String host = request.headers().get("host");
        if (host != null) {
            hpackEncoder.encodeHeaderDirect(":authority", host);
        }
        hpackEncoder.encodeHeaderDirect(":scheme", "https");
        encodeHeadersDirect(request.headers());

        int headerBlockLen = hpackEncoder.encodedLength();
        byte[] headerBlock = hpackEncoder.encodedBuffer();

        if (!hasBody) {
            writeFrame(context, dst, Http2FrameType.HEADERS, Http2Flags.END_STREAM | Http2Flags.END_HEADERS, currentStreamId, headerBlock, 0, headerBlockLen);
        } else {
            writeFrame(context, dst, Http2FrameType.HEADERS, Http2Flags.END_HEADERS, currentStreamId, headerBlock, 0, headerBlockLen);

            // DATA frame with END_STREAM - write body directly from source ByteBuf
            writeDataFrameDirect(context, dst, Http2Flags.END_STREAM, currentStreamId, body);
        }
    }

    /**
     * Encodes HTTP response headers as a HEADERS frame.
     */
    private void encodeResponseHeaders(ProtoContext context, HttpResponse response, ProtoSndQueue<ByteBuf> dst) {
        hpackEncoder.beginEncode();
        hpackEncoder.encodeHeaderDirect(":status", String.valueOf(response.status().code()));
        encodeHeadersDirect(response.headers());

        int headerBlockLen = hpackEncoder.encodedLength();
        writeFrame(context, dst, Http2FrameType.HEADERS, Http2Flags.END_HEADERS, currentStreamId, hpackEncoder.encodedBuffer(), 0, headerBlockLen);
    }

    /**
     * Encodes HTTP request headers as a HEADERS frame.
     */
    private void encodeRequestHeaders(ProtoContext context, HttpRequest request, ProtoSndQueue<ByteBuf> dst) {
        currentStreamId = nextStreamId.getAndAdd(2);

        hpackEncoder.beginEncode();
        hpackEncoder.encodeHeaderDirect(":method", request.method().name());
        hpackEncoder.encodeHeaderDirect(":path", request.uri());
        String host = request.headers().get("host");
        if (host != null) {
            hpackEncoder.encodeHeaderDirect(":authority", host);
        }
        hpackEncoder.encodeHeaderDirect(":scheme", "https");
        encodeHeadersDirect(request.headers());

        int headerBlockLen = hpackEncoder.encodedLength();
        writeFrame(context, dst, Http2FrameType.HEADERS, Http2Flags.END_HEADERS, currentStreamId, hpackEncoder.encodedBuffer(), 0, headerBlockLen);
    }

    /**
     * Encodes body content as a DATA frame.
     */
    private void encodeContent(ProtoContext context, HttpContent content, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf body = content.content();
        if (body == null || body.readableBytes() == 0) {
            return;
        }
        writeDataFrameDirect(context, dst, Http2Flags.NONE, currentStreamId, body);
    }

    /**
     * Encodes the last content chunk as a DATA frame with END_STREAM.
     */
    private void encodeLastContent(ProtoContext context, LastHttpContent lastContent, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf body = lastContent.content();
        int bodyLen = (body != null) ? body.readableBytes() : 0;

        if (bodyLen > 0) {
            writeDataFrameDirect(context, dst, Http2Flags.END_STREAM, currentStreamId, body);
        } else {
            // Empty DATA frame with END_STREAM
            writeFrame(context, dst, Http2FrameType.DATA, Http2Flags.END_STREAM, currentStreamId, new byte[0], 0, 0);
        }
    }

    /**
     * Writes a SETTINGS frame with default values.
     */
    private void writeSettingsFrame(ProtoContext context, ProtoSndQueue<ByteBuf> dst) {
        writeFrame(context, dst, Http2FrameType.SETTINGS, Http2Flags.NONE, 0, new byte[0], 0, 0);
    }

    /**
     * Writes a single HTTP/2 frame to the output.
     * Writes the 9-byte frame header directly to ByteBuf using writeByte/writeInt24/writeInt32,
     * eliminating the intermediate byte[9] allocation.
     */
    private void writeFrame(ProtoContext context, ProtoSndQueue<ByteBuf> dst, int type, int flags, int streamId, byte[] payload, int offset, int length) {
        ByteBuf frame = context.byteBufAllocator().buffer(FRAME_HEADER_SIZE + length);

        // Write frame header (9 bytes) directly to ByteBuf
        frame.writeInt24(length);
        frame.writeByte((byte) type);
        frame.writeByte((byte) flags);
        frame.writeInt32(streamId & 0x7FFFFFFF);

        // Write payload
        if (length > 0) {
            frame.writeBytes(payload, offset, length);
        }

        frame.markWriter();
        dst.offerMessage(frame);
    }

    /**
     * Writes a DATA frame directly from a source ByteBuf, avoiding intermediate byte[] copy.
     * Reads body bytes from the source ByteBuf and writes them directly into the frame.
     */
    private void writeDataFrameDirect(ProtoContext context, ProtoSndQueue<ByteBuf> dst, int flags, int streamId, ByteBuf body) {
        int bodyLen = body.readableBytes();
        ByteBuf frame = context.byteBufAllocator().buffer(FRAME_HEADER_SIZE + bodyLen);

        // Write frame header directly
        frame.writeInt24(bodyLen);
        frame.writeByte((byte) Http2FrameType.DATA);
        frame.writeByte((byte) flags);
        frame.writeInt32(streamId & 0x7FFFFFFF);

        // Copy body data directly from source ByteBuf via getBytes
        if (bodyLen > 0) {
            byte[] bodyBytes = new byte[bodyLen];
            body.getBytes(0, bodyBytes, 0, bodyLen);
            frame.writeBytes(bodyBytes, 0, bodyLen);
        }

        frame.markWriter();
        dst.offerMessage(frame);
    }

    /**
     * Copies regular headers (non-pseudo, non-connection) directly into the HPACK encoder.
     * Avoids creating an intermediate HttpHeaders object.
     */
    private void encodeHeadersDirect(HttpHeaders source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<String, String> entry : source) {
            String name = entry.getKey().toLowerCase();
            // Skip HTTP/2 connection-specific headers (RFC 9113, Section 8.2.2)
            if ("connection".equals(name) || "transfer-encoding".equals(name) || "keep-alive".equals(name) || "proxy-connection".equals(name) || "upgrade".equals(name) || "host".equals(name)) {
                continue;
            }
            hpackEncoder.encodeHeaderDirect(name, entry.getValue());
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        // No resources to clean up
    }
}
