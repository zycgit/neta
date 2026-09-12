/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;
/**
 * Server-side selector for websocket extension negotiation results.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-23
 */
public interface WebSocketExtensionSelector {
    /**
     * Select the extension header returned by the server for the current handshake.
     * @param request handshake request snapshot
     * @param proposedExtensions proposed extension header value
     * @return selected extension header, or {@code null} when no extension is enabled
     */
    String selectServerExtensions(WebSocketHandshakeRequest request, String proposedExtensions);
}
