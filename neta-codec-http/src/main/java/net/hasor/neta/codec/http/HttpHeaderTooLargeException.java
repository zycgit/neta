/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
/**
 * Thrown when HTTP header fields exceed the configured maximum header size.
 * <p>Corresponds to {@link HttpStatus#REQUEST_HEADER_FIELDS_TOO_LARGE 431 Request Header Fields Too Large}.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-19
 * @see HttpSizeLimitException
 */
public class HttpHeaderTooLargeException extends HttpSizeLimitException {
    /**
     * Creates a header-too-large exception.
     * @param message exception description
     */
    public HttpHeaderTooLargeException(String message) {
        super(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE, message);
    }

    /**
     * Creates a header-too-large exception.
     * @param message exception description
     * @param limit configured limit
     * @param actual actual size
     */
    public HttpHeaderTooLargeException(String message, long limit, long actual) {
        super(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE, message, limit, actual);
    }
}
