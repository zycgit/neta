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
 * Encodes {@link HttpObject} instances into raw bytes for HTTP response messages.
 * <p>
 * This encoder handles the serialization of HTTP responses as defined in
 * <a href="https://tools.ietf.org/html/rfc7230#section-3.1.2">RFC 7230, Section 3.1.2</a>.
 * <p>
 * The encoder expects the following sequence of {@link HttpObject}s:
 * <ol>
 *   <li>{@link HttpResponse} - encodes status-line and headers</li>
 *   <li>Zero or more {@link HttpContent} - encodes body chunks</li>
 *   <li>{@link LastHttpContent} - encodes the final body chunk and optional trailing headers</li>
 * </ol>
 * <p>
 * For {@link FullHttpResponse}, the complete message (status-line + headers + body) is
 * encoded in a single call.
 * <p><b>Thread safety:</b> This handler maintains internal state ({@code chunkedEncoding})
 * and is intended to be used per-connection. Do not share a single instance across
 * multiple connections/pipelines.
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLastEncoder("http-response", new HttpResponseEncoder());
 * </pre>
 */
public class HttpResponseEncoder implements ProtoHandler<HttpObject, ByteBuf> {
    private static final byte[]              CRLF            = { '\r', '\n' };
    private static final byte[]              ZERO_CRLF_CRLF  = { '0', '\r', '\n', '\r', '\n' };
    private static final int                 SCRATCH_SIZE    = 2048;
    private static final ThreadLocal<byte[]> SCRATCH_BUF     = ThreadLocal.withInitial(() -> new byte[SCRATCH_SIZE]);
    private              boolean             chunkedEncoding = false;

    /**
     * Tries to compose the entire response head (status-line + headers + CRLF) into the
     * scratch buffer. Returns the total length if successful, or -1 if too large.
     */
    private static int composeResponseHead(HttpResponse response) {
        byte[] scratch = SCRATCH_BUF.get();
        int pos = 0;

        // Status-line: VERSION SP CODE SP REASON CRLF
        byte[] versionBytes = response.protocolVersion().textBytes();
        byte[] codeBytes = response.status().codeBytes();
        byte[] reasonBytes = response.status().reasonPhraseBytes();
        int statusLineEnd = versionBytes.length + 1 + codeBytes.length + 1 + reasonBytes.length + 2;
        if (statusLineEnd > SCRATCH_SIZE) {
            return -1;
        }
        System.arraycopy(versionBytes, 0, scratch, pos, versionBytes.length);
        pos += versionBytes.length;
        scratch[pos++] = ' ';
        System.arraycopy(codeBytes, 0, scratch, pos, codeBytes.length);
        pos += codeBytes.length;
        scratch[pos++] = ' ';
        System.arraycopy(reasonBytes, 0, scratch, pos, reasonBytes.length);
        pos += reasonBytes.length;
        scratch[pos++] = '\r';
        scratch[pos++] = '\n';

        // Headers
        HttpHeaders headers = response.headers();
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
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            if (msg instanceof FullHttpResponse) {
                encodeFullResponse(context, (FullHttpResponse) msg, dst);
            } else if (msg instanceof HttpResponse) {
                encodeResponseHead(context, (HttpResponse) msg, dst);
            } else if (msg instanceof LastHttpContent) {
                encodeLastContent(context, (LastHttpContent) msg, dst);
            } else if (msg instanceof HttpContent) {
                encodeContent(context, (HttpContent) msg, dst);
            }
        }

        return ProtoStatus.Next;
    }

    /**
     * Encodes a complete HTTP response (status-line + headers + body) into bytes.
     */
    private void encodeFullResponse(ProtoContext context, FullHttpResponse response, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf content = response.content();
        int bodyLen = (content != null) ? content.readableBytes() : 0;

        // Try to compose entire head into scratch buffer
        int headLen = composeResponseHead(response);
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
            writeStatusLine(buf, response);
            writeHeaders(buf, response.headers());
            buf.writeBytes(CRLF, 0, CRLF.length);
            if (bodyLen > 0) {
                buf.writeBuffer(content, bodyLen);
            }
            buf.markWriter();
        }
        dst.offerMessage(buf);

        chunkedEncoding = false;
    }

    /**
     * Encodes the status-line and headers.
     */
    private void encodeResponseHead(ProtoContext context, HttpResponse response, ProtoSndQueue<ByteBuf> dst) {
        // Determine if chunked
        String te = response.headers().get(HttpHeaderNames.TRANSFER_ENCODING);
        chunkedEncoding = StringUtils.containsIgnoreCase(te, HttpHeaderValues.CHUNKED);

        int headLen = composeResponseHead(response);
        ByteBuf buf;
        if (headLen > 0) {
            // Fast path: write scratch directly into a pooled ByteBuf (no intermediate byte[] allocation)
            buf = context.byteBufAllocator().buffer(headLen);
            buf.writeBytes(SCRATCH_BUF.get(), 0, headLen);
            buf.markWriter();
        } else {
            buf = context.byteBufAllocator().buffer(256);
            writeStatusLine(buf, response);
            writeHeaders(buf, response.headers());
            buf.writeBytes(CRLF, 0, CRLF.length);
            buf.markWriter();
        }

        dst.offerMessage(buf);
    }

    /**
     * Encodes a body content chunk.
     */
    private void encodeContent(ProtoContext context, HttpContent content, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf body = content.content();
        if (body == null || body.readableBytes() == 0) {
            return;
        }

        if (chunkedEncoding) {
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
    private void encodeLastContent(ProtoContext context, LastHttpContent lastContent, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf body = lastContent.content();

        if (chunkedEncoding) {
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
        chunkedEncoding = false;
    }

    /**
     * Writes the status-line: VERSION SP CODE SP REASON CRLF
     */
    private void writeStatusLine(ByteBuf buf, HttpResponse response) {
        byte[] scratch = SCRATCH_BUF.get();
        int pos = 0;
        byte[] versionBytes = response.protocolVersion().textBytes();
        byte[] codeBytes = response.status().codeBytes();
        byte[] reasonBytes = response.status().reasonPhraseBytes();
        System.arraycopy(versionBytes, 0, scratch, pos, versionBytes.length);
        pos += versionBytes.length;
        scratch[pos++] = ' ';
        System.arraycopy(codeBytes, 0, scratch, pos, codeBytes.length);
        pos += codeBytes.length;
        scratch[pos++] = ' ';
        int totalLen = pos + reasonBytes.length + 2;
        if (totalLen <= SCRATCH_SIZE) {
            System.arraycopy(reasonBytes, 0, scratch, pos, reasonBytes.length);
            pos += reasonBytes.length;
            scratch[pos++] = '\r';
            scratch[pos++] = '\n';
            buf.writeBytes(scratch, 0, pos);
        } else {
            buf.writeBytes(scratch, 0, pos);
            buf.writeBytes(reasonBytes, 0, reasonBytes.length);
            buf.writeBytes(CRLF, 0, CRLF.length);
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
