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
import java.util.Objects;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpProtocolException;
import net.hasor.neta.codec.http.HttpStatus;

/**
 * Internal exception used to abort the opening handshake with an HTTP response.
 * <p>
 * Carries status, optional headers, optional body, and whether the channel should be closed.
 */
class WebSocketHandshakeException extends HttpProtocolException {
    private final HttpStatus  status;
    private final HttpHeaders headers;
    private final byte[]      body;
    private final boolean     closeConnection;

    public WebSocketHandshakeException(HttpStatus status, String message) {
        this(status, message, null, null, true, null);
    }

    public WebSocketHandshakeException(HttpStatus status, String message, boolean closeConnection) {
        this(status, message, null, null, closeConnection, null);
    }

    public WebSocketHandshakeException(HttpStatus status, String message, Throwable cause) {
        this(status, message, null, null, true, cause);
    }

    public WebSocketHandshakeException(HttpStatus status, String message, HttpHeaders headers, byte[] body, boolean closeConnection) {
        this(status, message, headers, body, closeConnection, null);
    }

    public WebSocketHandshakeException(HttpStatus status, String message, HttpHeaders headers, byte[] body, boolean closeConnection, Throwable cause) {
        super(message, cause);
        this.status = Objects.requireNonNull(status, "status is null");
        this.headers = headers;
        this.body = body;
        this.closeConnection = closeConnection;
    }

    public HttpStatus status() {
        return this.status;
    }

    public HttpHeaders headers() {
        return this.headers;
    }

    public byte[] body() {
        return this.body;
    }

    public boolean closeConnection() {
        return this.closeConnection;
    }
}