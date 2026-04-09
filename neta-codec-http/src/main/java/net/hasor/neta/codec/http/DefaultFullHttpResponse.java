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
 * Default implementation of {@link FullHttpResponse}.
 * <p>
 * This object represents a fully aggregated response and combines the status line,
 * header view, and aggregated content in a single instance.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public class DefaultFullHttpResponse extends AbstractHttpObject<FullHttpResponse> implements FullHttpResponse {
    private final HttpResponse     responseLine;
    private final HttpHeaders      headers;
    private final CompositeByteBuf contentBuffer;

    /**
     * Create an aggregated response with empty content and empty headers.
     * @param version HTTP version
     * @param status HTTP response status
     */
    public DefaultFullHttpResponse(HttpVersion version, HttpStatus status) {
        this(version, status, ByteBuf.EMPTY, new DefaultHttpHeaders(), new DefaultLastHttpHeaders());
    }

    /**
     * Create an aggregated response with the specified content and empty headers.
     * @param version HTTP version
     * @param status HTTP response status
     * @param content aggregated payload
     */
    public DefaultFullHttpResponse(HttpVersion version, HttpStatus status, ByteBuf content) {
        this(version, status, content, new DefaultHttpHeaders(), new DefaultLastHttpHeaders());
    }

    /**
     * Create an aggregated response with the specified content and headers.
     * @param version HTTP version
     * @param status HTTP response status
     * @param content aggregated payload
     * @param headers complete response header set
     */
    public DefaultFullHttpResponse(HttpVersion version, HttpStatus status, ByteBuf content, DefaultHttpHeaders headers) {
        this(version, status, content, headers, new DefaultLastHttpHeaders());
    }

    /**
     * Create an aggregated response with the specified content, headers, and trailing headers.
     * @param version HTTP version
     * @param status HTTP response status
     * @param content aggregated payload
     * @param headers response header view
     * @param trailerHeaders trailing header view
     */
    public DefaultFullHttpResponse(HttpVersion version, HttpStatus status, ByteBuf content, DefaultHttpHeaders headers, DefaultHttpHeaders trailerHeaders) {
        this(new DefaultHttpResponse(version, status), headers, new DefaultHttpContent(content), trailerHeaders);
    }

    /**
     * Create an aggregated response from a status line, headers, and content object.
     * @param responseLine status line object
     * @param headers response header view
     * @param content aggregated content object
     */
    public DefaultFullHttpResponse(DefaultHttpResponse responseLine, DefaultHttpHeaders headers, DefaultHttpContent content) {
        this(responseLine, headers, content, new DefaultLastHttpHeaders());
    }

    /**
     * Create an aggregated response from a status line, headers, content object,
     * and trailing headers.
     * @param responseLine status line object
     * @param headers response header view
     * @param content aggregated content object
     * @param trailerHeaders trailing header view
     */
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
        this.contentBuffer = ByteBufUtils.compositeBuffer();
        this.contentBuffer.addComponent(content.content());
        this.inheritHttpObjectState(responseLine);
        if (!this.isBad() && headers.isBad()) {
            this.setBadState(headers.badReason());
        } else if (!this.isBad() && content.isBad()) {
            this.setBadState(content.badReason());
        }
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
