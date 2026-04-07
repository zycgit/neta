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
 * Default implementation of {@link HttpRequest}.
 * <p>
 * This object stores only the request line fields. Header blocks and content chunks
 * are represented later in the message stream as separate {@link HttpHeaders} and
 * {@link HttpContent} objects.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class DefaultHttpRequest extends AbstractHttpObject<HttpRequest> implements HttpRequest {
    private HttpVersion version;
    private HttpMethod  method;
    private String      uri;
    private String      versionText;
    private String      methodText;
    private String      uriText;

    /**
     * Create a request start-line object.
     * @param version HTTP version
     * @param method HTTP method
     * @param uri request target
     */
    public DefaultHttpRequest(HttpVersion version, HttpMethod method, String uri) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }

        this.version = version;
        this.method = method;
        this.uri = uri;
        this.versionText = version.text();
        this.methodText = method.name();
        this.uriText = uri;
    }

    /**
     * Create a request start-line object from parsed text fields.
     * @param version raw protocol version text
     * @param method raw request method text
     * @param uri raw request target text
     */
    public DefaultHttpRequest(String version, String method, String uri) {
        if (version == null || version.isEmpty()) {
            throw new IllegalArgumentException("version must not be empty");
        }
        if (method == null || method.isEmpty()) {
            throw new IllegalArgumentException("method must not be empty");
        }
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }
        this.versionText = version;
        this.methodText = method;
        this.uriText = uri;
    }

    @Override
    protected HttpRequest self() {
        return this;
    }

    /**
     * Return the protocol version.
     */
    @Override
    public HttpVersion protocolVersion() {
        if (this.version == null) {
            this.version = HttpVersion.valueOf(this.versionText);
        }
        return version;
    }

    /**
     * Return the raw protocol version text.
     * @return raw protocol version text
     */
    public String protocolVersionText() {
        return this.versionText;
    }

    /**
     * Set the protocol version on the request line.
     * @param version protocol version
     * @return current request instance
     */
    @Override
    public HttpRequest protocolVersion(HttpVersion version) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        this.version = version;
        this.versionText = version.text();
        return this;
    }

    /**
     * Return the request method.
     */
    @Override
    public HttpMethod method() {
        if (this.method == null) {
            this.method = HttpMethod.valueOf(this.methodText);
        }
        return method;
    }

    /**
     * Return the raw request method text.
     * @return raw request method text
     */
    public String methodText() {
        return this.methodText;
    }

    /**
     * Set the request method on the request line.
     * @param method request method
     * @return current request instance
     */
    @Override
    public HttpRequest method(HttpMethod method) {
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        this.method = method;
        this.methodText = method.name();
        return this;
    }

    /**
     * Return the request target.
     */
    @Override
    public String uri() {
        if (this.uri == null) {
            this.uri = this.uriText;
        }
        return uri;
    }

    /**
     * Set the request target on the request line.
     * @param uri request target
     * @return current request instance
     */
    @Override
    public HttpRequest uri(String uri) {
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }
        this.uri = uri;
        this.uriText = uri;
        return this;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(version: " + protocolVersionText() + ", method: " + methodText() + ", uri: " + uri() + ", bad: " + this.isBad() + ')';
    }

    /**
     * Release the state held by this request object.
     */
    @Override
    public void release() {
        this.resetHttpObjectState();
        this.version = null;
        this.method = null;
        this.uri = null;
        this.versionText = null;
        this.methodText = null;
        this.uriText = null;
    }
}
