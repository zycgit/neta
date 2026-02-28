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
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;

/**
 * Decodes raw bytes into HTTP response objects ({@link HttpObject}).
 * <p>
 * This decoder implements the HTTP/1.x response parsing state machine as defined in
 * <a href="https://tools.ietf.org/html/rfc7230">RFC 7230</a>.
 * <p>
 * The decoder emits the following sequence of {@link HttpObject}s for each response:
 * <ol>
 *   <li>{@link DefaultHttpResponse} - the status line and headers</li>
 *   <li>Zero or more {@link DefaultHttpContent} - body chunks</li>
 *   <li>{@link DefaultLastHttpContent} - marks the end of the response body</li>
 * </ol>
 * <p>
 * Supported transfer modes (RFC 7230 §3.3.3):
 * <ul>
 *   <li>Chunked transfer encoding (§4.1)</li>
 *   <li>Content-Length based body (§3.3.2)</li>
 *   <li>Connection close delimited body (HTTP/1.0 without Content-Length)</li>
 *   <li>No body (1xx, 204, 304 responses)</li>
 * </ul>
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLastDecoder("http-response", new HttpResponseDecoder());
 * </pre>
 */
public class HttpResponseDecoder implements ProtoHandler<ByteBuf, HttpObject> {
    private static final int                 DEFAULT_MAX_INITIAL_LINE_LENGTH = 4096;
    private static final int                 DEFAULT_MAX_HEADER_SIZE         = 8192;
    private static final int                 DEFAULT_MAX_CHUNK_SIZE          = 8192;
    private static final int                 SCAN_BUF_SIZE                   = 8192;
    private static final ThreadLocal<byte[]> SCAN_BUF                        = ThreadLocal.withInitial(() -> new byte[SCAN_BUF_SIZE]);
    //
    private final        int                 maxInitialLineLength;
    private final        int                 maxHeaderSize;
    private final        int                 maxChunkSize;

    /** Creates a decoder with default limits. */
    public HttpResponseDecoder() {
        this(DEFAULT_MAX_INITIAL_LINE_LENGTH, DEFAULT_MAX_HEADER_SIZE, DEFAULT_MAX_CHUNK_SIZE);
    }

    /**
     * Creates a decoder with the specified limits.
     * @param maxInitialLineLength maximum length of the status line
     * @param maxHeaderSize maximum total size of all headers
     * @param maxChunkSize maximum chunk size for content delivery
     */
    public HttpResponseDecoder(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
        if (maxInitialLineLength <= 0) {
            throw new IllegalArgumentException("maxInitialLineLength must be positive");
        }
        if (maxHeaderSize <= 0) {
            throw new IllegalArgumentException("maxHeaderSize must be positive");
        }
        if (maxChunkSize <= 0) {
            throw new IllegalArgumentException("maxChunkSize must be positive");
        }
        this.maxInitialLineLength = maxInitialLineLength;
        this.maxHeaderSize = maxHeaderSize;
        this.maxChunkSize = maxChunkSize;
    }

    @Override
    public void onInit(ProtoContext context) {
        HttpContext.getOrCreate(context);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        HttpContext respCtx = context.context(HttpContext.class);

        // Create a zero-copy view over all queued ByteBuf data
        if (respCtx.respAccumulator != null) {
            respCtx.respAccumulator.free();
        }
        respCtx.respAccumulator = ByteBufUtils.queueBuffer(src);

        // Try to decode as much as possible
        boolean needMoreData = false;

        while (!needMoreData) {
            switch (respCtx.respDecoderState) {
                case READ_INITIAL:
                    needMoreData = !decodeStatusLine(respCtx, dst);
                    break;

                case READ_HEADER:
                    needMoreData = !decodeHeaders(respCtx, dst);
                    break;

                case READ_FIXED_LENGTH_CONTENT:
                    needMoreData = !decodeFixedLengthContent(respCtx, context, dst);
                    break;

                case READ_VARIABLE_LENGTH_CONTENT:
                    needMoreData = !decodeVariableLengthContent(respCtx, context, dst);
                    break;

                case READ_CHUNK_SIZE:
                    needMoreData = !decodeChunkSize(respCtx, dst);
                    break;

                case READ_CHUNKED_CONTENT:
                    needMoreData = !decodeChunkedContent(respCtx, context, dst);
                    break;

                case READ_CHUNK_DELIMITER:
                    needMoreData = !decodeChunkDelimiter(respCtx);
                    break;

                case READ_CHUNK_TRAILER:
                    needMoreData = !decodeChunkTrailer(respCtx, dst);
                    break;

                case DONE:
                    respCtx.resetRespDecoder();
                    needMoreData = respCtx.respAccumulator.readableBytes() == 0;
                    break;

                default:
                    needMoreData = true;
                    break;
            }
        }

        // Consume fully-read ByteBuf messages from the queue
        respCtx.respAccumulator.markReader();

        return ProtoStatus.Next;
    }

    /**
     * Parses the status-line: HTTP-version SP status-code SP reason-phrase CRLF
     * (RFC 7230 §3.1.2)
     */
    private boolean decodeStatusLine(HttpContext respCtx, ProtoSndQueue<HttpObject> dst) {
        String line = fastReadLine(respCtx);
        if (line == null) {
            return false;
        }

        // Skip empty lines (robustness, RFC 7230 §3.5)
        if (line.isEmpty()) {
            return true;
        }

        if (line.length() > maxInitialLineLength) {
            throw new HttpInitialLineTooLongException("status line too long: " + line.length() + " > " + maxInitialLineLength, maxInitialLineLength, line.length());
        }

        // Parse: VERSION SP STATUS SP REASON
        int firstSpace = line.indexOf(' ');
        if (firstSpace < 0) {
            throw new HttpBadRequestException("invalid status line: " + line);
        }

        String versionStr = line.substring(0, firstSpace);
        HttpVersion version = HttpVersion.valueOf(versionStr);

        // Find second space (separates status code from reason phrase)
        int secondSpace = line.indexOf(' ', firstSpace + 1);

        // Parse status code directly from characters (avoid substring + Integer.parseInt)
        int codeEnd = secondSpace >= 0 ? secondSpace : line.length();
        int statusCode = 0;
        for (int i = firstSpace + 1; i < codeEnd; i++) {
            char c = line.charAt(i);
            if (c < '0' || c > '9') {
                if (c != ' ' && c != '\t') {
                    throw new HttpBadRequestException("invalid status code in: " + line);
                }
                continue; // skip whitespace
            }
            statusCode = statusCode * 10 + (c - '0');
        }
        if (statusCode < 100 || statusCode > 999) {
            throw new HttpBadRequestException("invalid status code: " + statusCode);
        }

        String reasonPhrase = secondSpace >= 0 ? line.substring(secondSpace + 1) : "";
        HttpStatus status = HttpStatus.valueOf(statusCode, reasonPhrase);
        respCtx.respCurrentResponse = new DefaultHttpResponse(version, status);
        respCtx.respDecoderState = HttpContext.RespDecoderState.READ_HEADER;
        return true;
    }

    /**
     * Parses header fields until an empty line is encountered.
     * (RFC 7230 §3.2)
     */
    private boolean decodeHeaders(HttpContext respCtx, ProtoSndQueue<HttpObject> dst) {
        HttpHeaders headers = respCtx.respCurrentResponse.headers();

        String line;
        while ((line = fastReadLine(respCtx)) != null) {

            // Empty line marks end of headers
            if (line.isEmpty()) {
                determineTransferMode(respCtx, headers);
                dst.offerMessage(respCtx.respCurrentResponse);

                // Determine body state based on response status and transfer mode
                int statusCode = respCtx.respCurrentResponse.status().code();

                // RFC 7230 §3.3.3: responses with certain status codes MUST NOT have a body
                if (statusCode == 204 || statusCode == 304 || (statusCode >= 100 && statusCode < 200)) {
                    dst.offerMessage(DefaultLastHttpContent.EMPTY_LAST_CONTENT);
                    respCtx.respDecoderState = HttpContext.RespDecoderState.DONE;
                } else if (respCtx.respChunked) {
                    respCtx.respDecoderState = HttpContext.RespDecoderState.READ_CHUNK_SIZE;
                } else if (respCtx.respContentLength >= 0) {
                    if (respCtx.respContentLength == 0) {
                        dst.offerMessage(DefaultLastHttpContent.EMPTY_LAST_CONTENT);
                        respCtx.respDecoderState = HttpContext.RespDecoderState.DONE;
                    } else {
                        respCtx.respDecoderState = HttpContext.RespDecoderState.READ_FIXED_LENGTH_CONTENT;
                        respCtx.respBytesRead = 0;
                    }
                } else {
                    // No Content-Length and not chunked:
                    // For HTTP/1.0 or Connection: close, read until connection closed
                    // For HTTP/1.1 without body indication, assume no body
                    HttpVersion version = respCtx.respCurrentResponse.protocolVersion();
                    String connection = headers.get(HttpHeaderNames.CONNECTION);
                    boolean isClose = HttpVersion.HTTP_1_0.equals(version) || StringUtils.containsIgnoreCase(connection, HttpHeaderValues.CLOSE);

                    if (isClose) {
                        respCtx.respDecoderState = HttpContext.RespDecoderState.READ_VARIABLE_LENGTH_CONTENT;
                    } else {
                        dst.offerMessage(DefaultLastHttpContent.EMPTY_LAST_CONTENT);
                        respCtx.respDecoderState = HttpContext.RespDecoderState.DONE;
                    }
                }
                return true;
            }

            respCtx.respHeaderBytes += line.length() + 2; // +2 for CRLF
            if (respCtx.respHeaderBytes > maxHeaderSize) {
                throw new HttpHeaderTooLargeException("HTTP headers too large: " + respCtx.respHeaderBytes + " > " + maxHeaderSize, maxHeaderSize, respCtx.respHeaderBytes);
            }

            // Handle obs-fold (RFC 7230 §3.2.4)
            if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && !headers.isEmpty()) {
                // Append folded continuation to the last added header value
                String continuation = ' ' + line.trim();
                if (respCtx.respLastHeaderName != null) {
                    String existing = headers.get(respCtx.respLastHeaderName);
                    headers.set(respCtx.respLastHeaderName, existing + continuation);
                }
                continue;
            }

            // Parse header: name ":" value
            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) {
                throw new HttpBadRequestException("invalid header line (no colon): " + line);
            }

            String name = line.substring(0, colonIdx).trim();
            String value = line.substring(colonIdx + 1).trim();

            if (name.isEmpty()) {
                throw new HttpBadRequestException("empty header name");
            }

            headers.add(name, value);
            respCtx.respLastHeaderName = name;
        }

        return false;
    }

    private void determineTransferMode(HttpContext respCtx, HttpHeaders headers) {
        respCtx.respChunked = false;
        respCtx.respContentLength = -1;

        String te = headers.get(HttpHeaderNames.TRANSFER_ENCODING);
        if (StringUtils.containsIgnoreCase(te, HttpHeaderValues.CHUNKED)) {
            respCtx.respChunked = true;
            return;
        }

        String cl = headers.get(HttpHeaderNames.CONTENT_LENGTH);
        if (StringUtils.isNotBlank(cl)) {
            try {
                respCtx.respContentLength = Long.parseLong(cl.trim());
                if (respCtx.respContentLength < 0) {
                    throw new HttpContentTooLargeException("negative Content-Length: " + respCtx.respContentLength);
                }
            } catch (NumberFormatException e) {
                throw new HttpBadRequestException("invalid Content-Length: " + cl, e);
            }
        }
    }

    private boolean decodeFixedLengthContent(HttpContext respCtx, ProtoContext context, ProtoSndQueue<HttpObject> dst) {
        int readable = respCtx.respAccumulator.readableBytes();
        if (readable == 0) {
            return false;
        }

        long remaining = respCtx.respContentLength - respCtx.respBytesRead;
        int toRead = (int) Math.min(Math.min(remaining, readable), maxChunkSize);

        if (toRead > 0) {
            ByteBuf content = context.byteBufAllocator().buffer(toRead);
            content.writeBuffer(respCtx.respAccumulator, toRead);
            content.markWriter();
            respCtx.respBytesRead += toRead;

            remaining = respCtx.respContentLength - respCtx.respBytesRead;
            if (remaining == 0) {
                dst.offerMessage(new DefaultLastHttpContent(content));
                respCtx.respDecoderState = HttpContext.RespDecoderState.DONE;
            } else {
                dst.offerMessage(new DefaultHttpContent(content));
            }
            return true;
        }

        return false;
    }

    /**
     * Reads body content until the connection is closed (HTTP/1.0 style).
     * Per RFC 7230 §3.3.3, point 7: the message body length is determined
     * by the number of octets received prior to the server closing the connection.
     */
    private boolean decodeVariableLengthContent(HttpContext respCtx, ProtoContext context, ProtoSndQueue<HttpObject> dst) {
        int readable = respCtx.respAccumulator.readableBytes();
        if (readable == 0) {
            return false;
        }

        int toRead = Math.min(readable, maxChunkSize);
        ByteBuf content = context.byteBufAllocator().buffer(toRead);
        content.writeBuffer(respCtx.respAccumulator, toRead);
        content.markWriter();

        dst.offerMessage(new DefaultHttpContent(content));
        return true;
    }

    private boolean decodeChunkSize(HttpContext respCtx, ProtoSndQueue<HttpObject> dst) {
        String line = fastReadLine(respCtx);
        if (line == null) {
            return false;
        }

        int semiIdx = line.indexOf(';');
        String sizeStr = semiIdx >= 0 ? line.substring(0, semiIdx).trim() : line.trim();

        if (sizeStr.isEmpty()) {
            throw new HttpBadRequestException("empty chunk size");
        }

        try {
            respCtx.respCurrentChunkSize = Integer.parseInt(sizeStr, 16);
        } catch (NumberFormatException e) {
            throw new HttpBadRequestException("invalid chunk size: " + sizeStr, e);
        }

        if (respCtx.respCurrentChunkSize < 0) {
            throw new HttpContentTooLargeException("negative chunk size: " + respCtx.respCurrentChunkSize);
        }

        if (respCtx.respCurrentChunkSize == 0) {
            respCtx.respDecoderState = HttpContext.RespDecoderState.READ_CHUNK_TRAILER;
        } else {
            respCtx.respDecoderState = HttpContext.RespDecoderState.READ_CHUNKED_CONTENT;
            respCtx.respBytesRead = 0;
        }
        return true;
    }

    private boolean decodeChunkedContent(HttpContext respCtx, ProtoContext context, ProtoSndQueue<HttpObject> dst) {
        int readable = respCtx.respAccumulator.readableBytes();
        if (readable == 0) {
            return false;
        }

        long remaining = respCtx.respCurrentChunkSize - respCtx.respBytesRead;
        int toRead = (int) Math.min(Math.min(remaining, readable), maxChunkSize);

        if (toRead > 0) {
            ByteBuf content = context.byteBufAllocator().buffer(toRead);
            content.writeBuffer(respCtx.respAccumulator, toRead);
            content.markWriter();
            respCtx.respBytesRead += toRead;

            remaining = respCtx.respCurrentChunkSize - respCtx.respBytesRead;
            if (remaining == 0) {
                respCtx.respDecoderState = HttpContext.RespDecoderState.READ_CHUNK_DELIMITER;
            }

            dst.offerMessage(new DefaultHttpContent(content));
            return true;
        }

        return false;
    }

    private boolean decodeChunkDelimiter(HttpContext respCtx) {
        String line = fastReadLine(respCtx);
        if (line == null) {
            return false;
        }
        respCtx.respDecoderState = HttpContext.RespDecoderState.READ_CHUNK_SIZE;
        return true;
    }

    private boolean decodeChunkTrailer(HttpContext respCtx, ProtoSndQueue<HttpObject> dst) {
        if (respCtx.respPendingTrailerHeaders == null) {
            respCtx.respPendingTrailerHeaders = new HttpHeaders();
        }

        String line;
        while ((line = fastReadLine(respCtx)) != null) {
            if (line.isEmpty()) {
                dst.offerMessage(new DefaultLastHttpContent(ByteBuf.EMPTY, respCtx.respPendingTrailerHeaders));
                respCtx.respPendingTrailerHeaders = null;
                respCtx.respDecoderState = HttpContext.RespDecoderState.DONE;
                return true;
            }

            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String name = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                if (!name.isEmpty()) {
                    respCtx.respPendingTrailerHeaders.add(name, value);
                }
            }
        }

        return false;
    }

    /**
     * Fast line reading: bulk-copies available bytes into a thread-local scratch buffer,
     * then scans the local byte array for '\n'. This avoids per-byte virtual method dispatch
     * through QueueByteBuf's component lookup chain (getByte → findComponent → delegate).
     */
    private String fastReadLine(HttpContext respCtx) {
        int available = respCtx.respAccumulator.readableBytes();
        if (available == 0) {
            return null;
        }

        int maxScan = Math.min(available, Math.min(maxHeaderSize + 2, SCAN_BUF_SIZE));
        byte[] buf = SCAN_BUF.get();
        respCtx.respAccumulator.getBytes(0, buf, 0, maxScan);

        // Scan the local array for '\n' (direct array access, no virtual dispatch)
        for (int i = 0; i < maxScan; i++) {
            if (buf[i] == '\n') {
                int lineLen = (i > 0 && buf[i - 1] == '\r') ? i - 1 : i;
                String result = (lineLen == 0) ? "" : new String(buf, 0, lineLen, StandardCharsets.US_ASCII);
                respCtx.respAccumulator.skipReadableBytes(i + 1);
                return result;
            }
        }

        return null;
    }

    @Override
    public void onClose(ProtoContext context) {
        HttpContext respCtx = context.context(HttpContext.class);
        if (respCtx != null) {
            if (respCtx.respAccumulator != null) {
                respCtx.respAccumulator.free();
                respCtx.respAccumulator = null;
            }
        }
    }
}
