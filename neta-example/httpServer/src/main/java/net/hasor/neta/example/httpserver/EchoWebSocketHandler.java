/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
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
