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

import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;

/**
 * SPDY/3.1 frame encoder that converts standard {@link HttpObject} instances into
 * SPDY binary frames.
 * <p>
 * This encoder takes the same {@link HttpObject} types used by HTTP/1.x and converts
 * them into SPDY/3.1 wire format:
 * <ul>
 *   <li>{@link HttpRequest} → SYN_STREAM control frame</li>
 *   <li>{@link HttpResponse} → SYN_REPLY control frame</li>
 *   <li>{@link HttpContent} → DATA frame</li>
 *   <li>{@link LastHttpContent} → DATA frame with FLAG_FIN</li>
 * </ul>
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLastEncoder("spdy", new SpdyFrameEncoder(true));
 * </pre>
 */
public class SpdyFrameEncoder implements ProtoHandler<HttpObject, ByteBuf> {
    private static final int FRAME_HEADER_SIZE = 8;
    private static final int SPDY_VERSION      = 3;

    private final boolean       serverMode;
    private final AtomicInteger nextStreamId;
    private       int           currentStreamId = 0;

    /**
     * Creates a new SPDY frame encoder.
     * @param serverMode true for server-side, false for client-side
     */
    public SpdyFrameEncoder(boolean serverMode) {
        this.serverMode = serverMode;
        // Server uses even stream IDs, client uses odd
        this.nextStreamId = new AtomicInteger(serverMode ? 2 : 1);
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

    /**
     * Encodes a complete HTTP response as SYN_REPLY + DATA frames.
     */
    private void encodeFullResponse(ProtoContext context, FullHttpResponse response, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf body = response.content();
        boolean hasBody = body != null && body.readableBytes() > 0;

        // Build SPDY headers
        HttpHeaders h = new HttpHeaders();
        h.add(":status", response.status().code() + " " + response.status().reasonPhrase());
        h.add(":version", "HTTP/1.1"); // SPDY maps to HTTP/1.1 semantics
        copyHeaders(response.headers(), h);

        byte[] headerBlock = SpdyHeaderBlockCodec.encode(h);

        if (!hasBody) {
            // SYN_REPLY with FLAG_FIN
            writeSynReply(context, dst, currentStreamId, SpdyFlags.FLAG_FIN, headerBlock);
        } else {
            // SYN_REPLY (no FIN) + DATA with FIN
            writeSynReply(context, dst, currentStreamId, SpdyFlags.NONE, headerBlock);

            int bodyLen = body.readableBytes();
            byte[] bodyBytes = new byte[bodyLen];
            body.getBytes(0, bodyBytes, 0, bodyLen);
            writeDataFrame(context, dst, currentStreamId, SpdyFlags.FLAG_FIN, bodyBytes, 0, bodyLen);
        }
    }

    /**
     * Encodes a complete HTTP request as SYN_STREAM + DATA frames.
     */
    private void encodeFullRequest(ProtoContext context, FullHttpRequest request, ProtoSndQueue<ByteBuf> dst) {
        currentStreamId = nextStreamId.getAndAdd(2);

        ByteBuf body = request.content();
        boolean hasBody = body != null && body.readableBytes() > 0;

        HttpHeaders h = new HttpHeaders();
        h.add(":method", request.method().name());
        h.add(":path", request.uri());
        h.add(":version", "HTTP/1.1");
        String host = request.headers().get("host");
        if (host != null) {
            h.add(":host", host);
        }
        h.add(":scheme", "https");
        copyHeaders(request.headers(), h);

        byte[] headerBlock = SpdyHeaderBlockCodec.encode(h);

        if (!hasBody) {
            writeSynStream(context, dst, currentStreamId, SpdyFlags.FLAG_FIN, headerBlock);
        } else {
            writeSynStream(context, dst, currentStreamId, SpdyFlags.NONE, headerBlock);

            int bodyLen = body.readableBytes();
            byte[] bodyBytes = new byte[bodyLen];
            body.getBytes(0, bodyBytes, 0, bodyLen);
            writeDataFrame(context, dst, currentStreamId, SpdyFlags.FLAG_FIN, bodyBytes, 0, bodyLen);
        }
    }

    /** Encodes HTTP response headers as SYN_REPLY. */
    private void encodeResponse(ProtoContext context, HttpResponse response, ProtoSndQueue<ByteBuf> dst) {
        HttpHeaders h = new HttpHeaders();
        h.add(":status", response.status().code() + " " + response.status().reasonPhrase());
        h.add(":version", "HTTP/1.1");
        copyHeaders(response.headers(), h);

        byte[] headerBlock = SpdyHeaderBlockCodec.encode(h);
        writeSynReply(context, dst, currentStreamId, SpdyFlags.NONE, headerBlock);
    }

    /** Encodes HTTP request headers as SYN_STREAM. */
    private void encodeRequest(ProtoContext context, HttpRequest request, ProtoSndQueue<ByteBuf> dst) {
        currentStreamId = nextStreamId.getAndAdd(2);

        HttpHeaders h = new HttpHeaders();
        h.add(":method", request.method().name());
        h.add(":path", request.uri());
        h.add(":version", "HTTP/1.1");
        String host = request.headers().get("host");
        if (host != null) {
            h.add(":host", host);
        }
        h.add(":scheme", "https");
        copyHeaders(request.headers(), h);

        byte[] headerBlock = SpdyHeaderBlockCodec.encode(h);
        writeSynStream(context, dst, currentStreamId, SpdyFlags.NONE, headerBlock);
    }

    /** Encodes body content as DATA frame. */
    private void encodeContent(ProtoContext context, HttpContent content, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf body = content.content();
        if (body == null || body.readableBytes() == 0) {
            return;
        }
        int bodyLen = body.readableBytes();
        byte[] bodyBytes = new byte[bodyLen];
        body.getBytes(0, bodyBytes, 0, bodyLen);
        writeDataFrame(context, dst, currentStreamId, SpdyFlags.NONE, bodyBytes, 0, bodyLen);
    }

    /** Encodes last content as DATA frame with FLAG_FIN. */
    private void encodeLastContent(ProtoContext context, LastHttpContent lastContent, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf body = lastContent.content();
        int bodyLen = (body != null) ? body.readableBytes() : 0;

        if (bodyLen > 0) {
            byte[] bodyBytes = new byte[bodyLen];
            body.getBytes(0, bodyBytes, 0, bodyLen);
            writeDataFrame(context, dst, currentStreamId, SpdyFlags.FLAG_FIN, bodyBytes, 0, bodyLen);
        } else {
            writeDataFrame(context, dst, currentStreamId, SpdyFlags.FLAG_FIN, new byte[0], 0, 0);
        }
    }

    /**
     * Writes a SYN_STREAM control frame.
     * Writes directly to ByteBuf, avoiding intermediate byte[] allocation.
     */
    private void writeSynStream(ProtoContext context, ProtoSndQueue<ByteBuf> dst, int streamId, int flags, byte[] headerBlock) {
        int payloadLen = 10 + headerBlock.length; // 4+4+2 = 10 bytes of SYN_STREAM-specific fields
        ByteBuf frame = context.byteBufAllocator().buffer(FRAME_HEADER_SIZE + payloadLen);

        // Control frame header - write directly to ByteBuf
        frame.writeByte((byte) (0x80 | ((SPDY_VERSION >>> 8) & 0x7F)));
        frame.writeByte((byte) (SPDY_VERSION & 0xFF));
        frame.writeByte((byte) ((SpdyFrameType.SYN_STREAM >>> 8) & 0xFF));
        frame.writeByte((byte) (SpdyFrameType.SYN_STREAM & 0xFF));
        frame.writeByte((byte) flags);
        frame.writeInt24(payloadLen);

        // Stream ID
        frame.writeInt32(streamId & 0x7FFFFFFF);
        // Associated-To-Stream-ID (0)
        frame.writeInt32(0);
        // Priority (3 bits) + unused (5 bits) + slot (8 bits)
        frame.writeByte((byte) 0);
        frame.writeByte((byte) 0);

        // Header block
        frame.writeBytes(headerBlock, 0, headerBlock.length);
        frame.markWriter();
        dst.offerMessage(frame);
    }

    /**
     * Writes a SYN_REPLY control frame.
     * Writes directly to ByteBuf.
     */
    private void writeSynReply(ProtoContext context, ProtoSndQueue<ByteBuf> dst, int streamId, int flags, byte[] headerBlock) {
        int payloadLen = 4 + headerBlock.length;
        ByteBuf frame = context.byteBufAllocator().buffer(FRAME_HEADER_SIZE + payloadLen);

        // Control frame header
        frame.writeByte((byte) (0x80 | ((SPDY_VERSION >>> 8) & 0x7F)));
        frame.writeByte((byte) (SPDY_VERSION & 0xFF));
        frame.writeByte((byte) ((SpdyFrameType.SYN_REPLY >>> 8) & 0xFF));
        frame.writeByte((byte) (SpdyFrameType.SYN_REPLY & 0xFF));
        frame.writeByte((byte) flags);
        frame.writeInt24(payloadLen);

        // Stream ID
        frame.writeInt32(streamId & 0x7FFFFFFF);

        // Header block
        frame.writeBytes(headerBlock, 0, headerBlock.length);
        frame.markWriter();
        dst.offerMessage(frame);
    }

    /**
     * Writes a SPDY data frame.
     * Writes directly to ByteBuf.
     */
    private void writeDataFrame(ProtoContext context, ProtoSndQueue<ByteBuf> dst, int streamId, int flags, byte[] data, int offset, int length) {
        ByteBuf frame = context.byteBufAllocator().buffer(FRAME_HEADER_SIZE + length);

        // Data frame header: C=0 + streamId
        frame.writeInt32(streamId & 0x7FFFFFFF);
        frame.writeByte((byte) flags);
        frame.writeInt24(length);

        if (length > 0) {
            frame.writeBytes(data, offset, length);
        }
        frame.markWriter();
        dst.offerMessage(frame);
    }

    /** Copies regular headers, skipping SPDY pseudo-headers and connection headers. */
    private void copyHeaders(HttpHeaders source, HttpHeaders target) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<String, String> entry : source) {
            String name = entry.getKey().toLowerCase();
            if ("connection".equals(name) || "transfer-encoding".equals(name) || "keep-alive".equals(name) || "host".equals(name)) {
                continue;
            }
            target.add(name, entry.getValue());
        }
    }

    @Override
    public void onClose(ProtoContext context) {
        // No resources to clean up
    }
}
