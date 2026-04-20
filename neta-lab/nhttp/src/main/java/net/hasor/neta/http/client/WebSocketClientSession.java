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
package net.hasor.neta.http.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;

/**
 * Active client websocket session.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface WebSocketClientSession extends Closeable {
    URI uri();

    long streamId();

    String subProtocol();

    boolean isOpen();

    void sendText(String message) throws IOException;

    void sendBinary(byte[] data) throws IOException;

    void sendPing(byte[] data) throws IOException;

    void sendPong(byte[] data) throws IOException;

    void close(int statusCode, String reason) throws IOException;
}