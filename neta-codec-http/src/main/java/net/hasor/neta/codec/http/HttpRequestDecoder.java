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
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;

/**
 * Decodes raw bytes into HTTP request objects ({@link HttpObject}).
 * <p>
 * This decoder implements the HTTP/1.x message parsing state machine as defined in
 * <a href="https://tools.ietf.org/html/rfc7230">RFC 7230</a>.
 * <p>
 * The decoder emits the following sequence of {@link HttpObject}s for each request:
 * <ol>
 *   <li>{@link DefaultHttpRequest} - the request line and headers</li>
 *   <li>Zero or more {@link DefaultHttpContent} - body chunks</li>
 *   <li>{@link DefaultLastHttpContent} - marks the end of the request body</li>
 * </ol>
 * <p>
 * Supported transfer modes:
 * <ul>
 *   <li>Content-Length based body (RFC 7230 §3.3.2)</li>
 *   <li>Chunked transfer encoding (RFC 7230 §4.1)</li>
 *   <li>No body (for methods like GET, HEAD, DELETE, etc.)</li>
 * </ul>
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLastDecoder("http-request", new HttpRequestDecoder());
 * </pre>
 */
public class HttpRequestDecoder implements ProtoHandler<ByteBuf, HttpObject> {
    private static final Logger              logger                          = Logger.getLogger(HttpRequestDecoder.class);
    /** Default maximum length of the initial line (request-line). */
    private static final int                 DEFAULT_MAX_INITIAL_LINE_LENGTH = 4096;
    /** Default maximum total size of all headers. */
    private static final int                 DEFAULT_MAX_HEADER_SIZE         = 8192;
    /** Default maximum chunk size for content. */
    private static final int                 DEFAULT_MAX_CHUNK_SIZE          = 8192;
    private static final int                 SCAN_BUF_SIZE                   = 8192;
    private static final ThreadLocal<byte[]> SCAN_BUF                        = ThreadLocal.withInitial(() -> new byte[SCAN_BUF_SIZE]);
    private final        int                 maxInitialLineLength;
    private final        int                 maxHeaderSize;
    private final        int                 maxChunkSize;

    /** Creates a decoder with default limits. */
    public HttpRequestDecoder() {
        this(DEFAULT_MAX_INITIAL_LINE_LENGTH, DEFAULT_MAX_HEADER_SIZE, DEFAULT_MAX_CHUNK_SIZE);
    }

    /**
     * Creates a decoder with the specified limits.
     * @param maxInitialLineLength maximum length of the request line
     * @param maxHeaderSize maximum total size of all headers
     * @param maxChunkSize maximum chunk size for content delivery
     */
    public HttpRequestDecoder(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
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
        HttpContext reqCtx = context.context(HttpContext.class);

        // Create a zero-copy view over all queued ByteBuf data
        if (reqCtx.reqAccumulator != null) {
            reqCtx.reqAccumulator.free();
        }
        reqCtx.reqAccumulator = ByteBufUtils.queueBuffer(src);

        // Try to decode as much as possible
        boolean needMoreData = false;

        while (!needMoreData) {
            switch (reqCtx.reqDecoderState) {
                case READ_INITIAL:
                    needMoreData = !decodeInitialLine(reqCtx, context, dst);
                    break;
                case READ_HEADER:
                    needMoreData = !decodeHeaders(reqCtx, dst);
                    break;
                case READ_FIXED_LENGTH_CONTENT:
                    needMoreData = !decodeFixedLengthContent(reqCtx, context, dst);
                    break;
                case READ_CHUNK_SIZE:
                    needMoreData = !decodeChunkSize(reqCtx, dst);
                    break;
                case READ_CHUNKED_CONTENT:
                    needMoreData = !decodeChunkedContent(reqCtx, context, dst);
                    break;
                case READ_CHUNK_DELIMITER:
                    needMoreData = !decodeChunkDelimiter(reqCtx);
                    break;
                case READ_CHUNK_TRAILER:
                    needMoreData = !decodeChunkTrailer(reqCtx, dst);
                    break;
                case DONE:
                    // Reset for next request (keep-alive)
                    reqCtx.resetReqDecoder();
                    needMoreData = reqCtx.reqAccumulator.readableBytes() == 0;
                    break;
                default:
                    needMoreData = true;
                    break;
            }
        }

        // Consume fully-read ByteBuf messages from the queue
        reqCtx.reqAccumulator.markReader();

        return ProtoStatus.Next;
    }

    /**
     * Parses the request-line: method SP request-target SP HTTP-version CRLF
     * (RFC 7230 §3.1.1)
     */
    private boolean decodeInitialLine(HttpContext reqCtx, ProtoContext context, ProtoSndQueue<HttpObject> dst) {
        String line = fastReadLine(reqCtx);
        if (line == null) {
            return false;
        }

        // Skip empty lines before request (RFC 7230 §3.5: robustness)
        if (line.isEmpty()) {
            return true; // retry, there might be more data
        }

        if (line.length() > maxInitialLineLength) {
            throw new HttpInitialLineTooLongException("request line too long: " + line.length() + " > " + maxInitialLineLength, maxInitialLineLength, line.length());
        }

        // Parse: METHOD SP URI SP VERSION
        int firstSpace = line.indexOf(' ');
        if (firstSpace < 0) {
            throw new HttpBadRequestException("invalid request line: " + line);
        }
        int secondSpace = line.indexOf(' ', firstSpace + 1);
        if (secondSpace < 0) {
            throw new HttpBadRequestException("invalid request line: " + line);
        }

        String methodStr = line.substring(0, firstSpace);
        String uri = line.substring(firstSpace + 1, secondSpace);
        String versionStr = line.substring(secondSpace + 1);

        HttpMethod method = HttpMethod.valueOf(methodStr);
        HttpVersion version = HttpVersion.valueOf(versionStr);

        reqCtx.reqCurrentRequest = new DefaultHttpRequest(version, method, uri);
        reqCtx.reqDecoderState = HttpContext.ReqDecoderState.READ_HEADER;
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[HTTP-REQ] channel=" + channelID + " " + methodStr + " " + uri + " " + versionStr);
        }
        return true;
    }

    /**
     * Parses header fields until an empty line is encountered.
     * (RFC 7230 §3.2)
     * <pre>
     *   header-field = field-name ":" OWS field-value OWS
     * </pre>
     */
    private boolean decodeHeaders(HttpContext reqCtx, ProtoSndQueue<HttpObject> dst) {
        HttpHeaders headers = reqCtx.reqCurrentRequest.headers();

        String line;
        while ((line = fastReadLine(reqCtx)) != null) {

            // Empty line marks end of headers
            if (line.isEmpty()) {
                // Determine transfer mode
                determineTransferMode(reqCtx, headers);

                // Emit the request with headers
                dst.offerMessage(reqCtx.reqCurrentRequest);

                // Transition to body reading state
                if (reqCtx.reqChunked) {
                    reqCtx.reqDecoderState = HttpContext.ReqDecoderState.READ_CHUNK_SIZE;
                } else if (reqCtx.reqContentLength > 0) {
                    reqCtx.reqDecoderState = HttpContext.ReqDecoderState.READ_FIXED_LENGTH_CONTENT;
                    reqCtx.reqBytesRead = 0;
                } else {
                    // No body - emit empty last content immediately
                    dst.offerMessage(DefaultLastHttpContent.EMPTY_LAST_CONTENT);
                    reqCtx.reqDecoderState = HttpContext.ReqDecoderState.DONE;
                }
                return true;
            }

            reqCtx.reqHeaderBytes += line.length() + 2; // +2 for CRLF
            if (reqCtx.reqHeaderBytes > maxHeaderSize) {
                throw new HttpHeaderTooLargeException("HTTP headers too large: " + reqCtx.reqHeaderBytes + " > " + maxHeaderSize, maxHeaderSize, reqCtx.reqHeaderBytes);
            }

            // Handle header line folding (obs-fold, RFC 7230 §3.2.4)
            // obs-fold = CRLF 1*( SP / HTAB )
            if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && !headers.isEmpty()) {
                // Append folded continuation to the last added header value (replace leading WS with SP)
                String continuation = ' ' + line.trim();
                if (reqCtx.reqLastHeaderName != null) {
                    String existing = headers.get(reqCtx.reqLastHeaderName);
                    headers.set(reqCtx.reqLastHeaderName, existing + continuation);
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
            reqCtx.reqLastHeaderName = name;
        }

        // Not enough data yet; need to restore what we read.
        // But since we committed reads via readLine, we cannot undo.
        // The accumulator approach means we'll wait for more data.
        return false;
    }

    /**
     * Determines the transfer encoding mode from headers.
     * Per RFC 7230 §3.3.3:
     * - If Transfer-Encoding contains "chunked", use chunked
     * - Otherwise, use Content-Length if present
     * - Otherwise, no body
     */
    private void determineTransferMode(HttpContext reqCtx, HttpHeaders headers) {
        reqCtx.reqChunked = false;
        reqCtx.reqContentLength = -1;

        // Check Transfer-Encoding
        String te = headers.get(HttpHeaderNames.TRANSFER_ENCODING);
        if (StringUtils.containsIgnoreCase(te, HttpHeaderValues.CHUNKED)) {
            reqCtx.reqChunked = true;
            return;
        }

        // Check Content-Length
        String cl = headers.get(HttpHeaderNames.CONTENT_LENGTH);
        if (StringUtils.isNotBlank(cl)) {
            try {
                reqCtx.reqContentLength = Long.parseLong(cl.trim());
                if (reqCtx.reqContentLength < 0) {
                    throw new HttpContentTooLargeException("negative Content-Length: " + reqCtx.reqContentLength);
                }
            } catch (NumberFormatException e) {
                throw new HttpBadRequestException("invalid Content-Length: " + cl, e);
            }
        }
    }

    /**
     * Reads fixed-length body content based on Content-Length header.
     * (RFC 7230 §3.3.2)
     */
    private boolean decodeFixedLengthContent(HttpContext reqCtx, ProtoContext context, ProtoSndQueue<HttpObject> dst) {
        int readable = reqCtx.reqAccumulator.readableBytes();
        if (readable == 0) {
            return false;
        }

        long remaining = reqCtx.reqContentLength - reqCtx.reqBytesRead;
        int toRead = (int) Math.min(Math.min(remaining, readable), maxChunkSize);

        if (toRead > 0) {
            ByteBuf content = context.byteBufAllocator().buffer(toRead);
            content.writeBuffer(reqCtx.reqAccumulator, toRead);
            content.markWriter();
            reqCtx.reqBytesRead += toRead;

            remaining = reqCtx.reqContentLength - reqCtx.reqBytesRead;
            if (remaining == 0) {
                // Last chunk
                dst.offerMessage(new DefaultLastHttpContent(content));
                reqCtx.reqDecoderState = HttpContext.ReqDecoderState.DONE;
            } else {
                dst.offerMessage(new DefaultHttpContent(content));
            }
            return true;
        }

        return false;
    }

    /**
     * Reads the chunk-size line in chunked transfer encoding.
     * (RFC 7230 §4.1)
     * <pre>
     *   chunk = chunk-size [ chunk-ext ] CRLF chunk-data CRLF
     *   chunk-size = 1*HEXDIG
     * </pre>
     */
    private boolean decodeChunkSize(HttpContext reqCtx, ProtoSndQueue<HttpObject> dst) {
        String line = fastReadLine(reqCtx);
        if (line == null) {
            return false;
        }

        // Remove chunk extensions (";...") if present
        int semiIdx = line.indexOf(';');
        String sizeStr = semiIdx >= 0 ? line.substring(0, semiIdx).trim() : line.trim();

        if (sizeStr.isEmpty()) {
            throw new HttpBadRequestException("empty chunk size");
        }

        try {
            reqCtx.reqCurrentChunkSize = Integer.parseInt(sizeStr, 16);
        } catch (NumberFormatException e) {
            throw new HttpBadRequestException("invalid chunk size: " + sizeStr, e);
        }

        if (reqCtx.reqCurrentChunkSize < 0) {
            throw new HttpBadRequestException("negative chunk size: " + reqCtx.reqCurrentChunkSize);
        }

        if (reqCtx.reqCurrentChunkSize == 0) {
            // Last chunk - read trailers
            reqCtx.reqDecoderState = HttpContext.ReqDecoderState.READ_CHUNK_TRAILER;
        } else {
            reqCtx.reqDecoderState = HttpContext.ReqDecoderState.READ_CHUNKED_CONTENT;
            reqCtx.reqBytesRead = 0;
        }
        return true;
    }

    /**
     * Reads chunk-data for the current chunk.
     * (RFC 7230 §4.1)
     */
    private boolean decodeChunkedContent(HttpContext reqCtx, ProtoContext context, ProtoSndQueue<HttpObject> dst) {
        int readable = reqCtx.reqAccumulator.readableBytes();
        if (readable == 0) {
            return false;
        }

        long remaining = reqCtx.reqCurrentChunkSize - reqCtx.reqBytesRead;
        int toRead = (int) Math.min(Math.min(remaining, readable), maxChunkSize);

        if (toRead > 0) {
            ByteBuf content = context.byteBufAllocator().buffer(toRead);
            content.writeBuffer(reqCtx.reqAccumulator, toRead);
            content.markWriter();
            reqCtx.reqBytesRead += toRead;

            remaining = reqCtx.reqCurrentChunkSize - reqCtx.reqBytesRead;
            if (remaining == 0) {
                // Finished this chunk, need to read the trailing CRLF
                reqCtx.reqDecoderState = HttpContext.ReqDecoderState.READ_CHUNK_DELIMITER;
            }

            dst.offerMessage(new DefaultHttpContent(content));
            return true;
        }

        return false;
    }

    /**
     * Reads the CRLF delimiter after chunk-data.
     */
    private boolean decodeChunkDelimiter(HttpContext reqCtx) {
        String line = fastReadLine(reqCtx);
        if (line == null) {
            return false;
        }
        // Should be empty (just CRLF)
        // Transition back to reading next chunk size
        reqCtx.reqDecoderState = HttpContext.ReqDecoderState.READ_CHUNK_SIZE;
        return true;
    }

    /**
     * Reads trailing headers after the last chunk (chunk-size = 0).
     * (RFC 7230 §4.1.2)
     * <pre>
     *   trailer-part = *( header-field CRLF )
     * </pre>
     */
    private boolean decodeChunkTrailer(HttpContext reqCtx, ProtoSndQueue<HttpObject> dst) {
        // Preserve partial trailer state across partial reads
        if (reqCtx.reqPendingTrailerHeaders == null) {
            reqCtx.reqPendingTrailerHeaders = new HttpHeaders();
        }

        String line;
        while ((line = fastReadLine(reqCtx)) != null) {
            // Empty line marks end of trailers
            if (line.isEmpty()) {
                dst.offerMessage(new DefaultLastHttpContent(ByteBuf.EMPTY, reqCtx.reqPendingTrailerHeaders));
                reqCtx.reqPendingTrailerHeaders = null;
                reqCtx.reqDecoderState = HttpContext.ReqDecoderState.DONE;
                return true;
            }

            // Parse trailer header
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String name = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                if (!name.isEmpty()) {
                    reqCtx.reqPendingTrailerHeaders.add(name, value);
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
    private String fastReadLine(HttpContext reqCtx) {
        int available = reqCtx.reqAccumulator.readableBytes();
        if (available == 0) {
            return null;
        }

        int maxScan = Math.min(available, Math.min(maxHeaderSize + 2, SCAN_BUF_SIZE));
        byte[] buf = SCAN_BUF.get();
        reqCtx.reqAccumulator.getBytes(0, buf, 0, maxScan);

        // Scan the local array for '\n' (direct array access, no virtual dispatch)
        for (int i = 0; i < maxScan; i++) {
            if (buf[i] == '\n') {
                int lineLen = (i > 0 && buf[i - 1] == '\r') ? i - 1 : i;
                String result = (lineLen == 0) ? "" : new String(buf, 0, lineLen, StandardCharsets.US_ASCII);
                reqCtx.reqAccumulator.skipReadableBytes(i + 1);
                return result;
            }
        }

        return null;
    }

    @Override
    public void onClose(ProtoContext context) {
        HttpContext reqCtx = context.context(HttpContext.class);
        if (reqCtx != null) {
            if (reqCtx.reqAccumulator != null) {
                reqCtx.reqAccumulator.free();
                reqCtx.reqAccumulator = null;
            }
        }
    }
}