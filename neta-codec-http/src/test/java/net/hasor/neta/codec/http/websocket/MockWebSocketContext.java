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
package net.hasor.neta.codec.http.websocket;

final class MockWebSocketContext implements WebSocketContext {
    private final boolean ready;
    private final boolean server;
    private final String  subProtocol;
    private final int     version;
    private final String  requestPath;
    private final String  extensions;

    private MockWebSocketContext(boolean ready, boolean server, int version, String requestPath, String subProtocol, String extensions) {
        this.ready = ready;
        this.server = server;
        this.version = version;
        this.requestPath = requestPath;
        this.subProtocol = subProtocol;
        this.extensions = extensions;
    }

    static MockWebSocketContext server(WebSocketVersion version, String requestPath) {
        return new MockWebSocketContext(true, true, version.code(), requestPath, null, null);
    }

    static MockWebSocketContext client(WebSocketVersion version, String requestPath) {
        return new MockWebSocketContext(true, false, version.code(), requestPath, null, null);
    }

    @Override
    public boolean isReady() {
        return this.ready;
    }

    @Override
    public boolean isServer() {
        return this.server;
    }

    @Override
    public boolean isClient() {
        return !this.server;
    }

    @Override
    public String subProtocol() {
        return this.subProtocol;
    }

    @Override
    public int version() {
        return this.version;
    }

    @Override
    public String requestPath() {
        return this.requestPath;
    }

    @Override
    public String extensions() {
        return this.extensions;
    }
}