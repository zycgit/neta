/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.client;

/**
 * WebSocket lifecycle and message listener.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface WebSocketListener {
    default void onOpen(WebSocket webSocket) {
    }

    default void onText(WebSocket webSocket, String message) {
    }

    default void onBinary(WebSocket webSocket, byte[] data) {
    }

    default void onPing(WebSocket webSocket, byte[] data) {
    }

    default void onPong(WebSocket webSocket, byte[] data) {
    }

    default void onClosing(WebSocket webSocket, int statusCode, String reason) {
    }

    default void onClosed(WebSocket webSocket, int statusCode, String reason) {
    }

    default void onFailure(WebSocket webSocket, Throwable error) {
    }
}
