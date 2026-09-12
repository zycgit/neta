/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.client;

import java.io.Closeable;
import java.io.IOException;

import net.hasor.nhttp.request.Request;

/**
 * Active WebSocket session opened by {@link HttpClient}.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface WebSocket extends Closeable {
    Request request();

    long streamId();

    String subProtocol();

    boolean isOpen();

    void sendText(String message) throws IOException;

    void sendBinary(byte[] data) throws IOException;

    void sendPing(byte[] data) throws IOException;

    void sendPong(byte[] data) throws IOException;

    void close(int statusCode, String reason) throws IOException;
}
