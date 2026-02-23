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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;

/**
 * Semantic HTTP/2 encoder that converts standard {@link HttpObject} instances
 * into {@link Http2Frame} objects.
 * <p>
 * This encoder takes the same {@link HttpObject} types used by HTTP/1.x
 * ({@link HttpRequest}, {@link HttpResponse}, {@link HttpContent}, {@link LastHttpContent})
 * and converts them into typed {@link Http2Frame} instances with HPACK-compressed
 * headers, ready for binary serialization by {@link Http2FrameEncoder}.
 * <p>
 * <b>Encode path:</b> {@code HttpObject → Http2Frame → ByteBuf}
 * @see Http2Frame
 * @see Http2FrameEncoder
 */
public class HttpObjectToHttp2FrameEncoder implements ProtoHandler<HttpObject, Http2Frame> {
    /** HTTP/2 connection preface sent by the client */
    private static final byte[]        CLIENT_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private final        HpackEncoder  hpackEncoder;
    private final        AtomicInteger nextStreamId;
    private final        HttpScheme    scheme         = HttpScheme.HTTPS;

    private boolean prefaceSent;
    private int     currentStreamId = 0;

    /** Sets the stream ID to use for the next response encoding. Called by Http2ServerDuplexe. */
    void setResponseStreamId(int streamId) {
        this.currentStreamId = streamId;
    }

    /**
     * Creates a new HttpObject-to-Http2Frame encoder with default HPACK settings.
     * @param serverMode true for server-side, false for client-side
     */
    public HttpObjectToHttp2FrameEncoder(boolean serverMode) {
        this(serverMode, 4096);
    }

    /**
     * Creates a new HttpObject-to-Http2Frame encoder with custom HPACK settings.
     * @param serverMode true for server-side, false for client-side
     * @param maxHeaderTableSize maximum HPACK dynamic table size in bytes
     */
    public HttpObjectToHttp2FrameEncoder(boolean serverMode, int maxHeaderTableSize) {
        this.hpackEncoder = new HpackEncoder(maxHeaderTableSize);
        this.nextStreamId = new AtomicInteger(serverMode ? 2 : 1);
        this.prefaceSent = serverMode; // Server doesn't send the connection preface
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Http2Frame> dst) throws Throwable {
        // Send client preface as a special "preface" frame if needed
        if (!prefaceSent) {
            // Emit client connection preface as a special frame
            // The downstream Http2FrameEncoder will handle writing this as raw bytes
            dst.offerMessage(new Http2Frame(Http2FrameType.PREFACE, 0, 0, CLIENT_PREFACE));

            // Also send initial empty SETTINGS frame
            dst.offerMessage(Http2Frame.settings(Http2Flags.NONE, new byte[0]));
            prefaceSent = true;
        }

        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            if (msg instanceof FullHttpResponse) {
                encodeFullResponse((FullHttpResponse) msg, dst);
            } else if (msg instanceof FullHttpRequest) {
                encodeFullRequest((FullHttpRequest) msg, dst);
            } else if (msg instanceof HttpResponse) {
                encodeResponseHeaders((HttpResponse) msg, dst);
            } else if (msg instanceof HttpRequest) {
                encodeRequestHeaders((HttpRequest) msg, dst);
            } else if (msg instanceof LastHttpContent) {
                encodeLastContent((LastHttpContent) msg, dst);
            } else if (msg instanceof HttpContent) {
                encodeContent((HttpContent) msg, dst);
            }
        }

        return ProtoStatus.Next;
    }

    /**
     * Encodes a complete HTTP response (headers + body) as HEADERS + DATA frames.
     */
    private void encodeFullResponse(FullHttpResponse response, ProtoSndQueue<Http2Frame> dst) {
        ByteBuf body = response.content();
        boolean hasBody = body != null && body.readableBytes() > 0;

        hpackEncoder.beginEncode();
        hpackEncoder.encodeHeaderDirect(":status", String.valueOf(response.status().code()));
        encodeHeadersDirect(response.headers());

        int headerBlockLen = hpackEncoder.encodedLength();
        byte[] headerBlock = new byte[headerBlockLen];
        System.arraycopy(hpackEncoder.encodedBuffer(), 0, headerBlock, 0, headerBlockLen);

        if (!hasBody) {
            dst.offerMessage(Http2Frame.headers(currentStreamId, Http2Flags.END_STREAM | Http2Flags.END_HEADERS, headerBlock));
        } else {
            dst.offerMessage(Http2Frame.headers(currentStreamId, Http2Flags.END_HEADERS, headerBlock));
            byte[] bodyBytes = extractBodyBytes(body);
            dst.offerMessage(Http2Frame.data(currentStreamId, Http2Flags.END_STREAM, bodyBytes));
        }
    }

    /**
     * Encodes a complete HTTP request (headers + body) as HEADERS + DATA frames.
     */
    private void encodeFullRequest(FullHttpRequest request, ProtoSndQueue<Http2Frame> dst) {
        currentStreamId = nextStreamId.getAndAdd(2);

        ByteBuf body = request.content();
        boolean hasBody = body != null && body.readableBytes() > 0;

        hpackEncoder.beginEncode();
        hpackEncoder.encodeHeaderDirect(":method", request.method().name());
        hpackEncoder.encodeHeaderDirect(":path", request.uri());
        String host = request.headers().get(HttpHeaderNames.HOST);
        if (StringUtils.isNotBlank(host)) {
            hpackEncoder.encodeHeaderDirect(":authority", host);
        }
        hpackEncoder.encodeHeaderDirect(":scheme", scheme.name());
        encodeHeadersDirect(request.headers());

        int headerBlockLen = hpackEncoder.encodedLength();
        byte[] headerBlock = new byte[headerBlockLen];
        System.arraycopy(hpackEncoder.encodedBuffer(), 0, headerBlock, 0, headerBlockLen);

        if (!hasBody) {
            dst.offerMessage(Http2Frame.headers(currentStreamId, Http2Flags.END_STREAM | Http2Flags.END_HEADERS, headerBlock));
        } else {
            dst.offerMessage(Http2Frame.headers(currentStreamId, Http2Flags.END_HEADERS, headerBlock));
            byte[] bodyBytes = extractBodyBytes(body);
            dst.offerMessage(Http2Frame.data(currentStreamId, Http2Flags.END_STREAM, bodyBytes));
        }
    }

    /**
     * Encodes HTTP response headers as a HEADERS frame.
     */
    private void encodeResponseHeaders(HttpResponse response, ProtoSndQueue<Http2Frame> dst) {
        hpackEncoder.beginEncode();
        hpackEncoder.encodeHeaderDirect(":status", String.valueOf(response.status().code()));
        encodeHeadersDirect(response.headers());

        int headerBlockLen = hpackEncoder.encodedLength();
        byte[] headerBlock = new byte[headerBlockLen];
        System.arraycopy(hpackEncoder.encodedBuffer(), 0, headerBlock, 0, headerBlockLen);

        dst.offerMessage(Http2Frame.headers(currentStreamId, Http2Flags.END_HEADERS, headerBlock));
    }

    /**
     * Encodes HTTP request headers as a HEADERS frame.
     */
    private void encodeRequestHeaders(HttpRequest request, ProtoSndQueue<Http2Frame> dst) {
        currentStreamId = nextStreamId.getAndAdd(2);

        hpackEncoder.beginEncode();
        hpackEncoder.encodeHeaderDirect(":method", request.method().name());
        hpackEncoder.encodeHeaderDirect(":path", request.uri());
        String host = request.headers().get(HttpHeaderNames.HOST);
        if (StringUtils.isNotBlank(host)) {
            hpackEncoder.encodeHeaderDirect(":authority", host);
        }
        hpackEncoder.encodeHeaderDirect(":scheme", scheme.name());
        encodeHeadersDirect(request.headers());

        int headerBlockLen = hpackEncoder.encodedLength();
        byte[] headerBlock = new byte[headerBlockLen];
        System.arraycopy(hpackEncoder.encodedBuffer(), 0, headerBlock, 0, headerBlockLen);

        dst.offerMessage(Http2Frame.headers(currentStreamId, Http2Flags.END_HEADERS, headerBlock));
    }

    /**
     * Encodes body content as a DATA frame.
     */
    private void encodeContent(HttpContent content, ProtoSndQueue<Http2Frame> dst) {
        ByteBuf body = content.content();
        if (body == null || body.readableBytes() == 0) {
            return;
        }
        byte[] bodyBytes = extractBodyBytes(body);
        dst.offerMessage(Http2Frame.data(currentStreamId, Http2Flags.NONE, bodyBytes));
    }

    /**
     * Encodes the last content chunk as a DATA frame with END_STREAM.
     */
    private void encodeLastContent(LastHttpContent lastContent, ProtoSndQueue<Http2Frame> dst) {
        ByteBuf body = lastContent.content();
        int bodyLen = (body != null) ? body.readableBytes() : 0;

        if (bodyLen > 0) {
            byte[] bodyBytes = extractBodyBytes(body);
            dst.offerMessage(Http2Frame.data(currentStreamId, Http2Flags.END_STREAM, bodyBytes));
        } else {
            dst.offerMessage(Http2Frame.data(currentStreamId, Http2Flags.END_STREAM, new byte[0]));
        }
    }

    /**
     * Extracts body bytes from a ByteBuf.
     */
    private byte[] extractBodyBytes(ByteBuf body) {
        int bodyLen = body.readableBytes();
        byte[] bodyBytes = new byte[bodyLen];
        body.getBytes(0, bodyBytes, 0, bodyLen);
        return bodyBytes;
    }

    /**
     * Copies regular headers (non-pseudo, non-connection) directly into the HPACK encoder.
     */
    private void encodeHeadersDirect(HttpHeaders source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<String, String> entry : source) {
            String name = entry.getKey().toLowerCase();
            // Skip HTTP/2 connection-specific headers (RFC 9113, Section 8.2.2)
            if (StringUtils.equals(HttpHeaderNames.CONNECTION, name)              //
                    || StringUtils.equals(HttpHeaderNames.TRANSFER_ENCODING, name)//
                    || StringUtils.equals(HttpHeaderNames.KEEP_ALIVE, name)       //
                    || StringUtils.equals(HttpHeaderNames.PROXY_CONNECTION, name) //
                    || StringUtils.equals(HttpHeaderNames.UPGRADE, name)          //
                    || StringUtils.equals(HttpHeaderNames.HOST, name)) {
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
