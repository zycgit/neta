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

/**
 * Base exception type for all HTTP protocol errors.
 * <p>Each HTTP exception carries an {@link HttpStatus} that represents the response status code most appropriate for the error.
 * Upstream error handlers can use {@link #status()} to generate a suitable HTTP error response.</p>
 * <p>
 * This class and its subclasses cover HTTP/1.x, HTTP/2, and HTTP/3.
 * <h3>Exception hierarchy</h3>
 * <pre>
 *   HttpProtocolException                          (base type, defaults to 400 Bad Request)
 *   ├── HttpBadRequestException                    (400, malformed message)
 *   ├── HttpSizeLimitException                     (413 / 414 / 431, size limit exceeded)
 *   │   ├── HttpContentTooLargeException           (413, payload too large)
 *   │   ├── HttpInitialLineTooLongException        (414, request line or status line too long)
 *   │   └── HttpHeaderTooLargeException            (431, header fields too large)
 *   ├── HttpProtocolConnectionException            (connection-level protocol failure)
 *   │   ├── HpackDecodingException                 (HTTP/2 HPACK decoding failure)
 *   │   └── QpackDecodingException                 (HTTP/3 QPACK decoding failure)
 *   ├── HttpProtocolStreamException                (stream-level protocol failure)
 *   ├── HttpProtocolStateException                 (protocol state, timing, or mode mismatch)
 *   ├── HttpProtocolOutOfBoundsException           (boundary overflow or invalid value)
 *   └── WebSocketHandshakeException                (WebSocket handshake failure)
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-19
 */
public class HttpProtocolException extends RuntimeException {
    private final HttpStatus status;
    private final long       errorCode;
    private long             streamId = -1;

    /**
     * Creates a protocol exception that uses the default status {@link HttpStatus#BAD_REQUEST 400}.
     * @param message exception description
     */
    public HttpProtocolException(String message) {
        this(HttpStatus.BAD_REQUEST, -1L, message);
    }

    /**
     * Creates a protocol exception that uses the default status {@link HttpStatus#BAD_REQUEST 400}.
     * @param message exception description
     * @param cause root cause exception
     */
    public HttpProtocolException(String message, Throwable cause) {
        this(HttpStatus.BAD_REQUEST, -1L, message, cause);
    }

    /**
     * Creates an exception that carries a protocol-specific error code.
     * @param errorCode protocol error code
     * @param message exception description
     */
    public HttpProtocolException(long errorCode, String message) {
        this(HttpStatus.BAD_REQUEST, errorCode, message);
    }

    /**
     * Creates an exception that carries a protocol-specific error code and root cause.
     * @param errorCode protocol error code
     * @param message exception description
     * @param cause root cause exception
     */
    public HttpProtocolException(long errorCode, String message, Throwable cause) {
        this(HttpStatus.BAD_REQUEST, errorCode, message, cause);
    }

    /**
     * Creates a protocol exception with the specified HTTP status.
     * @param status HTTP status
     * @param message exception description
     */
    public HttpProtocolException(HttpStatus status, String message) {
        this(status, -1L, message);
    }

    /**
     * Creates a protocol exception with the specified HTTP status and protocol-specific error code.
     * @param status HTTP status
     * @param errorCode protocol error code
     * @param message exception description
     */
    public HttpProtocolException(HttpStatus status, long errorCode, String message) {
        super(message);
        this.status = status != null ? status : HttpStatus.BAD_REQUEST;
        this.errorCode = errorCode;
    }

    /**
     * Creates a protocol exception with the specified HTTP status and root cause.
     * @param status HTTP status
     * @param message exception description
     * @param cause root cause exception
     */
    public HttpProtocolException(HttpStatus status, String message, Throwable cause) {
        this(status, -1L, message, cause);
    }

    /**
     * Creates a protocol exception with the specified HTTP status, protocol-specific error code, and root cause.
     * @param status HTTP status
     * @param errorCode protocol error code
     * @param message exception description
     * @param cause root cause exception
     */
    public HttpProtocolException(HttpStatus status, long errorCode, String message, Throwable cause) {
        super(message, cause);
        this.status = status != null ? status : HttpStatus.BAD_REQUEST;
        this.errorCode = errorCode;
    }

    /**
     * Returns the HTTP status that best describes the current error.
     * <p>Error handlers can use this value to generate an appropriate HTTP error response:</p>
     * <pre>
     *   catch (HttpProtocolException e) {
     *       response.setStatus(e.status());
     *   }
     * </pre>
     * @return associated {@link HttpStatus}, never {@code null}
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * Returns the HTTP status code as an integer for direct use.
     * Equivalent to {@code status().code()}.
     * @return numeric HTTP status code
     */
    public int statusCode() {
        return status.code();
    }

    /**
     * Returns the protocol-specific error code, or {@code -1} if this exception does not carry one.
     * @return protocol error code
     */
    public long errorCode() {
        return this.errorCode;
    }

    /**
     * Returns the associated stream identifier, or {@code -1} if the current error is not stream-scoped.
     * @return stream identifier
     */
    public long getStreamId() {
        return this.streamId;
    }

    /**
     * Updates the associated stream identifier for protocol stacks that can determine it at a later stage.
     * @param streamId stream identifier
     */
    public void setStreamId(long streamId) {
        this.streamId = streamId;
    }

    /**
     * Sets the associated stream identifier in a fluent style.
     * @param streamId stream identifier
     * @return current exception instance
     */
    public HttpProtocolException streamId(long streamId) {
        this.streamId = streamId;
        return this;
    }

    /**
     * Returns the stream identifier in a style consistent with other HTTP objects in this module.
     * @return stream identifier
     */
    public long streamId() {
        return this.streamId;
    }
}
