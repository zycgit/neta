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
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.bytebuf.CompositeByteBuf;

/**
 * Default implementation of {@link FullHttpRequest}.
 * <p>
 * This object represents a fully aggregated request and combines the request line,
 * header view, and aggregated content in a single instance.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class DefaultFullHttpRequest extends AbstractHttpObject<FullHttpRequest> implements FullHttpRequest {
    private final HttpRequest      requestLine;
    private final HttpHeaders      headers;
    private final CompositeByteBuf contentBuffer;

    /**
     * Create an aggregated request with empty content and empty headers.
     * @param version HTTP version
     * @param method HTTP method
     * @param uri request target
     */
    public DefaultFullHttpRequest(HttpVersion version, HttpMethod method, String uri) {
        this(version, method, uri, ByteBuf.EMPTY, new DefaultHttpHeaders(), new DefaultLastHttpHeaders());
    }

    /**
     * Create an aggregated request with the specified content and empty headers.
     * @param version HTTP version
     * @param method HTTP method
     * @param uri request target
     * @param content aggregated payload
     */
    public DefaultFullHttpRequest(HttpVersion version, HttpMethod method, String uri, ByteBuf content) {
        this(version, method, uri, content, new DefaultHttpHeaders(), new DefaultLastHttpHeaders());
    }

    /**
     * Create an aggregated request with the specified content and headers.
     * @param version HTTP version
     * @param method HTTP method
     * @param uri request target
     * @param content aggregated payload
     * @param headers final header block
     */
    public DefaultFullHttpRequest(HttpVersion version, HttpMethod method, String uri, ByteBuf content, DefaultHttpHeaders headers) {
        this(version, method, uri, content, headers, new DefaultLastHttpHeaders());
    }

    /**
     * Create an aggregated request with the specified content, headers, and trailing headers.
     * @param version HTTP version
     * @param method HTTP method
     * @param uri request target
     * @param content aggregated payload
     * @param headers request header view
     * @param trailerHeaders trailing header view
     */
    public DefaultFullHttpRequest(HttpVersion version, HttpMethod method, String uri, ByteBuf content, DefaultHttpHeaders headers, DefaultHttpHeaders trailerHeaders) {
        this(new DefaultHttpRequest(version, method, uri), headers, new DefaultHttpContent(content), trailerHeaders);
    }

    /**
     * Create an aggregated request from a request line, headers, and content object.
     * @param requestLine request line object
     * @param headers request header view
     * @param content aggregated content object
     */
    public DefaultFullHttpRequest(DefaultHttpRequest requestLine, DefaultHttpHeaders headers, DefaultHttpContent content) {
        this(requestLine, headers, content, new DefaultLastHttpHeaders());
    }

    /**
     * Create an aggregated request from a request line, headers, content object,
     * and trailing headers.
     * @param requestLine request line object
     * @param headers request header view
     * @param content aggregated content object
     * @param trailerHeaders trailing header view
     */
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
        this.contentBuffer = ByteBufUtils.compositeBuffer();
        this.contentBuffer.addComponent(content.content());
        this.inheritHttpObjectState(requestLine);
    }

    @Override
    protected FullHttpRequest self() {
        return this;
    }

    @Override
    public FullHttpRequest streamId(int streamId) {
        super.streamId(streamId);
        this.requestLine.streamId(streamId);
        this.headers.streamId(streamId);
        return this;
    }

    //

    @Override
    public HttpVersion protocolVersion() {
        return this.requestLine.protocolVersion();
    }

    /**
     * Set the protocol version on the aggregated request.
     * @param version protocol version
     * @return current request instance
     */
    @Override
    public FullHttpRequest protocolVersion(HttpVersion version) {
        this.requestLine.protocolVersion(version);
        return this;
    }

    public String protocolVersionText() {
        return this.requestLine.protocolVersion().text();
    }

    @Override
    public HttpMethod method() {
        return this.requestLine.method();
    }

    /**
     * Set the request method on the aggregated request.
     * @param method request method
     * @return current request instance
     */
    @Override
    public FullHttpRequest method(HttpMethod method) {
        this.requestLine.method(method);
        return this;
    }

    public String methodText() {
        return this.requestLine.method().name();
    }

    @Override
    public String uri() {
        return this.requestLine.uri();
    }

    /**
     * Set the request target on the aggregated request.
     * @param uri request target
     * @return current request instance
     */
    @Override
    public FullHttpRequest uri(String uri) {
        this.requestLine.uri(uri);
        return this;
    }

    //

    @Override
    public FullHttpRequest addHeader(String name, String value) {
        this.headers.addHeader(name, value);
        return this;
    }

    @Override
    public FullHttpRequest setHeader(String name, String value) {
        this.headers.setHeader(name, value);
        return this;
    }

    @Override
    public FullHttpRequest clearHeader() {
        this.headers.clearHeader();
        return this;
    }

    @Override
    public FullHttpRequest removeHeader(String name) {
        this.headers.removeHeader(name);
        return this;
    }

    @Override
    public FullHttpRequest appendHeaders(HttpHeaders headers) {
        this.headers.appendHeaders(headers);
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
     * Append a content chunk to the aggregated payload.
     * <p>
     * The chunk data is added directly to the internal composite buffer while
     * reusing the original payload buffer. After appending, the caller transfers
     * responsibility for releasing that chunk payload to this object.
     * @param content content chunk to append
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
        return getClass().getSimpleName() + "(version: " + protocolVersionText() + ", method: " + methodText() + ", uri: " + uri() + ", headers: " + headerSize() + ", content: " + readableBytes + " bytes, bad: " + this.isBad() + ")";
    }

    /**
     * Release all state and buffers held by this aggregated request.
     */
    @Override
    public void release() {
        this.requestLine.release();
        this.headers.release();
        this.contentBuffer.release();
        this.resetHttpObjectState();
    }
}
