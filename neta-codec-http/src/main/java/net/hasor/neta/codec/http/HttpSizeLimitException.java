/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
/**
 * Thrown when an HTTP message component exceeds the configured size limit.
 * <p>This is the common base class for all "too large" errors, and each subclass maps to a specific HTTP status code:</p>
 * <ul>
 *   <li>{@link HttpContentTooLargeException} -> {@link HttpStatus#REQUEST_ENTITY_TOO_LARGE 413}</li>
 *   <li>{@link HttpInitialLineTooLongException} -> {@link HttpStatus#REQUEST_URI_TOO_LONG 414}</li>
 *   <li>{@link HttpHeaderTooLargeException} -> {@link HttpStatus#REQUEST_HEADER_FIELDS_TOO_LARGE 431}</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-24
 * @see HttpProtocolException
 */
public class HttpSizeLimitException extends HttpProtocolException {
    private final long limit;
    private final long actual;

    /**
     * Creates a size-limit exception with the specified HTTP status.
     * @param status HTTP status, for example 413, 414, or 431
     * @param message exception description
     * @param limit configured maximum limit
     * @param actual actual size that exceeded the limit, or -1 if unknown
     */
    public HttpSizeLimitException(HttpStatus status, String message, long limit, long actual) {
        super(status, message);
        this.limit = limit;
        this.actual = actual;
    }

    /**
     * Creates a size-limit exception with the specified HTTP status.
     * @param status HTTP status, for example 413, 414, or 431
     * @param message exception description
     */
    public HttpSizeLimitException(HttpStatus status, String message) {
        this(status, message, -1, -1);
    }

    /**
     * Returns the configured maximum limit, or {@code -1} if it does not apply.
     * @return configured maximum limit
     */
    public long limit() {
        return limit;
    }

    /**
     * Returns the actual size that exceeded the limit, or {@code -1} if it does not apply.
     * @return actual size
     */
    public long actual() {
        return actual;
    }
}
