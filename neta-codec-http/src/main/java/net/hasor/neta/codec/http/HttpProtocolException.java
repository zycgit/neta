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
 * Base exception for all HTTP protocol errors.
 * <p>Every HTTP exception carries an {@link HttpStatus} that indicates the
 * most appropriate response status code for this error. Upstream error
 * handlers can use {@link #status()} to generate a proper HTTP error response.
 * <p>
 * This class and its subclasses cover HTTP/1.x, HTTP/2, and HTTP/3.
 * <h3>Exception hierarchy</h3>
 * <pre>
 *   HttpProtocolException                          (base, default 400 Bad Request)
 *   ├── HttpBadRequestException                    (400 — malformed messages)
 *   ├── HttpSizeLimitException                     (413 / 414 / 431 — size exceeded)
 *   │   ├── HttpContentTooLargeException           (413 — body too large)
 *   │   ├── HttpInitialLineTooLongException        (414 — request/status line too long)
 *   │   └── HttpHeaderTooLargeException            (431 — headers too large)
 *   └── HttpProtocolViolationException             (varies — wire-protocol violations for HTTP/2 &amp; HTTP/3)
 * </pre>
 */
public class HttpProtocolException extends RuntimeException {
    private final HttpStatus status;

    /** Creates an exception with the default status {@link HttpStatus#BAD_REQUEST 400}. */
    public HttpProtocolException(String message) {
        this(HttpStatus.BAD_REQUEST, message);
    }

    /** Creates an exception with the default status {@link HttpStatus#BAD_REQUEST 400}. */
    public HttpProtocolException(String message, Throwable cause) {
        this(HttpStatus.BAD_REQUEST, message, cause);
    }

    /** Creates an exception with the specified status. */
    public HttpProtocolException(HttpStatus status, String message) {
        super(message);
        this.status = status != null ? status : HttpStatus.BAD_REQUEST;
    }

    /** Creates an exception with the specified status and cause. */
    public HttpProtocolException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status != null ? status : HttpStatus.BAD_REQUEST;
    }

    /**
     * Returns the HTTP status code that best describes this error.
     * <p>Error handlers can use this to generate an appropriate HTTP error response:
     * <pre>
     *   catch (HttpProtocolException e) {
     *       response.setStatus(e.status());
     *   }
     * </pre>
     * @return the associated {@link HttpStatus}, never {@code null}
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * Returns the HTTP status code as an integer for convenience.
     * Equivalent to {@code status().code()}.
     * @return the numeric HTTP status code
     */
    public int statusCode() {
        return status.code();
    }
}
