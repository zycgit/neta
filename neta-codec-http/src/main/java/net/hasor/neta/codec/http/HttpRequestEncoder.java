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
package net.hasor.neta.codec.http;
import java.nio.charset.StandardCharsets;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;

/**
 * Encodes {@link HttpObject} instances into raw bytes for HTTP request messages.
 * <p>
 * This encoder handles the serialization of HTTP requests as defined in
 * <a href="https://tools.ietf.org/html/rfc7230#section-3.1.1">RFC 7230, Section 3.1.1</a>.
 * <p>
 * The encoder expects the following sequence of {@link HttpObject}s:
 * <ol>
 *   <li>{@link HttpRequest} - encodes request-line and headers</li>
 *   <li>Zero or more {@link HttpContent} - encodes body chunks</li>
 *   <li>{@link LastHttpContent} - encodes the final body chunk and optional trailing headers</li>
 * </ol>
 * <p>
 * For {@link FullHttpRequest}, the complete message (request-line + headers + body) is
 * encoded in a single call.
 * <p><b>Thread safety:</b> This handler is stateless. Per-connection state is stored in
 * {@link HttpContext} on the {@link ProtoContext}, making it safe to share a single
 * instance across multiple connections/pipelines.
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLastEncoder("http-request", new HttpRequestEncoder());
 * </pre>
 */
public class HttpRequestEncoder implements ProtoHandler<HttpObject, ByteBuf> {
    private static final byte[]              CRLF           = { '\r', '\n' };
    private static final byte[]              ZERO_CRLF_CRLF = { '0', '\r', '\n', '\r', '\n' };
    private static final int                 SCRATCH_SIZE   = 2048;
    private static final ThreadLocal<byte[]> SCRATCH_BUF    = ThreadLocal.withInitial(() -> new byte[SCRATCH_SIZE]);

    /**
     * Tries to compose the entire request head (request-line + headers + CRLF) into the
     * scratch buffer. Returns the total length if successful, or -1 if the head is too large.
     */
    private static int composeRequestHead(HttpRequest request) {
        byte[] scratch = SCRATCH_BUF.get();
        int pos = 0;

        // Request-line: METHOD SP URI SP VERSION CRLF
        byte[] methodBytes = request.method().nameBytes();
        System.arraycopy(methodBytes, 0, scratch, pos, methodBytes.length);
        pos += methodBytes.length;
        scratch[pos++] = ' ';

        String uri = request.uri();
        int uriLen = uri.length();
        byte[] versionBytes = request.protocolVersion().textBytes();
        int requestLineEnd = pos + uriLen + 1 + versionBytes.length + 2;
        if (requestLineEnd > SCRATCH_SIZE) {
            return -1;
        }
        for (int i = 0; i < uriLen; i++) {
            scratch[pos++] = (byte) uri.charAt(i);
        }
        scratch[pos++] = ' ';
        System.arraycopy(versionBytes, 0, scratch, pos, versionBytes.length);
        pos += versionBytes.length;
        scratch[pos++] = '\r';
        scratch[pos++] = '\n';

        // Headers
        HttpHeaders headers = request.headers();
        if (headers != null && !headers.isEmpty()) {
            pos = headers.composeHeadersTo(scratch, pos, SCRATCH_SIZE - 2);
            if (pos < 0) {
                return -1;
            }
        }

        // Final CRLF
        scratch[pos++] = '\r';
        scratch[pos++] = '\n';
        return pos;
    }

    /** Writes an ASCII string directly byte-by-byte, avoiding String.getBytes() allocation. */
    private static void writeAscii(ByteBuf buf, String s) {
        int len = s.length();
        if (len <= SCRATCH_SIZE) {
            byte[] scratch = SCRATCH_BUF.get();
            for (int i = 0; i < len; i++) {
                scratch[i] = (byte) s.charAt(i);
            }
            buf.writeBytes(scratch, 0, len);
        } else {
            buf.writeString(s, StandardCharsets.US_ASCII);
        }
    }

    /** Writes an int as hex string without String allocation. */
    private static void writeHexInt(ByteBuf buf, int value) {
        byte[] scratch = SCRATCH_BUF.get();
        int idx = 15;
        do {
            int digit = value & 0xF;
            scratch[idx--] = (byte) (digit < 10 ? ('0' + digit) : ('a' + digit - 10));
            value >>>= 4;
        } while (value != 0);
        buf.writeBytes(scratch, idx + 1, 15 - idx);
    }

    @Override
    public void onInit(ProtoContext context) {
        HttpContext.getOrCreate(context);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
        HttpContext httpCtx = context.context(HttpContext.class);
        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            if (msg instanceof FullHttpRequest) {
                // Encode complete request in one go
                encodeFullRequest(httpCtx, context, (FullHttpRequest) msg, dst);
            } else if (msg instanceof HttpRequest) {
                // Encode request line and headers
                encodeRequestHead(httpCtx, context, (HttpRequest) msg, dst);
            } else if (msg instanceof LastHttpContent) {
                // Encode last content chunk
                encodeLastContent(httpCtx, context, (LastHttpContent) msg, dst);
            } else if (msg instanceof HttpContent) {
                // Encode content chunk
                encodeContent(httpCtx, context, (HttpContent) msg, dst);
            }
        }

        return ProtoStatus.Next;
    }

    /**
     * Encodes a complete HTTP request (request-line + headers + body) into bytes.
     */
    private void encodeFullRequest(HttpContext httpCtx, ProtoContext context, FullHttpRequest request, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf content = request.content();
        int bodyLen = (content != null) ? content.readableBytes() : 0;

        // Try to compose entire head into scratch buffer
        int headLen = composeRequestHead(request);
        ByteBuf buf;
        if (headLen > 0) {
            // Fast path: write scratch + content directly into a pooled ByteBuf (no intermediate byte[] allocation)
            buf = context.byteBufAllocator().buffer(headLen + bodyLen);
            buf.writeBytes(SCRATCH_BUF.get(), 0, headLen);
            if (bodyLen > 0) {
                buf.writeBuffer(content, bodyLen);
            }
            buf.markWriter();
        } else {
            buf = context.byteBufAllocator().buffer(256 + bodyLen);
            writeRequestLine(buf, request);
            writeHeaders(buf, request.headers());
            buf.writeBytes(CRLF, 0, CRLF.length);
            if (bodyLen > 0) {
                buf.writeBuffer(content, bodyLen);
            }
            buf.markWriter();
        }
        dst.offerMessage(buf);

        httpCtx.reqChunkedEncoding = false;
    }

    /**
     * Encodes the request-line and headers.
     */
    private void encodeRequestHead(HttpContext httpCtx, ProtoContext context, HttpRequest request, ProtoSndQueue<ByteBuf> dst) {
        // Determine if chunked
        String te = request.headers().get(HttpHeaderNames.TRANSFER_ENCODING);
        httpCtx.reqChunkedEncoding = StringUtils.containsIgnoreCase(te, HttpHeaderValues.CHUNKED);

        int headLen = composeRequestHead(request);
        ByteBuf buf;
        if (headLen > 0) {
            // Fast path: write scratch directly into a pooled ByteBuf (no intermediate byte[] allocation)
            buf = context.byteBufAllocator().buffer(headLen);
            buf.writeBytes(SCRATCH_BUF.get(), 0, headLen);
            buf.markWriter();
        } else {
            buf = context.byteBufAllocator().buffer(256);
            writeRequestLine(buf, request);
            writeHeaders(buf, request.headers());
            buf.writeBytes(CRLF, 0, CRLF.length);
            buf.markWriter();
        }

        dst.offerMessage(buf);
    }

    /**
     * Encodes a body content chunk.
     */
    private void encodeContent(HttpContext httpCtx, ProtoContext context, HttpContent content, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf body = content.content();
        if (body == null || body.readableBytes() == 0) {
            return;
        }

        if (httpCtx.reqChunkedEncoding) {
            // Chunked: SIZE CRLF DATA CRLF
            ByteBuf buf = context.byteBufAllocator().buffer(body.readableBytes() + 32);
            writeHexInt(buf, body.readableBytes());
            buf.writeBytes(CRLF, 0, CRLF.length);
            buf.writeBuffer(body, body.readableBytes());
            buf.writeBytes(CRLF, 0, CRLF.length);
            buf.markWriter();
            dst.offerMessage(buf);
        } else {
            // Direct: just write the body bytes
            ByteBuf buf = context.byteBufAllocator().buffer(body.readableBytes());
            buf.writeBuffer(body, body.readableBytes());
            buf.markWriter();
            dst.offerMessage(buf);
        }
    }

    /**
     * Encodes the last body content chunk and optional trailing headers.
     */
    private void encodeLastContent(HttpContext httpCtx, ProtoContext context, LastHttpContent lastContent, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf body = lastContent.content();

        if (httpCtx.reqChunkedEncoding) {
            if (body != null && body.readableBytes() > 0) {
                ByteBuf chunkBuf = context.byteBufAllocator().buffer(body.readableBytes() + 32);
                writeHexInt(chunkBuf, body.readableBytes());
                chunkBuf.writeBytes(CRLF, 0, CRLF.length);
                chunkBuf.writeBuffer(body, body.readableBytes());
                chunkBuf.writeBytes(CRLF, 0, CRLF.length);
                chunkBuf.markWriter();
                dst.offerMessage(chunkBuf);
            }

            ByteBuf lastBuf = context.byteBufAllocator().buffer(64);
            HttpHeaders trailers = lastContent.trailerHeaders();

            if (trailers != null && !trailers.isEmpty()) {
                writeHexInt(lastBuf, 0);
                lastBuf.writeBytes(CRLF, 0, CRLF.length);
                writeHeaders(lastBuf, trailers);
                lastBuf.writeBytes(CRLF, 0, CRLF.length);
            } else {
                lastBuf.writeBytes(ZERO_CRLF_CRLF, 0, ZERO_CRLF_CRLF.length);
            }

            lastBuf.markWriter();
            dst.offerMessage(lastBuf);
        } else {
            // Non-chunked: just write remaining body if any
            if (body != null && body.readableBytes() > 0) {
                ByteBuf buf = context.byteBufAllocator().buffer(body.readableBytes());
                buf.writeBuffer(body, body.readableBytes());
                buf.markWriter();
                dst.offerMessage(buf);
            }
        }

        // Reset state
        httpCtx.reqChunkedEncoding = false;
    }

    /**
     * Writes the request-line: METHOD SP URI SP VERSION CRLF
     */
    private void writeRequestLine(ByteBuf buf, HttpRequest request) {
        byte[] scratch = SCRATCH_BUF.get();
        int pos = 0;
        byte[] methodBytes = request.method().nameBytes();
        System.arraycopy(methodBytes, 0, scratch, pos, methodBytes.length);
        pos += methodBytes.length;
        scratch[pos++] = ' ';
        String uri = request.uri();
        int uriLen = uri.length();
        byte[] versionBytes = request.protocolVersion().textBytes();
        int totalLen = pos + uriLen + 1 + versionBytes.length + 2;
        if (totalLen <= SCRATCH_SIZE) {
            for (int i = 0; i < uriLen; i++) {
                scratch[pos++] = (byte) uri.charAt(i);
            }
            scratch[pos++] = ' ';
            System.arraycopy(versionBytes, 0, scratch, pos, versionBytes.length);
            pos += versionBytes.length;
            scratch[pos++] = '\r';
            scratch[pos++] = '\n';
            buf.writeBytes(scratch, 0, pos);
        } else {
            buf.writeBytes(scratch, 0, pos);
            writeAscii(buf, uri);
            scratch[0] = ' ';
            System.arraycopy(versionBytes, 0, scratch, 1, versionBytes.length);
            int off = 1 + versionBytes.length;
            scratch[off++] = '\r';
            scratch[off++] = '\n';
            buf.writeBytes(scratch, 0, off);
        }
    }

    /**
     * Writes all headers using forEachHeader callback to avoid Entry allocation.
     */
    private void writeHeaders(ByteBuf buf, HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        headers.forEachHeader((name, value) -> {
            byte[] scratch = SCRATCH_BUF.get();
            int nameLen = name.length();
            int valueLen = value.length();
            int totalLen = nameLen + 2 + valueLen + 2;
            if (totalLen <= SCRATCH_SIZE) {
                int pos = 0;
                for (int i = 0; i < nameLen; i++) {
                    scratch[pos++] = (byte) name.charAt(i);
                }
                scratch[pos++] = ':';
                scratch[pos++] = ' ';
                for (int i = 0; i < valueLen; i++) {
                    scratch[pos++] = (byte) value.charAt(i);
                }
                scratch[pos++] = '\r';
                scratch[pos++] = '\n';
                buf.writeBytes(scratch, 0, pos);
            } else {
                writeAscii(buf, name);
                scratch[0] = ':';
                scratch[1] = ' ';
                buf.writeBytes(scratch, 0, 2);
                writeAscii(buf, value);
                scratch[0] = '\r';
                scratch[1] = '\n';
                buf.writeBytes(scratch, 0, 2);
            }
        });
    }
}