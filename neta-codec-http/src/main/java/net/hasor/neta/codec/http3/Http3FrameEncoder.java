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
package net.hasor.neta.codec.http3;

import java.util.concurrent.atomic.AtomicLong;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.quic.QuicVarInt;

/**
 * HTTP/3 frame encoder that converts standard {@link HttpObject} instances into
 * HTTP/3 frames wrapped in QUIC stream data format.
 * <p>
 * This encoder takes the same {@link HttpObject} types used by HTTP/1.x and HTTP/2
 * ({@link HttpRequest}, {@link HttpResponse}, {@link HttpContent}, {@link LastHttpContent})
 * and serializes them into HTTP/3 wire format with QPACK-compressed headers.
 * <p>
 * Output ByteBuf format (for QuicFrameEncoder):
 * <pre>
 *   streamId (8 bytes) + fin (1 byte) + HTTP/3 frame data (...)
 * </pre>
 * <p>
 * HTTP/3 Frame format (RFC 9114, Section 7.1):
 * <pre>
 *   HTTP/3 Frame {
 *     Type (i),
 *     Length (i),
 *     Frame Payload (..),
 *   }
 * </pre>
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLastEncoder("h3", new Http3FrameEncoder(true));
 *   ctx.addLastEncoder("quic", new QuicFrameEncoder(true));
 * </pre>
 */
public class Http3FrameEncoder implements ProtoHandler<HttpObject, ByteBuf> {
    private final boolean      serverMode;
    private final QpackEncoder qpackEncoder;
    private final AtomicLong   nextStreamId;
    private       long         currentStreamId;
    private       boolean      settingsSent;

    /**
     * Creates a new HTTP/3 frame encoder.
     * @param serverMode true for server-side, false for client-side
     */
    public Http3FrameEncoder(boolean serverMode) {
        this.serverMode = serverMode;
        this.qpackEncoder = new QpackEncoder();
        // Client-initiated bidi streams: 0, 4, 8, ... Server-initiated: 1, 5, 9, ...
        this.nextStreamId = new AtomicLong(serverMode ? 1 : 0);
        this.currentStreamId = 0;
        this.settingsSent = false;
    }

    @Override
    public void onActive(ProtoContext context) throws Throwable {
        // Send initial SETTINGS on the control stream (unidirectional stream type 0x00)
        if (!settingsSent) {
            sendSettings(context);
            settingsSent = true;
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
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
                encodeResponse(context, (HttpResponse) msg, dst);
            } else if (msg instanceof HttpRequest) {
                encodeRequest(context, (HttpRequest) msg, dst);
            } else if (msg instanceof LastHttpContent) {
                encodeLastContent(context, (LastHttpContent) msg, dst);
            } else if (msg instanceof HttpContent) {
                encodeContent(context, (HttpContent) msg, dst);
            }
        }

        return ProtoStatus.Next;
    }

    /** Encodes a complete HTTP request (headers + body) as HTTP/3 HEADERS + DATA frames. */
    private void encodeFullRequest(ProtoContext context, FullHttpRequest request, ProtoSndQueue<ByteBuf> dst) {
        currentStreamId = nextStreamId.getAndAdd(4);

        // Encode headers directly into QPACK encoder, avoiding intermediate HttpHeaders allocation
        qpackEncoder.beginEncode();
        qpackEncoder.encodeHeaderDirect(":method", request.method().name());
        qpackEncoder.encodeHeaderDirect(":path", request.uri());
        qpackEncoder.encodeHeaderDirect(":scheme", "https");
        String host = request.headers().get("host");
        if (host != null) {
            qpackEncoder.encodeHeaderDirect(":authority", host);
        }
        encodeNonPseudoHeadersDirect(request.headers());

        int headerBlockLen = qpackEncoder.encodedLength();
        byte[] headerBlock = qpackEncoder.encodedBuffer();

        ByteBuf body = request.content();
        boolean hasBody = body != null && body.readableBytes() > 0;

        if (!hasBody) {
            writeHeadersStreamData(context, dst, currentStreamId, true, headerBlock, 0, headerBlockLen);
        } else {
            writeHeadersStreamData(context, dst, currentStreamId, false, headerBlock, 0, headerBlockLen);

            int bodyLen = body.readableBytes();
            byte[] bodyBytes = new byte[bodyLen];
            body.getBytes(0, bodyBytes, 0, bodyLen);
            writeDataStreamData(context, dst, currentStreamId, true, bodyBytes, 0, bodyLen);
        }
    }

    /** Encodes a complete HTTP response (headers + body). */
    private void encodeFullResponse(ProtoContext context, FullHttpResponse response, ProtoSndQueue<ByteBuf> dst) {
        qpackEncoder.beginEncode();
        qpackEncoder.encodeHeaderDirect(":status", String.valueOf(response.status().code()));
        encodeNonPseudoHeadersDirect(response.headers());

        int headerBlockLen = qpackEncoder.encodedLength();
        byte[] headerBlock = qpackEncoder.encodedBuffer();

        ByteBuf body = response.content();
        boolean hasBody = body != null && body.readableBytes() > 0;

        if (!hasBody) {
            writeHeadersStreamData(context, dst, currentStreamId, true, headerBlock, 0, headerBlockLen);
        } else {
            writeHeadersStreamData(context, dst, currentStreamId, false, headerBlock, 0, headerBlockLen);

            int bodyLen = body.readableBytes();
            byte[] bodyBytes = new byte[bodyLen];
            body.getBytes(0, bodyBytes, 0, bodyLen);
            writeDataStreamData(context, dst, currentStreamId, true, bodyBytes, 0, bodyLen);
        }
    }

    /** Encodes an HTTP request (headers only). */
    private void encodeRequest(ProtoContext context, HttpRequest request, ProtoSndQueue<ByteBuf> dst) {
        currentStreamId = nextStreamId.getAndAdd(4);

        qpackEncoder.beginEncode();
        qpackEncoder.encodeHeaderDirect(":method", request.method().name());
        qpackEncoder.encodeHeaderDirect(":path", request.uri());
        qpackEncoder.encodeHeaderDirect(":scheme", "https");
        String host = request.headers().get("host");
        if (host != null) {
            qpackEncoder.encodeHeaderDirect(":authority", host);
        }
        encodeNonPseudoHeadersDirect(request.headers());

        int headerBlockLen = qpackEncoder.encodedLength();
        writeHeadersStreamData(context, dst, currentStreamId, false, qpackEncoder.encodedBuffer(), 0, headerBlockLen);
    }

    /** Encodes an HTTP response (headers only). */
    private void encodeResponse(ProtoContext context, HttpResponse response, ProtoSndQueue<ByteBuf> dst) {
        qpackEncoder.beginEncode();
        qpackEncoder.encodeHeaderDirect(":status", String.valueOf(response.status().code()));
        encodeNonPseudoHeadersDirect(response.headers());

        int headerBlockLen = qpackEncoder.encodedLength();
        writeHeadersStreamData(context, dst, currentStreamId, false, qpackEncoder.encodedBuffer(), 0, headerBlockLen);
    }

    /** Encodes body content as an HTTP/3 DATA frame. */
    private void encodeContent(ProtoContext context, HttpContent content, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf body = content.content();
        if (body == null || body.readableBytes() == 0) {
            return;
        }
        int bodyLen = body.readableBytes();
        byte[] bodyBytes = new byte[bodyLen];
        body.getBytes(0, bodyBytes, 0, bodyLen);
        writeDataStreamData(context, dst, currentStreamId, false, bodyBytes, 0, bodyLen);
    }

    /** Encodes last content as an HTTP/3 DATA frame with FIN. */
    private void encodeLastContent(ProtoContext context, LastHttpContent content, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf body = content.content();
        int bodyLen = (body != null) ? body.readableBytes() : 0;

        if (bodyLen > 0) {
            byte[] bodyBytes = new byte[bodyLen];
            body.getBytes(0, bodyBytes, 0, bodyLen);
            writeDataStreamData(context, dst, currentStreamId, true, bodyBytes, 0, bodyLen);
        } else {
            writeDataStreamData(context, dst, currentStreamId, true, new byte[0], 0, 0);
        }
    }

    /** Reusable varint buffer (max 8 bytes per varint, 2 varints for type+length). */
    private final byte[] varintBuf   = new byte[16];
    /** Reusable stream ID buffer. */
    private final byte[] streamIdBuf = new byte[8];

    /**
     * Writes an HTTP/3 HEADERS frame wrapped in stream metadata directly to output.
     * Avoids intermediate byte[] allocations from buildHeadersFrame + writeStreamData.
     */
    private void writeHeadersStreamData(ProtoContext context, ProtoSndQueue<ByteBuf> dst, long streamId, boolean fin, byte[] headerBlock, int offset, int length) {
        // Encode varint type and length into reusable buffer
        int typeLen = QuicVarInt.encodeTo(varintBuf, 0, Http3FrameType.HEADERS);
        int lenLen = QuicVarInt.encodeTo(varintBuf, typeLen, length);
        int frameHeaderLen = typeLen + lenLen;

        // Write directly to ByteBuf: streamId(8) + fin(1) + varint_type + varint_len + header_block
        ByteBuf output = context.byteBufAllocator().buffer(9 + frameHeaderLen + length);
        writeStreamId(output, streamId);
        output.writeByte((byte) (fin ? 1 : 0));
        output.writeBytes(varintBuf, 0, frameHeaderLen);
        output.writeBytes(headerBlock, offset, length);
        output.markWriter();
        dst.offerMessage(output);
    }

    /**
     * Writes an HTTP/3 DATA frame wrapped in stream metadata directly to output.
     */
    private void writeDataStreamData(ProtoContext context, ProtoSndQueue<ByteBuf> dst, long streamId, boolean fin, byte[] data, int offset, int length) {
        int typeLen = QuicVarInt.encodeTo(varintBuf, 0, Http3FrameType.DATA);
        int lenLen = QuicVarInt.encodeTo(varintBuf, typeLen, length);
        int frameHeaderLen = typeLen + lenLen;

        ByteBuf output = context.byteBufAllocator().buffer(9 + frameHeaderLen + length);
        writeStreamId(output, streamId);
        output.writeByte((byte) (fin ? 1 : 0));
        output.writeBytes(varintBuf, 0, frameHeaderLen);
        if (length > 0) {
            output.writeBytes(data, offset, length);
        }
        output.markWriter();
        dst.offerMessage(output);
    }

    /**
     * Writes stream ID (8 bytes big-endian) directly to ByteBuf.
     */
    private void writeStreamId(ByteBuf output, long streamId) {
        long sid = streamId;
        for (int i = 7; i >= 0; i--) {
            streamIdBuf[i] = (byte) (sid & 0xFF);
            sid >>>= 8;
        }
        output.writeBytes(streamIdBuf, 0, 8);
    }

    /** Encodes non-pseudo headers directly into the QPACK encoder. */
    private void encodeNonPseudoHeadersDirect(HttpHeaders src) {
        for (java.util.Map.Entry<String, String> entry : src) {
            String name = entry.getKey();
            if (!name.startsWith(":") && !name.equalsIgnoreCase("host")) {
                qpackEncoder.encodeHeaderDirect(name.toLowerCase(), entry.getValue());
            }
        }
    }

    /**
     * Sends initial SETTINGS frame on a control stream.
     */
    private void sendSettings(ProtoContext context) {
        // In HTTP/3, SETTINGS are sent on a unidirectional control stream.
        // The control stream creation and SETTINGS frame would be handled
        // at connection setup time. For codec-level implementation, this
        // is a no-op as the settings management is handled by the duplexer.
    }
}
