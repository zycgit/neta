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
import java.util.Map;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.constant.HttpHeaderNames;
import net.hasor.neta.codec.http.constant.HttpHeaderValues;

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
    private static final byte[]  CRLF            = { '\r', '\n' };
    private static final byte[]  SP              = { ' ' };
    private static final byte[]  COLON           = { ':', ' ' };
    private static final byte[]  ZERO_CRLF_CRLF  = { '0', '\r', '\n', '\r', '\n' };
    //
    private              boolean chunkedEncoding = false;

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
        ByteBuf buf = context.byteBufAllocator().buffer(256, Integer.MAX_VALUE);

        // Status-line: VERSION SP STATUS SP REASON CRLF
        writeStatusLine(buf, response);

        // Headers
        writeHeaders(buf, response.headers());

        // CRLF (end of headers)
        buf.writeBytes(CRLF, 0, CRLF.length);

        // Body
        ByteBuf content = response.content();
        if (content != null && content.readableBytes() > 0) {
            buf.writeBuffer(content, content.readableBytes());
        }

        buf.markWriter();
        dst.offerMessage(buf);

        chunkedEncoding = false;
    }

    /**
     * Encodes the status-line and headers.
     */
    private void encodeResponseHead(ProtoContext context, HttpResponse response, ProtoSndQueue<ByteBuf> dst) {
        ByteBuf buf = context.byteBufAllocator().buffer(256, Integer.MAX_VALUE);

        // Status-line
        writeStatusLine(buf, response);

        // Determine if chunked
        String te = response.headers().get(HttpHeaderNames.TRANSFER_ENCODING);
        chunkedEncoding = te != null && HttpHeaders.containsIgnoreCase(te, HttpHeaderValues.CHUNKED);

        // Headers
        writeHeaders(buf, response.headers());

        // CRLF (end of headers)
        buf.writeBytes(CRLF, 0, CRLF.length);

        buf.markWriter();
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
            ByteBuf buf = context.byteBufAllocator().buffer(body.readableBytes() + 32);
            String sizeHex = Integer.toHexString(body.readableBytes());
            buf.writeString(sizeHex, StandardCharsets.US_ASCII);
            buf.writeBytes(CRLF, 0, CRLF.length);
            buf.writeBuffer(body, body.readableBytes());
            buf.writeBytes(CRLF, 0, CRLF.length);
            buf.markWriter();
            dst.offerMessage(buf);
        } else {
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
                String sizeHex = Integer.toHexString(body.readableBytes());
                chunkBuf.writeString(sizeHex, StandardCharsets.US_ASCII);
                chunkBuf.writeBytes(CRLF, 0, CRLF.length);
                chunkBuf.writeBuffer(body, body.readableBytes());
                chunkBuf.writeBytes(CRLF, 0, CRLF.length);
                chunkBuf.markWriter();
                dst.offerMessage(chunkBuf);
            }

            ByteBuf lastBuf = context.byteBufAllocator().buffer(64);
            HttpHeaders trailers = lastContent.trailerHeaders();

            if (trailers != null && !trailers.isEmpty()) {
                lastBuf.writeString("0", StandardCharsets.US_ASCII);
                lastBuf.writeBytes(CRLF, 0, CRLF.length);
                writeHeaders(lastBuf, trailers);
                lastBuf.writeBytes(CRLF, 0, CRLF.length);
            } else {
                lastBuf.writeBytes(ZERO_CRLF_CRLF, 0, ZERO_CRLF_CRLF.length);
            }

            lastBuf.markWriter();
            dst.offerMessage(lastBuf);
        } else {
            if (body != null && body.readableBytes() > 0) {
                ByteBuf buf = context.byteBufAllocator().buffer(body.readableBytes());
                buf.writeBuffer(body, body.readableBytes());
                buf.markWriter();
                dst.offerMessage(buf);
            }
        }

        chunkedEncoding = false;
    }

    /**
     * Writes the status-line: VERSION SP STATUS_CODE SP REASON CRLF
     */
    private void writeStatusLine(ByteBuf buf, HttpResponse response) {
        buf.writeString(response.protocolVersion().text(), StandardCharsets.US_ASCII);
        buf.writeBytes(SP, 0, SP.length);
        buf.writeString(response.status().codeAsString(), StandardCharsets.US_ASCII);
        buf.writeBytes(SP, 0, SP.length);
        buf.writeString(response.status().reasonPhrase(), StandardCharsets.US_ASCII);
        buf.writeBytes(CRLF, 0, CRLF.length);
    }

    /**
     * Writes all headers: NAME ": " VALUE CRLF for each header.
     */
    private void writeHeaders(ByteBuf buf, HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : headers) {
            buf.writeString(entry.getKey(), StandardCharsets.US_ASCII);
            buf.writeBytes(COLON, 0, COLON.length);
            buf.writeString(entry.getValue(), StandardCharsets.US_ASCII);
            buf.writeBytes(CRLF, 0, CRLF.length);
        }
    }
}
