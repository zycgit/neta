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

/**
 * Immutable snapshot of a server-side opening-handshake request.
 * <p>
 * Exposes requested version, path, protocols, extensions, and headers to the authorizer.
 */
public class WebSocketHandshakeRequest {
    private       int                streamId;
    private       boolean            released;
    private final WebSocketVersion   version;
    private final String             requestPath;
    private final String             requestedProtocols;
    private final String             requestedExtensions;
    private final DefaultHttpHeaders headers;

    public WebSocketHandshakeRequest(WebSocketVersion version, String requestPath, String requestedProtocols, String requestedExtensions) {
        this(version, requestPath, requestedProtocols, requestedExtensions, null);
    }

    public WebSocketHandshakeRequest(WebSocketVersion version, String requestPath, String requestedProtocols, String requestedExtensions, HttpHeaders headers) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        this.version = version;
        this.requestPath = requestPath;
        this.requestedProtocols = requestedProtocols;
        this.requestedExtensions = requestedExtensions;
        this.headers = new DefaultHttpHeaders();
        if (headers != null) {
            this.headers.appendHeaders(headers);
        }
    }

    public int streamId() {
        return this.streamId;
    }

    public WebSocketHandshakeRequest streamId(int streamId) {
        this.streamId = streamId;
        return this;
    }

    public WebSocketVersion version() {
        return this.version;
    }

    public String requestPath() {
        return this.requestPath;
    }

    public String requestedProtocols() {
        return this.requestedProtocols;
    }

    public String requestedExtensions() {
        return this.requestedExtensions;
    }

    public String header(String name) {
        return this.headers.getString(name);
    }

    public HttpHeaders headers() {
        return this.headers;
    }

    public void release() {
        if (this.released) {
            return;
        }

        this.released = true;
        this.streamId = 0;
        this.headers.release();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("WebSocketHandshakeRequest{");
        sb.append("version=").append(this.version);
        if (this.requestPath != null) {
            sb.append(", path='").append(this.requestPath).append('\'');
        }
        if (this.requestedProtocols != null) {
            sb.append(", requestedProtocols='").append(this.requestedProtocols).append('\'');
        }
        if (this.requestedExtensions != null) {
            sb.append(", requestedExtensions='").append(this.requestedExtensions).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }
}