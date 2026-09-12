/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
/**
 * Thrown when the HTTP message body exceeds the configured maximum content length.
 * <p>Corresponds to {@link HttpStatus#REQUEST_ENTITY_TOO_LARGE 413 Request Entity Too Large}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-19
 * @see HttpSizeLimitException
 */
public class HttpContentTooLargeException extends HttpSizeLimitException {
    /**
     * Create a content-too-large exception.
     * @param message exception description
     */
    public HttpContentTooLargeException(String message) {
        super(HttpStatus.REQUEST_ENTITY_TOO_LARGE, message);
    }

    /**
     * Create a content-too-large exception.
     * @param message exception description
     * @param limit configured limit
     * @param actual actual size
     */
    public HttpContentTooLargeException(String message, long limit, long actual) {
        super(HttpStatus.REQUEST_ENTITY_TOO_LARGE, message, limit, actual);
    }
}
