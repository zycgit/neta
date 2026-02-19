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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.constant.HttpHeaderNames;
import net.hasor.neta.codec.http.constant.HttpHeaderValues;
import net.hasor.neta.codec.http.constant.HttpMethod;
import net.hasor.neta.codec.http.constant.HttpVersion;

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
    // Decoder state
    private              State               currentState                    = State.READ_INITIAL;
    private              ByteBuf             accumulator;
    // Current message being decoded
    private              HttpRequest         currentRequest;
    private              long                contentLength                   = -1;
    private              long                bytesRead                       = 0;
    private              boolean             chunked                         = false;
    private              int                 currentChunkSize                = 0;
    // For chunked trailer accumulation across partial reads
    private              HttpHeaders         pendingTrailerHeaders;
    // Accumulated header bytes across partial reads (for maxHeaderSize enforcement)
    private              int                 headerBytes                     = 0;
    // Track last header name for obs-fold continuation lines
    private              String              lastHeaderName;

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
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        // Create a zero-copy view over all queued ByteBuf data
        if (this.accumulator != null) {
            this.accumulator.free();
        }
        this.accumulator = ByteBufUtils.queueBuffer(src);

        // Try to decode as much as possible
        // boolean decoded = false; // removed unused variable
        boolean needMoreData = false;

        while (!needMoreData) {
            switch (currentState) {
                case READ_INITIAL:
                    needMoreData = !decodeInitialLine(dst);
                    break;
                case READ_HEADER:
                    needMoreData = !decodeHeaders(dst);
                    break;
                case READ_FIXED_LENGTH_CONTENT:
                    needMoreData = !decodeFixedLengthContent(context, dst);
                    break;
                case READ_CHUNK_SIZE:
                    needMoreData = !decodeChunkSize(dst);
                    break;
                case READ_CHUNKED_CONTENT:
                    needMoreData = !decodeChunkedContent(context, dst);
                    break;
                case READ_CHUNK_DELIMITER:
                    needMoreData = !decodeChunkDelimiter();
                    break;
                case READ_CHUNK_TRAILER:
                    needMoreData = !decodeChunkTrailer(dst);
                    break;
                case DONE:
                    // Reset for next request (keep-alive)
                    resetDecoder();
                    needMoreData = accumulator.readableBytes() == 0;
                    break;
                default:
                    needMoreData = true;
                    break;
            }
        }

        // Consume fully-read ByteBuf messages from the queue
        accumulator.markReader();

        return ProtoStatus.Next;
    }

    /**
     * Parses the request-line: method SP request-target SP HTTP-version CRLF
     * (RFC 7230 §3.1.1)
     */
    private boolean decodeInitialLine(ProtoSndQueue<HttpObject> dst) {
        String line = fastReadLine();
        if (line == null) {
            return false;
        }

        // Skip empty lines before request (RFC 7230 §3.5: robustness)
        if (line.isEmpty()) {
            return true; // retry, there might be more data
        }

        if (line.length() > maxInitialLineLength) {
            throw new HttpInitialLineTooLongException("request line too long: " + line.length() + " > " + maxInitialLineLength);
        }

        // Parse: METHOD SP URI SP VERSION
        int firstSpace = line.indexOf(' ');
        if (firstSpace < 0) {
            throw new HttpMalformedRequestException("invalid request line: " + line);
        }
        int secondSpace = line.indexOf(' ', firstSpace + 1);
        if (secondSpace < 0) {
            throw new HttpMalformedRequestException("invalid request line: " + line);
        }

        String methodStr = line.substring(0, firstSpace);
        String uri = line.substring(firstSpace + 1, secondSpace);
        String versionStr = line.substring(secondSpace + 1);

        HttpMethod method = HttpMethod.valueOf(methodStr);
        HttpVersion version = HttpVersion.valueOf(versionStr);

        currentRequest = new DefaultHttpRequest(version, method, uri);
        currentState = State.READ_HEADER;
        return true;
    }

    /**
     * Parses header fields until an empty line is encountered.
     * (RFC 7230 §3.2)
     * <pre>
     *   header-field = field-name ":" OWS field-value OWS
     * </pre>
     */
    private boolean decodeHeaders(ProtoSndQueue<HttpObject> dst) {
        HttpHeaders headers = currentRequest.headers();

        String line;
        while ((line = fastReadLine()) != null) {

            // Empty line marks end of headers
            if (line.isEmpty()) {
                // Determine transfer mode
                determineTransferMode(headers);

                // Emit the request with headers
                dst.offerMessage(currentRequest);

                // Transition to body reading state
                if (chunked) {
                    currentState = State.READ_CHUNK_SIZE;
                } else if (contentLength > 0) {
                    currentState = State.READ_FIXED_LENGTH_CONTENT;
                    bytesRead = 0;
                } else {
                    // No body - emit empty last content immediately
                    dst.offerMessage(DefaultLastHttpContent.EMPTY_LAST_CONTENT);
                    currentState = State.DONE;
                }
                return true;
            }

            headerBytes += line.length() + 2; // +2 for CRLF
            if (headerBytes > maxHeaderSize) {
                throw new HttpHeaderTooLargeException("HTTP headers too large: " + headerBytes + " > " + maxHeaderSize);
            }

            // Handle header line folding (obs-fold, RFC 7230 §3.2.4)
            // obs-fold = CRLF 1*( SP / HTAB )
            if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && !headers.isEmpty()) {
                // Append folded continuation to the last added header value (replace leading WS with SP)
                String continuation = ' ' + line.trim();
                if (lastHeaderName != null) {
                    String existing = headers.get(lastHeaderName);
                    headers.set(lastHeaderName, existing + continuation);
                }
                continue;
            }

            // Parse header: name ":" value
            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) {
                throw new HttpMalformedRequestException("invalid header line (no colon): " + line);
            }

            String name = line.substring(0, colonIdx).trim();
            String value = line.substring(colonIdx + 1).trim();

            if (name.isEmpty()) {
                throw new HttpMalformedRequestException("empty header name");
            }

            headers.add(name, value);
            lastHeaderName = name;
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
    private void determineTransferMode(HttpHeaders headers) {
        chunked = false;
        contentLength = -1;

        // Check Transfer-Encoding
        String te = headers.get(HttpHeaderNames.TRANSFER_ENCODING);
        if (te != null && HttpHeaders.containsIgnoreCase(te, HttpHeaderValues.CHUNKED)) {
            chunked = true;
            return;
        }

        // Check Content-Length
        String cl = headers.get(HttpHeaderNames.CONTENT_LENGTH);
        if (cl != null) {
            try {
                contentLength = Long.parseLong(cl.trim());
                if (contentLength < 0) {
                    throw new HttpContentTooLargeException("negative Content-Length: " + contentLength);
                }
            } catch (NumberFormatException e) {
                throw new HttpMalformedRequestException("invalid Content-Length: " + cl, e);
            }
        }
    }

    /**
     * Reads fixed-length body content based on Content-Length header.
     * (RFC 7230 §3.3.2)
     */
    private boolean decodeFixedLengthContent(ProtoContext context, ProtoSndQueue<HttpObject> dst) {
        int readable = accumulator.readableBytes();
        if (readable == 0) {
            return false;
        }

        long remaining = contentLength - bytesRead;
        int toRead = (int) Math.min(Math.min(remaining, readable), maxChunkSize);

        if (toRead > 0) {
            ByteBuf content = context.byteBufAllocator().buffer(toRead);
            content.writeBuffer(accumulator, toRead);
            content.markWriter();
            bytesRead += toRead;

            remaining = contentLength - bytesRead;
            if (remaining == 0) {
                // Last chunk
                dst.offerMessage(new DefaultLastHttpContent(content));
                currentState = State.DONE;
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
    private boolean decodeChunkSize(ProtoSndQueue<HttpObject> dst) {
        String line = fastReadLine();
        if (line == null) {
            return false;
        }

        // Remove chunk extensions (";...") if present
        int semiIdx = line.indexOf(';');
        String sizeStr = semiIdx >= 0 ? line.substring(0, semiIdx).trim() : line.trim();

        if (sizeStr.isEmpty()) {
            throw new HttpMalformedRequestException("empty chunk size");
        }

        try {
            currentChunkSize = Integer.parseInt(sizeStr, 16);
        } catch (NumberFormatException e) {
            throw new HttpMalformedRequestException("invalid chunk size: " + sizeStr, e);
        }

        if (currentChunkSize < 0) {
            throw new IllegalStateException("negative chunk size: " + currentChunkSize);
        }

        if (currentChunkSize == 0) {
            // Last chunk - read trailers
            currentState = State.READ_CHUNK_TRAILER;
        } else {
            currentState = State.READ_CHUNKED_CONTENT;
            bytesRead = 0;
        }
        return true;
    }

    /**
     * Reads chunk-data for the current chunk.
     * (RFC 7230 §4.1)
     */
    private boolean decodeChunkedContent(ProtoContext context, ProtoSndQueue<HttpObject> dst) {
        int readable = accumulator.readableBytes();
        if (readable == 0) {
            return false;
        }

        long remaining = currentChunkSize - bytesRead;
        int toRead = (int) Math.min(Math.min(remaining, readable), maxChunkSize);

        if (toRead > 0) {
            ByteBuf content = context.byteBufAllocator().buffer(toRead);
            content.writeBuffer(accumulator, toRead);
            content.markWriter();
            bytesRead += toRead;

            remaining = currentChunkSize - bytesRead;
            if (remaining == 0) {
                // Finished this chunk, need to read the trailing CRLF
                currentState = State.READ_CHUNK_DELIMITER;
            }

            dst.offerMessage(new DefaultHttpContent(content));
            return true;
        }

        return false;
    }

    /**
     * Reads the CRLF delimiter after chunk-data.
     */
    private boolean decodeChunkDelimiter() {
        String line = fastReadLine();
        if (line == null) {
            return false;
        }
        // Should be empty (just CRLF)
        // Transition back to reading next chunk size
        currentState = State.READ_CHUNK_SIZE;
        return true;
    }

    /**
     * Reads trailing headers after the last chunk (chunk-size = 0).
     * (RFC 7230 §4.1.2)
     * <pre>
     *   trailer-part = *( header-field CRLF )
     * </pre>
     */
    private boolean decodeChunkTrailer(ProtoSndQueue<HttpObject> dst) {
        // Preserve partial trailer state across partial reads
        if (pendingTrailerHeaders == null) {
            pendingTrailerHeaders = new HttpHeaders();
        }
        HttpHeaders trailingHeaders = pendingTrailerHeaders;

        String line;
        while ((line = fastReadLine()) != null) {
            // Empty line marks end of trailers
            if (line.isEmpty()) {
                dst.offerMessage(new DefaultLastHttpContent(ByteBuf.EMPTY, trailingHeaders));
                pendingTrailerHeaders = null;
                currentState = State.DONE;
                return true;
            }

            // Parse trailer header
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String name = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                if (!name.isEmpty()) {
                    trailingHeaders.add(name, value);
                }
            }
        }

        return false;
    }

    /**
     * Resets the decoder state for the next HTTP message (keep-alive).
     */
    private void resetDecoder() {
        currentState = State.READ_INITIAL;
        currentRequest = null;
        contentLength = -1;
        bytesRead = 0;
        chunked = false;
        currentChunkSize = 0;
        pendingTrailerHeaders = null;
        headerBytes = 0;
        lastHeaderName = null;
    }

    /**
     * Fast line reading: bulk-copies available bytes into a thread-local scratch buffer,
     * then scans the local byte array for '\n'. This avoids per-byte virtual method dispatch
     * through QueueByteBuf's component lookup chain (getByte → findComponent → delegate).
     */
    private String fastReadLine() {
        int available = accumulator.readableBytes();
        if (available == 0) {
            return null;
        }

        int maxScan = Math.min(available, Math.min(maxHeaderSize + 2, SCAN_BUF_SIZE));
        byte[] buf = SCAN_BUF.get();
        accumulator.getBytes(0, buf, 0, maxScan);

        // Scan the local array for '\n' (direct array access, no virtual dispatch)
        for (int i = 0; i < maxScan; i++) {
            if (buf[i] == '\n') {
                int lineLen = (i > 0 && buf[i - 1] == '\r') ? i - 1 : i;
                String result = (lineLen == 0) ? "" : new String(buf, 0, lineLen, StandardCharsets.US_ASCII);
                accumulator.skipReadableBytes(i + 1);
                return result;
            }
        }

        return null;
    }

    @Override
    public void onClose(ProtoContext context) {
        if (accumulator != null) {
            accumulator.free();
            accumulator = null;
        }
    }

    private enum State {
        READ_INITIAL,
        READ_HEADER,
        READ_FIXED_LENGTH_CONTENT,
        READ_CHUNK_SIZE,
        READ_CHUNKED_CONTENT,
        READ_CHUNK_DELIMITER,
        READ_CHUNK_TRAILER,
        DONE
    }
}
