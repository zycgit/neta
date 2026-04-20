/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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