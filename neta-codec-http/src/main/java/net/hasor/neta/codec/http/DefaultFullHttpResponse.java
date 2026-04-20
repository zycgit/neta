/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
 * Default implementation of {@link FullHttpResponse}.
 * <p>
 * This object represents a fully aggregated response and combines the status line,
 * header view, and aggregated content in a single instance.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class DefaultFullHttpResponse extends AbstractHttpObject<FullHttpResponse> implements FullHttpResponse {
    private final HttpResponse responseLine;
    private final HttpHeaders  headers;
    private CompositeByteBuf   contentBuffer;

    /**
     * Create an aggregated response with empty content and empty headers.
     * @param version HTTP version
     * @param status HTTP response status
     */
    public DefaultFullHttpResponse(HttpVersion version, HttpStatus status) {
        this(new DefaultHttpResponse(version, status), ByteBuf.EMPTY);
    }

    /**
     * Create an aggregated response with the specified content and empty headers.
     * @param version HTTP version
     * @param status HTTP response status
     * @param content aggregated payload
     */
    public DefaultFullHttpResponse(HttpVersion version, HttpStatus status, ByteBuf content) {
        this(new DefaultHttpResponse(version, status), content);
    }

    /**
     * Create an aggregated response from a status line, body payload, and zero or more header blocks.
     * Header blocks are merged internally in-order.
     * @param responseLine status line object
     * @param content aggregated payload
     * @param headerBlocks header blocks to merge into the final header view
     */
    public DefaultFullHttpResponse(DefaultHttpResponse responseLine, ByteBuf content, HttpHeaders... headerBlocks) {
        if (responseLine == null) {
            throw new IllegalArgumentException("responseLine must not be null");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }

        this.responseLine = responseLine;
        this.headers = mergeHeaders(this, headerBlocks);
        this.contentBuffer = ByteBufUtils.compositeBuffer();
        this.contentBuffer.addComponent(content);
        this.inheritHttpObjectState(responseLine);
    }

    private static HttpHeaders mergeHeaders(DefaultFullHttpResponse response, HttpHeaders[] headers) {
        DefaultHttpHeaders merged = new DefaultHttpHeaders();
        if (headers == null) {
            return merged;
        }
        for (HttpHeaders headerBlock : headers) {
            if (headerBlock != null && headerBlock.isBad()) {
                response.setBadState(headerBlock.badReason());
            }

            merged.appendHeaders(headerBlock);
        }

        return merged;
    }

    @Override
    protected FullHttpResponse self() {
        return this;
    }

    @Override
    public FullHttpResponse streamId(long streamId) {
        super.streamId(streamId);
        this.responseLine.streamId(streamId);
        this.headers.streamId(streamId);
        return this;
    }

    @Override
    public FullHttpResponse markBad(String reason) {
        super.markBad(reason);
        this.responseLine.markBad(reason);
        return this;
    }

    //

    @Override
    public HttpVersion protocolVersion() {
        return this.responseLine.protocolVersion();
    }

    /**
     * Set the protocol version on the aggregated response.
     * @param version protocol version
     * @return current response instance
     */
    @Override
    public HttpResponse protocolVersion(HttpVersion version) {
        this.responseLine.protocolVersion(version);
        return this;
    }

    public String protocolVersionText() {
        return this.responseLine.protocolVersion().text();
    }

    @Override
    public HttpStatus status() {
        return this.responseLine.status();
    }

    /**
     * Set the response status on the aggregated response.
     * @param status response status
     * @return current response instance
     */
    @Override
    public HttpResponse status(HttpStatus status) {
        this.responseLine.status(status);
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

    @Override
    public HttpResponse reasonText(String reason) {
        this.responseLine.reasonText(reason);
        return this;
    }

    //

    @Override
    public FullHttpResponse addHeader(String name, String value) {
        this.headers.addHeader(name, value);
        return this;
    }

    @Override
    public FullHttpResponse setHeader(String name, String value) {
        this.headers.setHeader(name, value);
        return this;
    }

    @Override
    public FullHttpResponse clearHeader() {
        this.headers.clearHeader();
        return this;
    }

    @Override
    public FullHttpResponse removeHeader(String name) {
        this.headers.removeHeader(name);
        return this;
    }

    @Override
    public FullHttpResponse appendHeaders(HttpHeaders headers) {
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
        CompositeByteBuf current = this.contentBuffer;
        this.contentBuffer = ByteBufUtils.compositeBuffer();
        return current;
    }

    /**
     * Append a content chunk to the aggregated payload.
     * <p>
     * Appending a {@link HttpContent} transfers its current payload ownership into
     * this full response. The wrapper itself remains independently releasable.
     * @param content content chunk to append
     */
    public void appendContent(HttpContent content) {
        if (content == null) {
            return;
        }

        this.contentBuffer.addComponent(content.transferContent());
    }

    public void appendContent(ByteBuf content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }

        this.contentBuffer.addComponent(content);
    }

    //

    @Override
    public String toString() {
        ByteBuf currentContent = this.content();
        int readableBytes = currentContent != null ? currentContent.readableBytes() : 0;
        return getClass().getSimpleName() + "(version: " + protocolVersionText() + ", status: " + statusText() + ' ' + reasonText() + ", headers: " + headerSize() + ", content: " + readableBytes + " bytes)";
    }

    /**
     * Release all state and buffers held by this aggregated response.
     */
    @Override
    public void release() {
        this.responseLine.release();
        this.headers.release();
        this.contentBuffer.release();
        this.resetHttpObjectState();
    }
}
