/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;
/**
 * Asynchronous authorization hook that decides whether a server handshake is accepted.
 * <p>
 * Implementations inspect the handshake request snapshot and answer through the
 * supplied callback.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-23
 */
@FunctionalInterface
public interface WebSocketHandshakeAuthorizer {
    /**
     * Accept or reject the current handshake request.
     * @param request snapshot of the opening handshake request
     * @param callback callback used to accept or reject the handshake
     * @throws Throwable any error raised while authorizing the request
     */
    void authorize(WebSocketHandshakeRequest request, WebSocketHandshakeCallback callback) throws Throwable;
}
