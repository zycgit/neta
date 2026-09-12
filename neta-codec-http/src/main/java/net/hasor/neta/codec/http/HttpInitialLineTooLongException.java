/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
/**
 * Thrown when an HTTP request line or status line exceeds the configured maximum length.
 * <p>Corresponds to {@link HttpStatus#REQUEST_URI_TOO_LONG 414 Request-URI Too Long}.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-19
 * @see HttpSizeLimitException
 */
public class HttpInitialLineTooLongException extends HttpSizeLimitException {
    /**
     * Creates an initial-line-too-long exception.
     * @param message exception description
     */
    public HttpInitialLineTooLongException(String message) {
        super(HttpStatus.REQUEST_URI_TOO_LONG, message);
    }

    /**
     * Creates an initial-line-too-long exception.
     * @param message exception description
     * @param limit configured limit
     * @param actual actual size
     */
    public HttpInitialLineTooLongException(String message, long limit, long actual) {
        super(HttpStatus.REQUEST_URI_TOO_LONG, message, limit, actual);
    }
}
