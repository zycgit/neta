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