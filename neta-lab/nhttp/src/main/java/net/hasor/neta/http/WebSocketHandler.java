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
package net.hasor.neta.http;

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
