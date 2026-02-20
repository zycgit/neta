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

        HttpHeaders pseudoHeaders = new HttpHeaders();
        pseudoHeaders.add(":method", request.method().name());
        pseudoHeaders.add(":path", request.uri());
        pseudoHeaders.add(":scheme", "https");
        String host = request.headers().get("host");
        if (host != null) {
            pseudoHeaders.add(":authority", host);
        }
        copyNonPseudoHeaders(request.headers(), pseudoHeaders);

        byte[] headerBlock = qpackEncoder.encode(pseudoHeaders);

        ByteBuf body = request.content();
        boolean hasBody = body != null && body.readableBytes() > 0;

        if (!hasBody) {
            writeStreamData(context, dst, currentStreamId, true, buildHeadersFrame(headerBlock));
        } else {
            writeStreamData(context, dst, currentStreamId, false, buildHeadersFrame(headerBlock));

            int bodyLen = body.readableBytes();
            byte[] bodyBytes = new byte[bodyLen];
            body.getBytes(0, bodyBytes, 0, bodyLen);
            writeStreamData(context, dst, currentStreamId, true, buildDataFrame(bodyBytes, 0, bodyLen));
        }
    }

    /** Encodes a complete HTTP response (headers + body). */
    private void encodeFullResponse(ProtoContext context, FullHttpResponse response, ProtoSndQueue<ByteBuf> dst) {
        HttpHeaders pseudoHeaders = new HttpHeaders();
        pseudoHeaders.add(":status", String.valueOf(response.status().code()));
        copyNonPseudoHeaders(response.headers(), pseudoHeaders);

        byte[] headerBlock = qpackEncoder.encode(pseudoHeaders);

        ByteBuf body = response.content();
        boolean hasBody = body != null && body.readableBytes() > 0;

        if (!hasBody) {
            writeStreamData(context, dst, currentStreamId, true, buildHeadersFrame(headerBlock));
        } else {
            writeStreamData(context, dst, currentStreamId, false, buildHeadersFrame(headerBlock));

            int bodyLen = body.readableBytes();
            byte[] bodyBytes = new byte[bodyLen];
            body.getBytes(0, bodyBytes, 0, bodyLen);
            writeStreamData(context, dst, currentStreamId, true, buildDataFrame(bodyBytes, 0, bodyLen));
        }
    }

    /** Encodes an HTTP request (headers only). */
    private void encodeRequest(ProtoContext context, HttpRequest request, ProtoSndQueue<ByteBuf> dst) {
        currentStreamId = nextStreamId.getAndAdd(4);

        HttpHeaders pseudoHeaders = new HttpHeaders();
        pseudoHeaders.add(":method", request.method().name());
        pseudoHeaders.add(":path", request.uri());
        pseudoHeaders.add(":scheme", "https");
        String host = request.headers().get("host");
        if (host != null) {
            pseudoHeaders.add(":authority", host);
        }
        copyNonPseudoHeaders(request.headers(), pseudoHeaders);

        byte[] headerBlock = qpackEncoder.encode(pseudoHeaders);
        writeStreamData(context, dst, currentStreamId, false, buildHeadersFrame(headerBlock));
    }

    /** Encodes an HTTP response (headers only). */
    private void encodeResponse(ProtoContext context, HttpResponse response, ProtoSndQueue<ByteBuf> dst) {
        HttpHeaders pseudoHeaders = new HttpHeaders();
        pseudoHeaders.add(":status", String.valueOf(response.status().code()));
        copyNonPseudoHeaders(response.headers(), pseudoHeaders);

        byte[] headerBlock = qpackEncoder.encode(pseudoHeaders);
        writeStreamData(context, dst, currentStreamId, false, buildHeadersFrame(headerBlock));
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
        writeStreamData(context, dst, currentStreamId, false, buildDataFrame(bodyBytes, 0, bodyLen));
    }

    /** Encodes last content as an HTTP/3 DATA frame with FIN. */
    private void encodeLastContent(ProtoContext context, LastHttpContent content, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf body = content.content();
        int bodyLen = (body != null) ? body.readableBytes() : 0;

        if (bodyLen > 0) {
            byte[] bodyBytes = new byte[bodyLen];
            body.getBytes(0, bodyBytes, 0, bodyLen);
            writeStreamData(context, dst, currentStreamId, true, buildDataFrame(bodyBytes, 0, bodyLen));
        } else {
            // Send empty DATA frame with FIN
            writeStreamData(context, dst, currentStreamId, true, buildDataFrame(new byte[0], 0, 0));
        }
    }

    /**
     * Builds an HTTP/3 HEADERS frame.
     * <pre>
     *   HEADERS Frame {
     *     Type (i) = 0x01,
     *     Length (i),
     *     Encoded Field Section (..),
     *   }
     * </pre>
     */
    private byte[] buildHeadersFrame(byte[] headerBlock) {
        byte[] typeBytes = QuicVarInt.encode(Http3FrameType.HEADERS);
        byte[] lenBytes = QuicVarInt.encode(headerBlock.length);
        byte[] frame = new byte[typeBytes.length + lenBytes.length + headerBlock.length];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(lenBytes, 0, frame, pos, lenBytes.length);
        pos += lenBytes.length;
        System.arraycopy(headerBlock, 0, frame, pos, headerBlock.length);
        return frame;
    }

    /**
     * Builds an HTTP/3 DATA frame.
     * <pre>
     *   DATA Frame {
     *     Type (i) = 0x00,
     *     Length (i),
     *     Data (..),
     *   }
     * </pre>
     */
    private byte[] buildDataFrame(byte[] data, int offset, int length) {
        byte[] typeBytes = QuicVarInt.encode(Http3FrameType.DATA);
        byte[] lenBytes = QuicVarInt.encode(length);
        byte[] frame = new byte[typeBytes.length + lenBytes.length + length];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(lenBytes, 0, frame, pos, lenBytes.length);
        pos += lenBytes.length;
        if (length > 0) {
            System.arraycopy(data, offset, frame, pos, length);
        }
        return frame;
    }

    /**
     * Wraps HTTP/3 frame data with QUIC stream metadata header and writes to the output.
     * Format: streamId(8) + fin(1) + data
     */
    private void writeStreamData(ProtoContext context, ProtoSndQueue<ByteBuf> dst, long streamId, boolean fin, byte[] frameData) {
        ByteBuf output = context.byteBufAllocator().buffer(9 + frameData.length);
        byte[] streamIdBytes = new byte[8];
        long sid = streamId;
        for (int i = 7; i >= 0; i--) {
            streamIdBytes[i] = (byte) (sid & 0xFF);
            sid >>>= 8;
        }
        output.writeBytes(streamIdBytes, 0, 8);
        output.writeBytes(new byte[] { (byte) (fin ? 1 : 0) }, 0, 1);
        output.writeBytes(frameData, 0, frameData.length);
        output.markWriter();
        dst.offerMessage(output);
    }

    /** Copies non-pseudo headers from source to destination. */
    private void copyNonPseudoHeaders(HttpHeaders src, HttpHeaders dst) {
        for (String name : src.names()) {
            if (!name.startsWith(":") && !name.equalsIgnoreCase("host")) {
                for (String value : src.getAll(name)) {
                    dst.add(name.toLowerCase(), value);
                }
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
