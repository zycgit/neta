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
 * Thrown when an HTTP message component exceeds a configured size limit.
 * <p>This is the common base class for all "too large" errors, each subclass
 * maps to a specific HTTP status code:
 * <ul>
 *   <li>{@link HttpContentTooLargeException} → {@link HttpStatus#REQUEST_ENTITY_TOO_LARGE 413}</li>
 *   <li>{@link HttpInitialLineTooLongException} → {@link HttpStatus#REQUEST_URI_TOO_LONG 414}</li>
 *   <li>{@link HttpHeaderTooLargeException} → {@link HttpStatus#REQUEST_HEADER_FIELDS_TOO_LARGE 431}</li>
 * </ul>
 * @see HttpProtocolException
 */
public class HttpSizeLimitException extends HttpProtocolException {
    private final long limit;
    private final long actual;

    /**
     * Creates a size limit exception with the specific HTTP status.
     * @param status the HTTP status (e.g., 413, 414, 431)
     * @param message descriptive message
     * @param limit the configured maximum size
     * @param actual the actual size that exceeded the limit (-1 if unknown)
     */
    public HttpSizeLimitException(HttpStatus status, String message, long limit, long actual) {
        super(status, message);
        this.limit = limit;
        this.actual = actual;
    }

    /**
     * Creates a size limit exception with the specific HTTP status.
     * @param status the HTTP status (e.g., 413, 414, 431)
     * @param message descriptive message
     */
    public HttpSizeLimitException(HttpStatus status, String message) {
        this(status, message, -1, -1);
    }

    /**
     * Returns the configured maximum size, or {@code -1} if not applicable.
     */
    public long limit() {
        return limit;
    }

    /**
     * Returns the actual size that exceeded the limit, or {@code -1} if not applicable.
     */
    public long actual() {
        return actual;
    }
}