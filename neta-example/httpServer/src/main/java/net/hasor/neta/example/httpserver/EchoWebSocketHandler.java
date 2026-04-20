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
package net.hasor.neta.example.httpserver;

import java.io.IOException;

import net.hasor.nhttp.server.WebSocketHandler;
import net.hasor.nhttp.server.WebSocketSession;

/**
 * A simple echo WebSocket handler that sends received messages back to the client.
 * @author 赵永春 (zyc@hasor.net)
 */
public class EchoWebSocketHandler implements WebSocketHandler {

    @Override
    public void onOpen(WebSocketSession session) {
        try {
            session.sendText("[Server] Connected! Session ID: " + session.getId());
        } catch (IOException e) {
            // ignore
        }
    }

    @Override
    public void onMessage(WebSocketSession session, String message) {
        try {
            session.sendText("[Echo] " + message);
        } catch (IOException e) {
            // ignore
        }
    }

    @Override
    public void onMessage(WebSocketSession session, byte[] data) {
        try {
            session.sendBinary(data);
        } catch (IOException e) {
            // ignore
        }
    }

    @Override
    public void onClose(WebSocketSession session, int statusCode, String reason) {
        // nothing to clean up
    }

    @Override
    public void onError(WebSocketSession session, Throwable cause) {
        System.err.println("WebSocket error on session " + session.getId() + ": " + cause.getMessage());
    }
}
