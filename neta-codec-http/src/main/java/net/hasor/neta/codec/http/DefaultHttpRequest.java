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
 * This object holds only the request line fields. Header blocks and body chunks are represented
 * by separate {@link HttpHeaders} and {@link HttpContent} objects later in the message flow.
 */
public class DefaultHttpRequest implements HttpRequest {
    private int         streamId;
    private HttpVersion version;
    private HttpMethod  method;
    private String      uri;
    private String      versionText;
    private String      methodText;
    private String      uriText;
    private boolean     bad;
    private String      badReason;

    /**
     * Creates a request start-line object.
     * @param version the HTTP version
     * @param method the HTTP method
     * @param uri the request target
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
     * Creates a request start-line object from parsed text fields.
     * @param version the raw protocol version text
     * @param method the raw request method text
     * @param uri the raw request target text
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
    public int streamId() {
        return streamId;
    }

    @Override
    public HttpRequest streamId(int streamId) {
        this.streamId = streamId;
        return this;
    }

    @Override
    public boolean isBad() {
        return this.bad;
    }

    @Override
    public String badReason() {
        return this.badReason;
    }

    @Override
    public HttpRequest markBad(String reason) {
        this.bad = true;
        this.badReason = reason;
        return this;
    }

    @Override
    public HttpVersion protocolVersion() {
        if (this.version == null) {
            this.version = HttpVersion.valueOf(this.versionText);
        }
        return version;
    }

    public String protocolVersionText() {
        return this.versionText;
    }

    /** Sets the protocol version carried by this request line. */
    @Override
    public HttpRequest protocolVersion(HttpVersion version) {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        this.version = version;
        this.versionText = version.text();
        return this;
    }

    @Override
    public HttpMethod method() {
        if (this.method == null) {
            this.method = HttpMethod.valueOf(this.methodText);
        }
        return method;
    }

    public String methodText() {
        return this.methodText;
    }

    /** Sets the request method carried by this request line. */
    @Override
    public HttpRequest method(HttpMethod method) {
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        this.method = method;
        this.methodText = method.name();
        return this;
    }

    @Override
    public String uri() {
        if (this.uri == null) {
            this.uri = this.uriText;
        }
        return uri;
    }

    /** Sets the request target carried by this request line. */
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
        return getClass().getSimpleName() + "(version: " + protocolVersionText() + ", method: " + methodText() + ", uri: " + uri() + ", bad: " + this.bad + ')';
    }

    @Override
    public void release() {
        this.streamId = 0;
        this.version = null;
        this.method = null;
        this.uri = null;
        this.versionText = null;
        this.methodText = null;
        this.uriText = null;
        this.bad = false;
        this.badReason = null;
    }
}
