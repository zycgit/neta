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
import net.hasor.neta.codec.http.constant.HttpMethod;
import net.hasor.neta.codec.http.constant.HttpVersion;

/**
 * Default implementation of {@link HttpRequest}.
 */
public class DefaultHttpRequest implements HttpRequest {
    private HttpVersion version;
    private HttpMethod  method;
    private String      uri;
    private HttpHeaders headers;

    /**
     * Creates a new HTTP request.
     * @param version the HTTP version
     * @param method the HTTP method
     * @param uri the request URI
     */
    public DefaultHttpRequest(HttpVersion version, HttpMethod method, String uri) {
        this(version, method, uri, new HttpHeaders());
    }

    /**
     * Creates a new HTTP request with the specified headers.
     * @param version the HTTP version
     * @param method the HTTP method
     * @param uri the request URI
     * @param headers the HTTP headers
     */
    public DefaultHttpRequest(HttpVersion version, HttpMethod method, String uri, HttpHeaders headers) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }
        if (headers == null) {
            throw new IllegalArgumentException("headers must not be null");
        }
        this.version = version;
        this.method = method;
        this.uri = uri;
        this.headers = headers;
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
    public String toString() {
        return getClass().getSimpleName() + "(decodeResult: success" + ", version: " + version + ", method: " + method + ", uri: " + uri + ')';
    }
}
