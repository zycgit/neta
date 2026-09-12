/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpStatus;
/**
 * Callback used by the server handshake authorizer to accept or reject a request.
 * <p>
 * Rejections may carry a custom status, headers, and body.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-23
 */
public interface WebSocketHandshakeCallback {
    /**
     * Accept the current handshake request.
     */
    void accept();

    /**
     * Accept the current handshake request with extra response headers.
     * @param headers additional response headers
     */
    void accept(HttpHeaders headers);

    /**
     * Reject the current handshake request with the default response.
     */
    void reject();

    /**
     * Reject the current handshake request with the given HTTP status.
     * @param status HTTP status to return
     */
    void reject(HttpStatus status);

    /**
     * Reject the current handshake request with the given HTTP status and body.
     * @param status HTTP status to return
     * @param body response body bytes
     */
    void reject(HttpStatus status, byte[] body);

    /**
     * Reject the current handshake request with the given status, headers, and body.
     * @param status HTTP status to return
     * @param headers response headers
     * @param body response body bytes
     */
    void reject(HttpStatus status, HttpHeaders headers, byte[] body);

    /**
     * Reject the current handshake request with the given status code and reason.
     * @param code HTTP status code
     * @param reasonPhrase HTTP reason phrase
     */
    void reject(int code, String reasonPhrase);

    /**
     * Reject the current handshake request with the given status code, reason, and body.
     * @param code HTTP status code
     * @param reasonPhrase HTTP reason phrase
     * @param body response body bytes
     */
    void reject(int code, String reasonPhrase, byte[] body);

    /**
     * Reject the current handshake request with the given status code, reason, headers, and body.
     * @param code HTTP status code
     * @param reasonPhrase HTTP reason phrase
     * @param headers response headers
     * @param body response body bytes
     */
    void reject(int code, String reasonPhrase, HttpHeaders headers, byte[] body);
}
