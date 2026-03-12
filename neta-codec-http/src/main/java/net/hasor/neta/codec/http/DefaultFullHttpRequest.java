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
import java.util.List;
import java.util.Set;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.CompositeByteBuf;

/**
 * Default implementation of {@link FullHttpRequest}.
 * <p>
 * This object represents an already aggregated request by combining the request line, the final
 * header block, and the final content block into one instance.
 */
public class DefaultFullHttpRequest implements FullHttpRequest {
    private final HttpRequest      requestLine;
    private final HttpHeaders      headers;
    private final CompositeByteBuf contentBuffer;

    /**
     * Creates an aggregated request with an empty payload and an empty final header block.
     * @param version the HTTP version
     * @param method the HTTP method
     * @param uri the request target
     */
    public DefaultFullHttpRequest(HttpVersion version, HttpMethod method, String uri) {
        this(version, method, uri, ByteBuf.EMPTY, new DefaultHttpHeaders(), new DefaultLastHttpHeaders());
    }

    /**
     * Creates an aggregated request with the specified payload and an empty final header block.
     * @param version the HTTP version
     * @param method the HTTP method
     * @param uri the request target
     * @param content the aggregated payload
     */
    public DefaultFullHttpRequest(HttpVersion version, HttpMethod method, String uri, ByteBuf content) {
        this(version, method, uri, content, new DefaultHttpHeaders(), new DefaultLastHttpHeaders());
    }

    /**
     * Creates an aggregated request with the specified payload and final header block.
     * @param version the HTTP version
     * @param method the HTTP method
     * @param uri the request target
     * @param content the aggregated payload
     * @param headers the final header block
     */
    public DefaultFullHttpRequest(HttpVersion version, HttpMethod method, String uri, ByteBuf content, DefaultHttpHeaders headers) {
        this(version, method, uri, content, headers, new DefaultLastHttpHeaders());
    }

    public DefaultFullHttpRequest(HttpVersion version, HttpMethod method, String uri, ByteBuf content, DefaultHttpHeaders headers, DefaultHttpHeaders trailerHeaders) {
        this(new DefaultHttpRequest(version, method, uri), headers, new DefaultHttpContent(content), trailerHeaders);
    }

    public DefaultFullHttpRequest(DefaultHttpRequest requestLine, DefaultHttpHeaders headers, DefaultHttpContent content) {
        this(requestLine, headers, content, new DefaultLastHttpHeaders());
    }

    public DefaultFullHttpRequest(DefaultHttpRequest requestLine, DefaultHttpHeaders headers, DefaultHttpContent content, DefaultHttpHeaders trailerHeaders) {
        if (requestLine == null) {
            throw new IllegalArgumentException("requestLine must not be null");
        }
        if (headers == null) {
            throw new IllegalArgumentException("headers must not be null");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }

        this.requestLine = requestLine;
        this.headers = headers;
        this.contentBuffer = new CompositeByteBuf(ByteBufAllocator.DEFAULT);
        this.contentBuffer.addComponent(content.content());
    }

    @Override
    public int streamId() {
        return this.requestLine.streamId();
    }

    @Override
    public FullHttpRequest streamId(int streamId) {
        this.requestLine.streamId(streamId);
        this.headers.streamId(streamId);
        return this;
    }

    //

    @Override
    public HttpVersion protocolVersion() {
        return this.requestLine.protocolVersion();
    }

    /** Sets the protocol version carried by this aggregated request. */
    public FullHttpRequest protocolVersion(HttpVersion version) {
        if (!(this.requestLine instanceof DefaultHttpRequest)) {
            throw new IllegalArgumentException("requestLine must be an instance of DefaultHttpRequest");
        }

        ((DefaultHttpRequest) this.requestLine).protocolVersion(version);
        return this;
    }

    public String protocolVersionText() {
        return this.requestLine.protocolVersion().text();
    }

    @Override
    public HttpMethod method() {
        return this.requestLine.method();
    }

    /** Sets the request method carried by this aggregated request. */
    public FullHttpRequest method(HttpMethod method) {
        if (!(this.requestLine instanceof DefaultHttpRequest)) {
            throw new IllegalArgumentException("requestLine must be an instance of DefaultHttpRequest");
        }

        ((DefaultHttpRequest) this.requestLine).method(method);
        return this;
    }

    public String methodText() {
        return this.requestLine.method().name();
    }

    @Override
    public String uri() {
        return this.requestLine.uri();
    }

    /** Sets the request target carried by this request line. */
    public FullHttpRequest uri(String uri) {
        if (!(this.requestLine instanceof DefaultHttpRequest)) {
            throw new IllegalArgumentException("requestLine must be an instance of DefaultHttpRequest");
        }

        ((DefaultHttpRequest) this.requestLine).uri(uri);
        return this;
    }

    //

    public FullHttpRequest addHeader(String name, String value) {
        if (!(this.headers instanceof DefaultHttpHeaders)) {
            throw new IllegalArgumentException("headers must be an instance of DefaultHttpHeaders");
        }

        ((DefaultHttpHeaders) this.headers).addHeader(name, value);
        return this;
    }

    public FullHttpRequest setHeader(String name, String value) {
        if (!(this.headers instanceof DefaultHttpHeaders)) {
            throw new IllegalArgumentException("headers must be an instance of DefaultHttpHeaders");
        }

        ((DefaultHttpHeaders) this.headers).setHeader(name, value);
        return this;
    }

    public FullHttpRequest clearHeader() {
        if (!(this.headers instanceof DefaultHttpHeaders)) {
            throw new IllegalArgumentException("headers must be an instance of DefaultHttpHeaders");
        }

        ((DefaultHttpHeaders) this.headers).clearHeader();
        return this;
    }

    public FullHttpRequest removeHeader(String name) {
        if (!(this.headers instanceof DefaultHttpHeaders)) {
            throw new IllegalArgumentException("headers must be an instance of DefaultHttpHeaders");
        }

        ((DefaultHttpHeaders) this.headers).removeHeader(name);
        return this;
    }

    public FullHttpRequest appendHeaders(HttpHeaders headers) {
        if (!(this.headers instanceof DefaultHttpHeaders)) {
            throw new IllegalArgumentException("headers must be an instance of DefaultHttpHeaders");
        }

        ((DefaultHttpHeaders) this.headers).appendHeaders(headers);
        return this;
    }

    @Override
    public List<String> getValues(String name) {
        return this.headers.getValues(name);
    }

    @Override
    public String getString(String name) {
        return this.headers.getString(name);
    }

    @Override
    public int getInt(String name, int defaultValue) {
        return this.headers.getInt(name, defaultValue);
    }

    @Override
    public long getLong(String name, long defaultValue) {
        return this.headers.getLong(name, defaultValue);
    }

    @Override
    public boolean containsHeader(String name) {
        return this.headers.containsHeader(name);
    }

    @Override
    public Set<String> headerNames() {
        return this.headers.headerNames();
    }

    @Override
    public int headerSize() {
        return this.headers.headerSize();
    }

    //

    @Override
    public ByteBuf content() {
        return this.contentBuffer;
    }

    /**
     * Appends one body chunk into the aggregated content.
     * <p>
     * The chunk data is added to the internal aggregated content buffer. Callers may release the
     * original {@link HttpContent} after this method returns.
     */
    public void appendContent(HttpContent content) {
        if (content == null) {
            return;
        }

        this.contentBuffer.addComponent(content.content());
    }

    //

    @Override
    public String toString() {
        ByteBuf currentContent = this.content();
        int readableBytes = currentContent != null ? currentContent.readableBytes() : 0;
        return getClass().getSimpleName() + "(version: " + protocolVersionText() + ", method: " + methodText() + ", uri: " + uri() + ", headers: " + headerSize() + ", content: " + readableBytes + " bytes)";
    }

    @Override
    public void release() {
        this.requestLine.release();
        this.headers.release();
        this.contentBuffer.release();
    }
}
