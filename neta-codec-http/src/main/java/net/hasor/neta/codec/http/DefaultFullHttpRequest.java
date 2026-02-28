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
package net.hasor.neta.codec.http;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Default implementation of {@link FullHttpRequest}.
 * Combines HTTP request headers with the complete message body.
 */
public class DefaultFullHttpRequest implements FullHttpRequest {
    private final HttpHeaders headers;
    private final ByteBuf     content;
    private final HttpHeaders trailerHeaders;
    private       HttpVersion version;
    private       HttpMethod  method;
    private       String      uri;
    private       int         streamId;

    /**
     * Creates a new full HTTP request with an empty body.
     * @param version the HTTP version
     * @param method the HTTP method
     * @param uri the request URI
     */
    public DefaultFullHttpRequest(HttpVersion version, HttpMethod method, String uri) {
        this(version, method, uri, ByteBuf.EMPTY, new HttpHeaders(), new HttpHeaders());
    }

    /**
     * Creates a new full HTTP request with the specified body.
     * @param version the HTTP version
     * @param method the HTTP method
     * @param uri the request URI
     * @param content the body content
     */
    public DefaultFullHttpRequest(HttpVersion version, HttpMethod method, String uri, ByteBuf content) {
        this(version, method, uri, content, new HttpHeaders(), new HttpHeaders());
    }

    /**
     * Creates a new full HTTP request with the specified body, headers, and trailing headers.
     * @param version the HTTP version
     * @param method the HTTP method
     * @param uri the request URI
     * @param content the body content
     * @param headers the HTTP headers
     * @param trailerHeaders the trailing headers
     */
    public DefaultFullHttpRequest(HttpVersion version, HttpMethod method, String uri, ByteBuf content, HttpHeaders headers, HttpHeaders trailerHeaders) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (headers == null) {
            throw new IllegalArgumentException("headers must not be null");
        }
        if (trailerHeaders == null) {
            throw new IllegalArgumentException("trailingHeaders must not be null");
        }
        this.version = version;
        this.method = method;
        this.uri = uri;
        this.content = content;
        this.headers = headers;
        this.trailerHeaders = trailerHeaders;
    }

    @Override
    public HttpVersion protocolVersion() {
        return version;
    }

    @Override
    public HttpRequest setProtocolVersion(HttpVersion version) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        this.version = version;
        return this;
    }

    @Override
    public HttpHeaders headers() {
        return headers;
    }

    @Override
    public HttpMethod method() {
        return method;
    }

    @Override
    public HttpRequest setMethod(HttpMethod method) {
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        this.method = method;
        return this;
    }

    @Override
    public String uri() {
        return uri;
    }

    @Override
    public HttpRequest setUri(String uri) {
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }
        this.uri = uri;
        return this;
    }

    @Override
    public ByteBuf content() {
        return content;
    }

    @Override
    public HttpHeaders trailerHeaders() {
        return trailerHeaders;
    }

    @Override
    public int streamId() {
        return streamId;
    }

    @Override
    public HttpObject streamId(int streamId) {
        this.streamId = streamId;
        return this;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(version: " + version + ", method: " + method + ", uri: " + uri + ", content: " + content.readableBytes() + " bytes)";
    }
}
