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
 * Default implementation of {@link FullHttpResponse}.
 * <p>
 * This object represents an already aggregated response by combining the status line, the final
 * merged header view, and the aggregated content into one instance.
 * <p>
 * For a full response, callers observe only one final header view. Any header fields collected
 * during aggregation, including fields that originally appeared at the logical end of the message,
 * are exposed through the same {@link HttpHeaders} facade.
 */
public class DefaultFullHttpResponse implements FullHttpResponse {
    private final HttpResponse     responseLine;
    private final HttpHeaders      headers;
    private final CompositeByteBuf contentView;

    /**
     * Creates an aggregated response with an empty payload and an empty merged header view.
     * @param version the HTTP version
     * @param status the HTTP response status
     */
    public DefaultFullHttpResponse(HttpVersion version, HttpStatus status) {
        this(version, status, ByteBuf.EMPTY, new DefaultHttpHeaders(), new DefaultLastHttpHeaders());
    }

    /**
     * Creates an aggregated response with the specified payload and an empty merged header view.
     * @param version the HTTP version
     * @param status the HTTP response status
     * @param content the aggregated payload
     */
    public DefaultFullHttpResponse(HttpVersion version, HttpStatus status, ByteBuf content) {
        this(version, status, content, new DefaultHttpHeaders(), new DefaultLastHttpHeaders());
    }

    /**
     * Creates an aggregated response with the specified payload and merged header view.
     * @param version the HTTP version
     * @param status the HTTP response status
     * @param content the aggregated payload
     * @param headers the merged headers visible on the full response
     */
    public DefaultFullHttpResponse(HttpVersion version, HttpStatus status, ByteBuf content, DefaultHttpHeaders headers) {
        this(version, status, content, headers, new DefaultLastHttpHeaders());
    }

    public DefaultFullHttpResponse(HttpVersion version, HttpStatus status, ByteBuf content, DefaultHttpHeaders headers, DefaultHttpHeaders trailerHeaders) {
        this(new DefaultHttpResponse(version, status), headers, new DefaultHttpContent(content), trailerHeaders);
    }

    public DefaultFullHttpResponse(DefaultHttpResponse responseLine, DefaultHttpHeaders headers, DefaultHttpContent content) {
        this(responseLine, headers, content, new DefaultLastHttpHeaders());
    }

    public DefaultFullHttpResponse(DefaultHttpResponse responseLine, DefaultHttpHeaders headers, DefaultHttpContent content, DefaultHttpHeaders trailerHeaders) {
        if (responseLine == null) {
            throw new IllegalArgumentException("responseLine must not be null");
        }
        if (headers == null) {
            throw new IllegalArgumentException("headers must not be null");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }

        this.responseLine = responseLine;
        this.headers = headers;
        this.contentView = new CompositeByteBuf(ByteBufAllocator.DEFAULT);
        this.contentView.addComponent(content.content());
    }

    @Override
    public int streamId() {
        return this.responseLine.streamId();
    }

    @Override
    public FullHttpResponse streamId(int streamId) {
        this.responseLine.streamId(streamId);
        this.headers.streamId(streamId);
        return this;
    }

    //

    @Override
    public HttpVersion protocolVersion() {
        return this.responseLine.protocolVersion();
    }

    /** Sets the protocol version carried by this aggregated response. */
    public HttpResponse protocolVersion(HttpVersion version) {
        if (!(this.responseLine instanceof DefaultHttpResponse)) {
            throw new IllegalArgumentException("responseLine must be an instance of DefaultHttpResponse");
        }

        ((DefaultHttpResponse) this.responseLine).protocolVersion(version);
        return this;
    }

    public String protocolVersionText() {
        return this.responseLine.protocolVersion().text();
    }

    @Override
    public HttpStatus status() {
        return this.responseLine.status();
    }

    /** Sets the response status carried by this status line. */
    public HttpResponse status(HttpStatus status) {
        if (!(this.responseLine instanceof DefaultHttpResponse)) {
            throw new IllegalArgumentException("responseLine must be an instance of DefaultHttpResponse");
        }

        ((DefaultHttpResponse) this.responseLine).status(status);
        return this;
    }

    @Override
    public String statusText() {
        return this.responseLine.statusText();
    }

    @Override
    public String reasonText() {
        return this.responseLine.reasonText();
    }

    public HttpResponse reasonText(String reason) {
        if (!(this.responseLine instanceof DefaultHttpResponse)) {
            throw new IllegalArgumentException("responseLine must be an instance of DefaultHttpResponse");
        }

        ((DefaultHttpResponse) this.responseLine).reasonText(reason);
        return this;
    }

    //

    public FullHttpResponse addHeader(String name, String value) {
        if (!(this.headers instanceof DefaultHttpHeaders)) {
            throw new IllegalArgumentException("headers must be an instance of DefaultHttpHeaders");
        }

        ((DefaultHttpHeaders) this.headers).addHeader(name, value);
        return this;
    }

    public FullHttpResponse addHeader(CharSequence name, CharSequence value) {
        if (!(this.headers instanceof DefaultHttpHeaders)) {
            throw new IllegalArgumentException("headers must be an instance of DefaultHttpHeaders");
        }

        ((DefaultHttpHeaders) this.headers).addHeader(name, value);
        return this;
    }

    public FullHttpResponse setHeader(String name, String value) {
        if (!(this.headers instanceof DefaultHttpHeaders)) {
            throw new IllegalArgumentException("headers must be an instance of DefaultHttpHeaders");
        }

        ((DefaultHttpHeaders) this.headers).setHeader(name, value);
        return this;
    }

    public FullHttpResponse setHeader(CharSequence name, CharSequence value) {
        if (!(this.headers instanceof DefaultHttpHeaders)) {
            throw new IllegalArgumentException("headers must be an instance of DefaultHttpHeaders");
        }

        ((DefaultHttpHeaders) this.headers).setHeader(name, value);
        return this;
    }

    public FullHttpResponse clearHeader() {
        if (!(this.headers instanceof DefaultHttpHeaders)) {
            throw new IllegalArgumentException("headers must be an instance of DefaultHttpHeaders");
        }

        ((DefaultHttpHeaders) this.headers).clearHeader();
        return this;
    }

    public FullHttpResponse removeHeader(String name) {
        if (!(this.headers instanceof DefaultHttpHeaders)) {
            throw new IllegalArgumentException("headers must be an instance of DefaultHttpHeaders");
        }

        ((DefaultHttpHeaders) this.headers).removeHeader(name);
        return this;
    }

    public FullHttpResponse appendHeaders(HttpHeaders headers) {
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
        return this.contentView;
    }

    /**
     * Appends one body chunk into the aggregated content view.
     * <p>
     * The underlying {@link ByteBuf} is retained by the internal {@link CompositeByteBuf}, so the
     * caller may release the original {@link HttpContent} after this method returns. This is a
     * zero-copy ownership transfer by reference count, not a byte copy.
     */
    public void appendContent(HttpContent content) {
        if (content == null) {
            return;
        }

        this.contentView.addComponent(content.content());
    }

    //

    @Override
    public String toString() {
        ByteBuf currentContent = this.content();
        int readableBytes = currentContent != null ? currentContent.readableBytes() : 0;
        return getClass().getSimpleName() + "(version: " + protocolVersionText() + ", status: " + statusText() + ' ' + reasonText() + ", headers: " + headerSize() + ", content: " + readableBytes + " bytes)";
    }

    @Override
    public void release() {
        this.responseLine.release();
        this.headers.release();
        this.contentView.release();
    }
}
