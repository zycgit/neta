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
import java.util.concurrent.atomic.AtomicLong;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.quic.QuicStreamChannel;
import net.hasor.neta.channel.quic.QuicVarInt;
import net.hasor.neta.codec.http.*;

/**
 * HTTP/3 frame encoder that converts standard {@link HttpObject} instances into
 * HTTP/3 frames for QUIC stream transmission.
 * <p>
 * This encoder takes the same {@link HttpObject} types used by HTTP/1.x and HTTP/2
 * ({@link HttpRequest}, {@link HttpResponse}, {@link HttpContent}, {@link LastHttpContent})
 * and serializes them into HTTP/3 wire format with QPACK-compressed headers.
 * <p>
 * Stream metadata (stream ID and FIN flag) is pushed to the {@link QuicChannel}'s
 * send metadata queue for each output frame, rather than being embedded in the data.
 * The write-path ({@code QuicAsyncConnectionChannel}) polls this metadata to determine
 * which QUIC stream to send on.
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
 *   ctx.addLast("h3", new Http3ServerDuplexe());
 *   ctx.addLastDecoder("aggregator", new HttpObjectAggregator(1048576));
 * </pre>
 */
public class Http3FrameEncoder implements ProtoHandler<HttpObject, ByteBuf> {
    private final QpackEncoder qpackEncoder;
    private final AtomicLong   nextStreamId;
    private final HttpScheme   scheme = HttpScheme.HTTPS;
    private       long         currentStreamId;
    private       long         responseStreamId;
    private       boolean      settingsSent;

    /**
     * Creates a new HTTP/3 frame encoder with default QPACK settings.
     * @param serverMode true for server-side, false for client-side
     */
    public Http3FrameEncoder(boolean serverMode) {
        this(serverMode, 4096);
    }

    /**
     * Creates a new HTTP/3 frame encoder with custom QPACK settings.
     * @param serverMode true for server-side, false for client-side
     * @param maxTableSize maximum QPACK dynamic table size in bytes
     */
    public Http3FrameEncoder(boolean serverMode, int maxTableSize) {
        this.qpackEncoder = new QpackEncoder(maxTableSize, false);
        // Client-initiated bidi streams: 0, 4, 8, ... Server-initiated: 1, 5, 9, ...
        this.nextStreamId = new AtomicLong(serverMode ? 1 : 0);
        this.currentStreamId = 0;
        this.responseStreamId = -1;
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
        qpackEncoder.encodeHeaderDirect(":scheme", scheme.name());
        String host = request.headers().get(HttpHeaderNames.HOST);
        if (StringUtils.isNotBlank(host)) {
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
            int bodyLen = body.readableBytes();
            byte[] bodyBytes = new byte[bodyLen];
            body.getBytes(0, bodyBytes, 0, bodyLen);

            SoChannel<?> ch = context.getChannel();
            if (ch instanceof QuicStreamChannel) {
                // QUIC: separate ByteBufs; FIN pushed via QuicStreamChannel
                writeHeadersStreamData(context, dst, currentStreamId, false, headerBlock, 0, headerBlockLen);
                writeDataStreamData(context, dst, currentStreamId, true, bodyBytes, 0, bodyLen);
            } else {
                // Non-QUIC (VrtChannel, testing): bundle all frames into one ByteBuf
                writeBundledFrames(context, dst, headerBlock, headerBlockLen, bodyBytes, bodyLen);
            }
        }
    }

    /**
     * Sets the stream ID for the next outgoing response.
     * In server mode, the duplexer polls from the decoder's response queue
     * and sets this before encoding a response.
     * @param streamId the QUIC stream ID to respond on
     */
    void setResponseStreamId(long streamId) {
        this.responseStreamId = streamId;
    }

    /** Encodes a complete HTTP response (headers + body). */
    private void encodeFullResponse(ProtoContext context, FullHttpResponse response, ProtoSndQueue<ByteBuf> dst) {
        if (this.responseStreamId >= 0) {
            this.currentStreamId = this.responseStreamId;
            this.responseStreamId = -1;
        }
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
            int bodyLen = body.readableBytes();
            byte[] bodyBytes = new byte[bodyLen];
            body.getBytes(0, bodyBytes, 0, bodyLen);

            SoChannel<?> ch = context.getChannel();
            if (ch instanceof QuicStreamChannel) {
                writeHeadersStreamData(context, dst, currentStreamId, false, headerBlock, 0, headerBlockLen);
                writeDataStreamData(context, dst, currentStreamId, true, bodyBytes, 0, bodyLen);
            } else {
                writeBundledFrames(context, dst, headerBlock, headerBlockLen, bodyBytes, bodyLen);
            }
        }
    }

    /** Encodes an HTTP request (headers only). */
    private void encodeRequest(ProtoContext context, HttpRequest request, ProtoSndQueue<ByteBuf> dst) {
        currentStreamId = nextStreamId.getAndAdd(4);

        qpackEncoder.beginEncode();
        qpackEncoder.encodeHeaderDirect(":method", request.method().name());
        qpackEncoder.encodeHeaderDirect(":path", request.uri());
        qpackEncoder.encodeHeaderDirect(":scheme", scheme.name());
        String host = request.headers().get(HttpHeaderNames.HOST);
        if (StringUtils.isNotBlank(host)) {
            qpackEncoder.encodeHeaderDirect(":authority", host);
        }
        encodeNonPseudoHeadersDirect(request.headers());

        int headerBlockLen = qpackEncoder.encodedLength();
        writeHeadersStreamData(context, dst, currentStreamId, false, qpackEncoder.encodedBuffer(), 0, headerBlockLen);
    }

    /** Encodes an HTTP response (headers only). */
    private void encodeResponse(ProtoContext context, HttpResponse response, ProtoSndQueue<ByteBuf> dst) {
        if (this.responseStreamId >= 0) {
            this.currentStreamId = this.responseStreamId;
            this.responseStreamId = -1;
        }
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

    /**
     * Bundles HEADERS + DATA frames into a single ByteBuf for non-QUIC channels.
     * This ensures VrtTransfer delivers all frames as one unit, allowing the decoder
     * to process them together with a single fin=true.
     */
    private void writeBundledFrames(ProtoContext context, ProtoSndQueue<ByteBuf> dst, byte[] headerBlock, int headerBlockLen, byte[] bodyData, int bodyLen) {
        byte[] vb = new byte[16];
        // HEADERS frame header
        int hTypeLen = QuicVarInt.encodeTo(vb, 0, Http3FrameType.HEADERS);
        int hLenLen = QuicVarInt.encodeTo(vb, hTypeLen, headerBlockLen);
        int headersFrameHeaderLen = hTypeLen + hLenLen;
        byte[] headersFrameHeader = new byte[headersFrameHeaderLen];
        System.arraycopy(vb, 0, headersFrameHeader, 0, headersFrameHeaderLen);

        // DATA frame header
        int dTypeLen = QuicVarInt.encodeTo(vb, 0, Http3FrameType.DATA);
        int dLenLen = QuicVarInt.encodeTo(vb, dTypeLen, bodyLen);
        int dataFrameHeaderLen = dTypeLen + dLenLen;

        int totalLen = headersFrameHeaderLen + headerBlockLen + dataFrameHeaderLen + bodyLen;
        ByteBuf output = context.byteBufAllocator().buffer(totalLen);
        output.writeBytes(headersFrameHeader, 0, headersFrameHeaderLen);
        output.writeBytes(headerBlock, 0, headerBlockLen);
        output.writeBytes(vb, 0, dataFrameHeaderLen);
        if (bodyLen > 0) {
            output.writeBytes(bodyData, 0, bodyLen);
        }
        output.markWriter();
        dst.offerMessage(output);
    }

    /** Reusable varint buffer (max 8 bytes per varint, 2 varints for type+length). */
    private final byte[] varintBuf = new byte[16];

    /**
     * Writes an HTTP/3 HEADERS frame. When {@code fin} is true, closes the
     * stream channel after writing to signal end-of-stream via QUIC FIN.
     */
    private void writeHeadersStreamData(ProtoContext context, ProtoSndQueue<ByteBuf> dst, long streamId, boolean fin, byte[] headerBlock, int offset, int length) {
        // Encode varint type and length into reusable buffer
        int typeLen = QuicVarInt.encodeTo(varintBuf, 0, Http3FrameType.HEADERS);
        int lenLen = QuicVarInt.encodeTo(varintBuf, typeLen, length);
        int frameHeaderLen = typeLen + lenLen;

        // Write HTTP/3 frame data only (no stream prefix)
        ByteBuf output = context.byteBufAllocator().buffer(frameHeaderLen + length);
        output.writeBytes(varintBuf, 0, frameHeaderLen);
        output.writeBytes(headerBlock, offset, length);
        output.markWriter();
        dst.offerMessage(output);

        // Close the stream channel to signal FIN on the QUIC layer
        if (fin) {
            SoChannel<?> ch = context.getChannel();
            if (ch instanceof QuicStreamChannel) {
                ch.close();
            }
        }
    }

    /**
     * Writes an HTTP/3 DATA frame. When {@code fin} is true, closes the
     * stream channel after writing to signal end-of-stream via QUIC FIN.
     */
    private void writeDataStreamData(ProtoContext context, ProtoSndQueue<ByteBuf> dst, long streamId, boolean fin, byte[] data, int offset, int length) {
        int typeLen = QuicVarInt.encodeTo(varintBuf, 0, Http3FrameType.DATA);
        int lenLen = QuicVarInt.encodeTo(varintBuf, typeLen, length);
        int frameHeaderLen = typeLen + lenLen;

        // Write HTTP/3 frame data only (no stream prefix)
        ByteBuf output = context.byteBufAllocator().buffer(frameHeaderLen + length);
        output.writeBytes(varintBuf, 0, frameHeaderLen);
        if (length > 0) {
            output.writeBytes(data, offset, length);
        }
        output.markWriter();
        dst.offerMessage(output);

        // Close the stream channel to signal FIN on the QUIC layer
        if (fin) {
            SoChannel<?> ch = context.getChannel();
            if (ch instanceof QuicStreamChannel) {
                ch.close();
            }
        }
    }

    /** Encodes non-pseudo headers directly into the QPACK encoder. */
    private void encodeNonPseudoHeadersDirect(HttpHeaders src) {
        for (java.util.Map.Entry<String, String> entry : src) {
            String name = entry.getKey();
            if (!StringUtils.startsWith(name, ":") && !StringUtils.equalsIgnoreCase(name, HttpHeaderNames.HOST)) {
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
