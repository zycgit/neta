/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
/**
 * Thrown when an HTTP message has a syntax error and parsing cannot continue.
 * <p>Corresponds to {@link HttpStatus#BAD_REQUEST 400 Bad Request}.</p>
 * <p>Typical causes include:</p>
 * <ul>
 *   <li>An invalid request line or status line format</li>
 *   <li>A malformed header field, such as a missing colon or an empty name</li>
 *   <li>An invalid Content-Length value</li>
 *   <li>An invalid chunked transfer-encoding format</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-24
 * @see HttpProtocolException
 */
public class HttpBadRequestException extends HttpProtocolException {
    /**
     * Creates a bad request exception.
     * @param message exception description
     */
    public HttpBadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Creates a bad request exception with a root cause.
     * @param message exception description
     * @param cause root cause exception
     */
    public HttpBadRequestException(String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, message, cause);
    }
}
