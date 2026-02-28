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
/**
 * Default implementation of {@link HttpResponse}.
 */
public class DefaultHttpResponse implements HttpResponse {
    private final HttpHeaders headers;
    private       int         streamId;
    private       HttpVersion version;
    private       HttpStatus  status;

    /**
     * Creates a new HTTP response.
     * @param version the HTTP version
     * @param status the HTTP response status
     */
    public DefaultHttpResponse(HttpVersion version, HttpStatus status) {
        this(version, status, new HttpHeaders());
    }

    /**
     * Creates a new HTTP response with the specified headers.
     * @param version the HTTP version
     * @param status the HTTP response status
     * @param headers the HTTP headers
     */
    public DefaultHttpResponse(HttpVersion version, HttpStatus status, HttpHeaders headers) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (headers == null) {
            throw new IllegalArgumentException("headers must not be null");
        }
        this.version = version;
        this.status = status;
        this.headers = headers;
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
    public String toString() {
        return getClass().getSimpleName() + "(decodeResult: success" + ", version: " + version + ", status: " + status + ')';
    }
}
