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
 * Signals a WebSocket protocol-format violation.
 * <p>
 * Carries the close status code that should be used when the violation is fatal on the wire.
 */
public class WebSocketProtocolViolationException extends RuntimeException {
    private final int closeStatusCode;

    public WebSocketProtocolViolationException(String message) {
        this(WebSocketCode.PROTOCOL_ERROR, message, null);
    }

    public WebSocketProtocolViolationException(int closeStatusCode, String message) {
        this(closeStatusCode, message, null);
    }

    public WebSocketProtocolViolationException(String message, Throwable cause) {
        this(WebSocketCode.PROTOCOL_ERROR, message, cause);
    }

    public WebSocketProtocolViolationException(int closeStatusCode, String message, Throwable cause) {
        super(message, cause);
        this.closeStatusCode = closeStatusCode;
    }

    public int closeStatusCode() {
        return this.closeStatusCode;
    }
}