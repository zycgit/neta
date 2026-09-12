/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;
/**
 * Client-side validator for websocket extension negotiation results.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-23
 */
public interface WebSocketExtensionValidator {
    /**
     * Validate the extension header returned by the server during the handshake.
     * @param version websocket version
     * @param requestedExtensions extension header originally requested by the client
     * @param negotiatedExtensions extension header returned by the server
     */
    void validateClientExtensions(WebSocketVersion version, String requestedExtensions, String negotiatedExtensions);
}
