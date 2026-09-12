/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server;
import java.io.IOException;
import java.net.SocketAddress;

/**
 * Represents an active WebSocket connection session.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface WebSocketSession {

    /** Returns the unique session ID */
    String getId();

    /** Sends a text message */
    void sendText(String message) throws IOException;

    /** Sends a binary message */
    void sendBinary(byte[] data) throws IOException;

    /** Sends a ping frame */
    void sendPing() throws IOException;

    /** Sends a pong frame */
    void sendPong() throws IOException;

    /** Closes the WebSocket with a normal close status */
    void close() throws IOException;

    /** Closes the WebSocket with a status code and reason */
    void close(int statusCode, String reason) throws IOException;

    /** Returns true if the session is open */
    boolean isOpen();

    /** Returns the remote address */
    SocketAddress getRemoteAddress();

    /** Returns the request path that initiated the WebSocket */
    String getRequestPath();

    /** Returns the original HTTP request that initiated the upgrade */
    ServletRequest getUpgradeRequest();

    /** Returns a session attribute */
    Object getAttribute(String name);

    /** Sets a session attribute */
    void setAttribute(String name, Object value);
}
