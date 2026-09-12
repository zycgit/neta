/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.http.client;

/**
 * Listener for websocket lifecycle and message callbacks.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface WebSocketClientHandler {
    default void onOpen(WebSocketClientSession session) {
    }

    default void onText(WebSocketClientSession session, String message) {
    }

    default void onBinary(WebSocketClientSession session, byte[] data) {
    }

    default void onPing(WebSocketClientSession session, byte[] data) {
    }

    default void onPong(WebSocketClientSession session, byte[] data) {
    }

    default void onClose(WebSocketClientSession session, int statusCode, String reason) {
    }

    default void onError(WebSocketClientSession session, Throwable error) {
    }
}
