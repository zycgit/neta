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
import net.hasor.neta.codec.http.constant.HttpStatus;
import net.hasor.neta.codec.http.constant.HttpVersion;

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
    private static final int          DEFAULT_MAX_INITIAL_LINE_LENGTH = 4096;
    private static final int          DEFAULT_MAX_HEADER_SIZE         = 8192;
    private static final int          DEFAULT_MAX_CHUNK_SIZE          = 8192;
    //
    private final        int          maxInitialLineLength;
    private final        int          maxHeaderSize;
    private final        int          maxChunkSize;
    // Decoder state
    private              State        currentState                    = State.READ_INITIAL;
    private              ByteBuf      accumulator;
    // Current message being decoded
    private              HttpResponse currentResponse;
    private              long         contentLength                   = -1;
    private              long         bytesRead                       = 0;
    private              boolean      chunked                         = false;
    private              int          currentChunkSize                = 0;
    // For chunked trailer accumulation across partial reads
    private              HttpHeaders  pendingTrailerHeaders;

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
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        // Accumulate all available data
        if (accumulator == null) {
            accumulator = context.byteBufAllocator().buffer(1024, Integer.MAX_VALUE);
        }
        while (src.hasMore()) {
            ByteBuf chunk = src.takeMessage();
            if (chunk != null && chunk.readableBytes() > 0) {
                accumulator.writeBuffer(chunk, chunk.readableBytes());
            }
        }
        accumulator.markWriter();

        // Try to decode as much as possible
        boolean needMoreData = false;

        while (!needMoreData) {
            switch (currentState) {
                case READ_INITIAL:
                    needMoreData = !decodeStatusLine(dst);
                    break;

                case READ_HEADER:
                    needMoreData = !decodeHeaders(dst);
                    break;

                case READ_FIXED_LENGTH_CONTENT:
                    needMoreData = !decodeFixedLengthContent(context, dst);
                    break;

                case READ_VARIABLE_LENGTH_CONTENT:
                    needMoreData = !decodeVariableLengthContent(context, dst);
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
                    resetDecoder();
                    needMoreData = accumulator.readableBytes() == 0;
                    break;

                default:
                    needMoreData = true;
                    break;
            }
        }

        // Compact the accumulator
        if (accumulator.readableBytes() == 0) {
            accumulator.clear();
        } else {
            accumulator.discardReadBytes();
        }

        return ProtoStatus.Next;
    }

    /**
     * Parses the status-line: HTTP-version SP status-code SP reason-phrase CRLF
     * (RFC 7230 §3.1.2)
     */
    private boolean decodeStatusLine(ProtoSndQueue<HttpObject> dst) {
        if (!accumulator.hasLine()) {
            return false;
        }

        String line = accumulator.readLine(StandardCharsets.US_ASCII);
        if (line == null) {
            return false;
        }

        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }

        // Skip empty lines (robustness, RFC 7230 §3.5)
        if (line.isEmpty()) {
            return true;
        }

        if (line.length() > maxInitialLineLength) {
            throw new IllegalStateException("status line too long: " + line.length() + " > " + maxInitialLineLength);
        }

        // Parse: VERSION SP STATUS SP REASON
        int firstSpace = line.indexOf(' ');
        if (firstSpace < 0) {
            throw new IllegalStateException("invalid status line: " + line);
        }

        String versionStr = line.substring(0, firstSpace);
        HttpVersion version = HttpVersion.valueOf(versionStr);

        String remaining = line.substring(firstSpace + 1);
        int secondSpace = remaining.indexOf(' ');

        int statusCode;
        String reasonPhrase;

        if (secondSpace < 0) {
            // No reason phrase (allowed per RFC 7230)
            try {
                statusCode = Integer.parseInt(remaining.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("invalid status code: " + remaining, e);
            }
            reasonPhrase = "";
        } else {
            try {
                statusCode = Integer.parseInt(remaining.substring(0, secondSpace).trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("invalid status code: " + remaining.substring(0, secondSpace), e);
            }
            reasonPhrase = remaining.substring(secondSpace + 1);
        }

        HttpStatus status = HttpStatus.valueOf(statusCode, reasonPhrase);
        currentResponse = new DefaultHttpResponse(version, status);
        currentState = State.READ_HEADER;
        return true;
    }

    /**
     * Parses header fields until an empty line is encountered.
     * (RFC 7230 §3.2)
     */
    private boolean decodeHeaders(ProtoSndQueue<HttpObject> dst) {
        HttpHeaders headers = currentResponse.headers();
        int headerBytes = 0;

        while (accumulator.hasLine()) {
            String line = accumulator.readLine(StandardCharsets.US_ASCII);
            if (line == null) {
                return false;
            }

            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }

            // Empty line marks end of headers
            if (line.isEmpty()) {
                determineTransferMode(headers);
                dst.offerMessage(currentResponse);

                // Determine body state based on response status and transfer mode
                int statusCode = currentResponse.status().code();

                // RFC 7230 §3.3.3: responses with certain status codes MUST NOT have a body
                if (statusCode == 204 || statusCode == 304 || (statusCode >= 100 && statusCode < 200)) {
                    dst.offerMessage(DefaultLastHttpContent.EMPTY_LAST_CONTENT);
                    currentState = State.DONE;
                } else if (chunked) {
                    currentState = State.READ_CHUNK_SIZE;
                } else if (contentLength >= 0) {
                    if (contentLength == 0) {
                        dst.offerMessage(DefaultLastHttpContent.EMPTY_LAST_CONTENT);
                        currentState = State.DONE;
                    } else {
                        currentState = State.READ_FIXED_LENGTH_CONTENT;
                        bytesRead = 0;
                    }
                } else {
                    // No Content-Length and not chunked:
                    // For HTTP/1.0 or Connection: close, read until connection closed
                    // For HTTP/1.1 without body indication, assume no body
                    HttpVersion version = currentResponse.protocolVersion();
                    String connection = headers.get(HttpHeaderNames.CONNECTION);
                    boolean isClose = HttpVersion.HTTP_1_0.equals(version) || (connection != null && connection.toLowerCase().contains(HttpHeaderValues.CLOSE));

                    if (isClose) {
                        currentState = State.READ_VARIABLE_LENGTH_CONTENT;
                    } else {
                        dst.offerMessage(DefaultLastHttpContent.EMPTY_LAST_CONTENT);
                        currentState = State.DONE;
                    }
                }
                return true;
            }

            headerBytes += line.length() + 2; // +2 for CRLF
            if (headerBytes > maxHeaderSize) {
                throw new IllegalStateException("HTTP headers too large: " + headerBytes + " > " + maxHeaderSize);
            }

            // Handle obs-fold (RFC 7230 §3.2.4)
            if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && !headers.isEmpty()) {
                // Append folded continuation to the last added header value
                String continuation = ' ' + line.trim();
                String lastKey = null;
                for (Map.Entry<String, String> e : headers) {
                    lastKey = e.getKey();
                }
                if (lastKey != null) {
                    String existing = headers.get(lastKey);
                    headers.set(lastKey, existing + continuation);
                }
                continue;
            }

            // Parse header: name ":" value
            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) {
                throw new IllegalStateException("invalid header line (no colon): " + line);
            }

            String name = line.substring(0, colonIdx).trim();
            String value = line.substring(colonIdx + 1).trim();

            if (name.isEmpty()) {
                throw new IllegalStateException("empty header name");
            }

            headers.add(name, value);
        }

        return false;
    }

    private void determineTransferMode(HttpHeaders headers) {
        chunked = false;
        contentLength = -1;

        String te = headers.get(HttpHeaderNames.TRANSFER_ENCODING);
        if (te != null && te.toLowerCase().contains(HttpHeaderValues.CHUNKED)) {
            chunked = true;
            return;
        }

        String cl = headers.get(HttpHeaderNames.CONTENT_LENGTH);
        if (cl != null) {
            try {
                contentLength = Long.parseLong(cl.trim());
                if (contentLength < 0) {
                    throw new IllegalStateException("negative Content-Length: " + contentLength);
                }
            } catch (NumberFormatException e) {
                throw new IllegalStateException("invalid Content-Length: " + cl, e);
            }
        }
    }

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
                dst.offerMessage(new DefaultLastHttpContent(content));
                currentState = State.DONE;
            } else {
                dst.offerMessage(new DefaultHttpContent(content));
            }
            return true;
        }

        dst.offerMessage(DefaultLastHttpContent.EMPTY_LAST_CONTENT);
        currentState = State.DONE;
        return true;
    }

    /**
     * Reads body content until the connection is closed (HTTP/1.0 style).
     * Per RFC 7230 §3.3.3, point 7: the message body length is determined
     * by the number of octets received prior to the server closing the connection.
     */
    private boolean decodeVariableLengthContent(ProtoContext context, ProtoSndQueue<HttpObject> dst) {
        int readable = accumulator.readableBytes();
        if (readable == 0) {
            return false;
        }

        int toRead = Math.min(readable, maxChunkSize);
        ByteBuf content = context.byteBufAllocator().buffer(toRead);
        content.writeBuffer(accumulator, toRead);
        content.markWriter();

        dst.offerMessage(new DefaultHttpContent(content));
        return true;
    }

    private boolean decodeChunkSize(ProtoSndQueue<HttpObject> dst) {
        if (!accumulator.hasLine()) {
            return false;
        }

        String line = accumulator.readLine(StandardCharsets.US_ASCII);
        if (line == null) {
            return false;
        }

        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }

        int semiIdx = line.indexOf(';');
        String sizeStr = semiIdx >= 0 ? line.substring(0, semiIdx).trim() : line.trim();

        if (sizeStr.isEmpty()) {
            throw new IllegalStateException("empty chunk size");
        }

        try {
            currentChunkSize = Integer.parseInt(sizeStr, 16);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("invalid chunk size: " + sizeStr, e);
        }

        if (currentChunkSize < 0) {
            throw new IllegalStateException("negative chunk size: " + currentChunkSize);
        }

        if (currentChunkSize == 0) {
            currentState = State.READ_CHUNK_TRAILER;
        } else {
            currentState = State.READ_CHUNKED_CONTENT;
            bytesRead = 0;
        }
        return true;
    }

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
                currentState = State.READ_CHUNK_DELIMITER;
            }

            dst.offerMessage(new DefaultHttpContent(content));
            return true;
        }

        return false;
    }

    private boolean decodeChunkDelimiter() {
        if (!accumulator.hasLine()) {
            return false;
        }
        accumulator.readLine(StandardCharsets.US_ASCII);
        currentState = State.READ_CHUNK_SIZE;
        return true;
    }

    private boolean decodeChunkTrailer(ProtoSndQueue<HttpObject> dst) {
        if (pendingTrailerHeaders == null) {
            pendingTrailerHeaders = new HttpHeaders();
        }
        HttpHeaders trailingHeaders = pendingTrailerHeaders;

        while (accumulator.hasLine()) {
            String line = accumulator.readLine(StandardCharsets.US_ASCII);
            if (line == null) {
                return false;
            }

            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }

            if (line.isEmpty()) {
                dst.offerMessage(new DefaultLastHttpContent(ByteBuf.EMPTY, trailingHeaders));
                pendingTrailerHeaders = null;
                currentState = State.DONE;
                return true;
            }

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

    private void resetDecoder() {
        currentState = State.READ_INITIAL;
        currentResponse = null;
        contentLength = -1;
        bytesRead = 0;
        chunked = false;
        currentChunkSize = 0;
        pendingTrailerHeaders = null;
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
        READ_VARIABLE_LENGTH_CONTENT,
        READ_CHUNK_SIZE,
        READ_CHUNKED_CONTENT,
        READ_CHUNK_DELIMITER,
        READ_CHUNK_TRAILER,
        DONE
    }
}
