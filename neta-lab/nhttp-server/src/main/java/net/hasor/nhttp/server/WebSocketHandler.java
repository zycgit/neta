/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server;

/**
 * WebSocket handler interface for processing WebSocket connections.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface WebSocketHandler {

    /** Called when a new WebSocket connection is opened */
    default void onOpen(WebSocketSession session) {
    }

    /** Called when a text message is received */
    default void onMessage(WebSocketSession session, String message) {
    }

    /** Called when a binary message is received */
    default void onMessage(WebSocketSession session, byte[] data) {
    }

    /** Called when the WebSocket connection is closed */
    default void onClose(WebSocketSession session, int statusCode, String reason) {
    }

    /** Called when an error occurs */
    default void onError(WebSocketSession session, Throwable cause) {
    }
}
