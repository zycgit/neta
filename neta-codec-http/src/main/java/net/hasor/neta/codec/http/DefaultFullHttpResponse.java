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
import net.hasor.neta.codec.http.constant.HttpStatus;
import net.hasor.neta.codec.http.constant.HttpVersion;

/**
 * Default implementation of {@link FullHttpResponse}.
 * Combines HTTP response headers with the complete message body.
 */
public class DefaultFullHttpResponse implements FullHttpResponse {
    private HttpVersion version;
    private HttpStatus  status;
    private HttpHeaders headers;
    private ByteBuf     content;
    private HttpHeaders trailerHeaders;

    /**
     * Creates a new full HTTP response with an empty body.
     * @param version the HTTP version
     * @param status the HTTP response status
     */
    public DefaultFullHttpResponse(HttpVersion version, HttpStatus status) {
        this(version, status, ByteBuf.EMPTY, new HttpHeaders(), new HttpHeaders());
    }

    /**
     * Creates a new full HTTP response with the specified body.
     * @param version the HTTP version
     * @param status the HTTP response status
     * @param content the body content
     */
    public DefaultFullHttpResponse(HttpVersion version, HttpStatus status, ByteBuf content) {
        this(version, status, content, new HttpHeaders(), new HttpHeaders());
    }

    /**
     * Creates a new full HTTP response with the specified body, headers, and trailing headers.
     * @param version the HTTP version
     * @param status the HTTP response status
     * @param content the body content
     * @param headers the HTTP headers
     * @param trailerHeaders the trailing headers
     */
    public DefaultFullHttpResponse(HttpVersion version, HttpStatus status, ByteBuf content, HttpHeaders headers, HttpHeaders trailerHeaders) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
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
        this.status = status;
        this.content = content;
        this.headers = headers;
        this.trailerHeaders = trailerHeaders;
    }

    @Override
    public HttpVersion protocolVersion() {
        return version;
    }

    @Override
    public HttpResponse setProtocolVersion(HttpVersion version) {
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
    public HttpStatus status() {
        return status;
    }

    @Override
    public HttpResponse setStatus(HttpStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        this.status = status;
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
    public String toString() {
        return getClass().getSimpleName() + "(version: " + version + ", status: " + status + ", content: " + content.readableBytes() + " bytes)";
    }
}
