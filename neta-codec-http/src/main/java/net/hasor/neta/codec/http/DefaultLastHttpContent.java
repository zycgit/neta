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
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Default implementation of {@link LastHttpContent}.
 * Marks the end of an HTTP message body and may carry optional trailing headers.
 */
public class DefaultLastHttpContent implements LastHttpContent {
    /** A singleton empty last content with no trailing headers. */
    public static final LastHttpContent EMPTY_LAST_CONTENT = new DefaultLastHttpContent(ByteBuf.EMPTY, HttpHeaders.EMPTY);
    private final       ByteBuf         content;
    private final       HttpHeaders     trailingHeaders;
    private             int             streamId;

    /** Creates a new last content with empty body and no trailing headers. */
    public DefaultLastHttpContent() {
        this(ByteBuf.EMPTY, new HttpHeaders());
    }

    /**
     * Creates a new last content with the specified body data.
     * @param content the content data
     */
    public DefaultLastHttpContent(ByteBuf content) {
        this(content, new HttpHeaders());
    }

    /**
     * Creates a new last content with the specified body data and trailing headers.
     * @param content the content data
     * @param trailingHeaders the trailing headers (for chunked encoding)
     */
    public DefaultLastHttpContent(ByteBuf content, HttpHeaders trailingHeaders) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (trailingHeaders == null) {
            throw new IllegalArgumentException("trailingHeaders must not be null");
        }
        this.content = content;
        this.trailingHeaders = trailingHeaders;
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
    public ByteBuf content() {
        return content;
    }

    @Override
    public HttpHeaders trailerHeaders() {
        return trailingHeaders;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append("(data: ").append(content.readableBytes()).append(" bytes");
        if (!trailingHeaders.isEmpty()) {
            sb.append(", trailerHeaders: ").append(trailingHeaders);
        }
        sb.append(')');
        return sb.toString();
    }
}
