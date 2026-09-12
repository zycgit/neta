/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
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
    private final HttpRequest requestLine;
    private final HttpHeaders headers;
    private       ByteBuf     contentBuffer;

    /**
     * Create an aggregated request with empty content and empty headers.
     * @param version HTTP version
     * @param method HTTP method
     * @param uri request target
     */
    public DefaultFullHttpRequest(HttpVersion version, HttpMethod method, String uri) {
        this(new DefaultHttpRequest(version, method, uri), (DefaultHttpHeaders) null, null);
    }

    /**
     * Create an aggregated request with the specified content and empty headers.
     * @param version HTTP version
     * @param method HTTP method
     * @param uri request target
     * @param content aggregated payload
     */
    public DefaultFullHttpRequest(HttpVersion version, HttpMethod method, String uri, ByteBuf content) {
        this(new DefaultHttpRequest(version, method, uri), (DefaultHttpHeaders) null, content);
    }

    DefaultFullHttpRequest(HttpRequest requestLine, DefaultHttpHeaders headers, ByteBuf content) {
        if (requestLine == null) {
            throw new IllegalArgumentException("requestLine must not be null");
        }

        this.requestLine = requestLine;
        this.headers = headers != null ? headers : new DefaultHttpHeaders();
        this.contentBuffer = normalizeContent(content);
        this.inheritHttpObjectState(requestLine);
        if (this.headers.isBad()) {
            this.setBadState(this.headers.badReason());
        }
    }

    /**
     * Create an aggregated request from a request line, body payload, and zero or more header blocks.
     * Header blocks are merged internally in-order.
     * @param requestLine request line object
     * @param headerBlocks header blocks to merge into the final header view
     * @param content aggregated payload
     */
    public DefaultFullHttpRequest(DefaultHttpRequest requestLine, HttpHeaders[] headerBlocks, ByteBuf content) {
        this(requestLine, mergeHeaders(headerBlocks), content);
    }

    private static DefaultHttpHeaders mergeHeaders(HttpHeaders[] headers) {
        DefaultHttpHeaders merged = new DefaultHttpHeaders();
        if (headers == null) {
            return merged;
        }
        for (HttpHeaders headerBlock : headers) {
            if (headerBlock != null && headerBlock.isBad()) {
                merged.markBad(headerBlock.badReason());
            }

            merged.appendHeaders(headerBlock);
        }

        return merged;
    }

    @Override
    protected FullHttpRequest self() {
        return this;
    }

    @Override
    public FullHttpRequest streamId(long streamId) {
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

    DefaultHttpHeaders headerBlock() {
        return (DefaultHttpHeaders) this.headers;
    }

    //

    @Override
    public ByteBuf content() {
        return this.contentBuffer;
    }

    @Override
    public ByteBuf transferContent() {
        ByteBuf current = this.contentBuffer;
        this.contentBuffer = ByteBuf.EMPTY;
        return current == null ? ByteBuf.EMPTY : current;
    }

    /**
     * Append a content chunk to the aggregated payload.
     * <p>
     * Appending a {@link HttpContent} transfers its current payload ownership into
     * this full request. The wrapper itself remains independently releasable.
     * @param content content chunk to append
     */
    public void appendContent(HttpContent content) {
        if (content == null) {
            return;
        }

        this.contentBuffer = appendContent(this.contentBuffer, content.transferContent());
    }

    public void appendContent(ByteBuf content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }

        this.contentBuffer = appendContent(this.contentBuffer, content);
    }

    private static ByteBuf normalizeContent(ByteBuf content) {
        if (content == null) {
            return ByteBuf.EMPTY;
        }
        if (content.readableBytes() == 0) {
            if (content != ByteBuf.EMPTY) {
                content.release();
            }
            return ByteBuf.EMPTY;
        }
        return content;
    }

    static ByteBuf appendContent(ByteBuf current, ByteBuf incoming) {
        if (incoming == null) {
            return current == null ? ByteBuf.EMPTY : current;
        }

        if (incoming.readableBytes() == 0) {
            if (incoming != ByteBuf.EMPTY) {
                incoming.release();
            }
            return current == null ? ByteBuf.EMPTY : current;
        }

        if (current == null || current == ByteBuf.EMPTY) {
            return incoming;
        }

        if (current.readableBytes() == 0) {
            current.release();
            return incoming;
        }

        if (current instanceof CompositeByteBuf) {
            ((CompositeByteBuf) current).addComponent(incoming);
            return current;
        }

        CompositeByteBuf composite = ByteBufUtils.compositeBuffer(current.alloc() != null ? current.alloc() : incoming.alloc());
        composite.addComponent(current);
        composite.addComponent(incoming);
        return composite;
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
