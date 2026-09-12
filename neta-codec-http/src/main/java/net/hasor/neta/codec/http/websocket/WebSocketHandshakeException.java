/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;
import java.util.Objects;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpProtocolException;
import net.hasor.neta.codec.http.HttpStatus;
/**
 * Protocol exception representing a failed WebSocket opening handshake.
 * <p>
 * This exception carries the HTTP status, optional response headers, optional
 * response body, and whether the connection should be closed.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-22
 */
public class WebSocketHandshakeException extends HttpProtocolException {
    private final HttpStatus  status;
    private final HttpHeaders headers;
    private final byte[]      body;
    private final boolean     closeConnection;

    /**
     * Create a handshake exception from the HTTP status and error message.
     * @param status HTTP status
     * @param message error message
     */
    public WebSocketHandshakeException(HttpStatus status, String message) {
        this(status, message, null, null, true, null);
    }

    /**
     * Create a handshake exception from the HTTP status, message, and close flag.
     * @param status HTTP status
     * @param message error message
     * @param closeConnection whether to close the connection
     */
    public WebSocketHandshakeException(HttpStatus status, String message, boolean closeConnection) {
        this(status, message, null, null, closeConnection, null);
    }

    /**
     * Create a handshake exception from the HTTP status, message, and root cause.
     * @param status HTTP status
     * @param message error message
     * @param cause root cause
     */
    public WebSocketHandshakeException(HttpStatus status, String message, Throwable cause) {
        this(status, message, null, null, true, cause);
    }

    /**
     * Create a handshake exception with the full response information.
     * @param status HTTP status
     * @param message error message
     * @param headers response headers
     * @param body response body
     * @param closeConnection whether to close the connection
     */
    public WebSocketHandshakeException(HttpStatus status, String message, HttpHeaders headers, byte[] body, boolean closeConnection) {
        this(status, message, headers, body, closeConnection, null);
    }

    /**
     * Create a handshake exception with the full response information and root cause.
     * @param status HTTP status
     * @param message error message
     * @param headers response headers
     * @param body response body
     * @param closeConnection whether to close the connection
     * @param cause root cause
     */
    public WebSocketHandshakeException(HttpStatus status, String message, HttpHeaders headers, byte[] body, boolean closeConnection, Throwable cause) {
        super(message, cause);
        this.status = Objects.requireNonNull(status, "status is null");
        this.headers = headers;
        this.body = body;
        this.closeConnection = closeConnection;
    }

    /**
     * Return the HTTP status associated with the handshake failure.
     */
    public HttpStatus status() {
        return this.status;
    }

    /**
     * Return the attached response headers.
     */
    public HttpHeaders headers() {
        return this.headers;
    }

    /**
     * Return the attached response body.
     */
    public byte[] body() {
        return this.body;
    }

    /**
     * Return whether the connection should be closed.
     */
    public boolean closeConnection() {
        return this.closeConnection;
    }
}
