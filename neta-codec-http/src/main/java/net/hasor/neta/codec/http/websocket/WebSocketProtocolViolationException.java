/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;
/**
 * Exception indicating a websocket protocol-format violation.
 * <p>
 * Carries the close status code that should be used when a fatal wire-level
 * shutdown is required.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-23
 */
public class WebSocketProtocolViolationException extends RuntimeException {
    private final int closeStatusCode;

    /**
     * Create an exception using the default protocol-error close code.
     * @param message exception message
     */
    public WebSocketProtocolViolationException(String message) {
        this(WebSocketCode.PROTOCOL_ERROR, message, null);
    }

    /**
     * Create an exception using the specified close status code.
     * @param closeStatusCode close status code
     * @param message exception message
     */
    public WebSocketProtocolViolationException(int closeStatusCode, String message) {
        this(closeStatusCode, message, null);
    }

    /**
     * Create an exception using the default protocol-error close code and a cause.
     * @param message exception message
     * @param cause root cause
     */
    public WebSocketProtocolViolationException(String message, Throwable cause) {
        this(WebSocketCode.PROTOCOL_ERROR, message, cause);
    }

    /**
     * Create an exception using the specified close status code and a cause.
     * @param closeStatusCode close status code
     * @param message exception message
     * @param cause root cause
     */
    public WebSocketProtocolViolationException(int closeStatusCode, String message, Throwable cause) {
        super(message, cause);
        this.closeStatusCode = closeStatusCode;
    }

    /**
     * Return the close status code recommended for wire-level shutdown.
     */
    public int closeStatusCode() {
        return this.closeStatusCode;
    }
}
