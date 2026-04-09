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
 * Snapshot object for a server-side opening handshake request.
 * <p>
 * Exposes the requested version, path, sub-protocols, extensions, and headers
 * to the handshake authorizer.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-22
 */
public class WebSocketHandshakeRequest {
    private       long               streamId;
    private       boolean            released;
    private final WebSocketVersion   version;
    private final String             requestPath;
    private final String             requestedProtocols;
    private final String             requestedExtensions;
    private final DefaultHttpHeaders headers;

    /**
     * Create a request snapshot from the specified handshake parameters.
     * @param version websocket version
     * @param requestPath request path
     * @param requestedProtocols requested sub-protocols
     * @param requestedExtensions requested extensions
     */
    public WebSocketHandshakeRequest(WebSocketVersion version, String requestPath, String requestedProtocols, String requestedExtensions) {
        this(version, requestPath, requestedProtocols, requestedExtensions, null);
    }

    /**
     * Create a request snapshot from the specified handshake parameters and headers.
     * @param version websocket version
     * @param requestPath request path
     * @param requestedProtocols requested sub-protocols
     * @param requestedExtensions requested extensions
     * @param headers request headers
     */
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

    /**
     * Return the HTTP stream ID.
     */
    public long streamId() {
        return this.streamId;
    }

    /**
     * Set the HTTP stream ID.
     * @param streamId stream ID
     * @return current request object
     */
    public WebSocketHandshakeRequest streamId(long streamId) {
        this.streamId = streamId;
        return this;
    }

    /**
     * Return the websocket version.
     */
    public WebSocketVersion version() {
        return this.version;
    }

    /**
     * Return the request path.
     */
    public String requestPath() {
        return this.requestPath;
    }

    /**
     * Return the requested sub-protocol header value.
     */
    public String requestedProtocols() {
        return this.requestedProtocols;
    }

    /**
     * Return the requested extension header value.
     */
    public String requestedExtensions() {
        return this.requestedExtensions;
    }

    /**
     * Read a request header by name.
     * @param name request header name
     * @return request header value
     */
    public String header(String name) {
        return this.headers.getString(name);
    }

    /**
     * Return the snapshot of handshake request headers.
     */
    public HttpHeaders headers() {
        return this.headers;
    }

    /**
     * Release the internally held request-header resources.
     * After this call, the object no longer retains valid header content.
     */
    public void release() {
        if (this.released) {
            return;
        }

        this.released = true;
        this.streamId = 0;
        this.headers.release();
    }

    /**
     * Return a compact summary string for the request.
     */
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