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
import net.hasor.neta.codec.http.DefaultHttpHeaders;
import net.hasor.neta.codec.http.HttpHeaders;

public class WebSocketHandshakeEvent extends AbstractWebSocketEvent {
    private final WebSocketVersion   version;
    private final String             requestPath;
    private final String             subProtocol;
    private final String             extensions;
    private final DefaultHttpHeaders headers;

    public WebSocketHandshakeEvent(WebSocketVersion version, String requestPath, String subProtocol, String extensions) {
        this(version, requestPath, subProtocol, extensions, null);
    }

    public WebSocketHandshakeEvent(WebSocketVersion version, String requestPath, String subProtocol, String extensions, HttpHeaders headers) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        this.version = version;
        this.requestPath = requestPath;
        this.subProtocol = subProtocol;
        this.extensions = extensions;
        this.headers = new DefaultHttpHeaders();
        if (headers != null) {
            this.headers.appendHeaders(headers);
        }
    }

    public WebSocketVersion version() {
        return this.version;
    }

    public String requestPath() {
        return this.requestPath;
    }

    public String subProtocol() {
        return this.subProtocol;
    }

    public String extensions() {
        return this.extensions;
    }

    public String header(String name) {
        return this.headers.getString(name);
    }

    public HttpHeaders headers() {
        return this.headers;
    }

    @Override
    protected void doRelease() {
        this.headers.release();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("HandshakeWebSocketEvent{");
        sb.append("version=").append(this.version);
        if (this.requestPath != null) {
            sb.append(", path='").append(this.requestPath).append('\'');
        }
        if (this.subProtocol != null) {
            sb.append(", subProtocol='").append(this.subProtocol).append('\'');
        }
        if (this.extensions != null) {
            sb.append(", extensions='").append(this.extensions).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }
}