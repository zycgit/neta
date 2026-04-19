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
package net.hasor.nhttp.server.connector;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.codec.http.*;

/**
 * HTTP/1.1 implementation of {@link ResponseSink}.
 * <p>For streaming responses, appends {@code Transfer-Encoding: chunked} to the headers.
 * The terminal chunk (last=true) sends a zero-length {@link LastHttpContent}.</p>
 * @author 赵永春 (zyc@hasor.net)
 */
class Http1ResponseSink implements ResponseSink {
    private final ProtoContext context;
    private final HttpVersion  version;

    Http1ResponseSink(ProtoContext context, HttpVersion version) {
        this.context = context;
        this.version = version != null ? version : HttpVersion.HTTP_1_1;
    }

    @Override
    public void sendHeaders(int statusCode, HttpHeaders headers, boolean streaming) {
        HttpStatus status = HttpStatus.valueOf(statusCode);
        DefaultHttpResponse response = new DefaultHttpResponse(this.version, status);
        DefaultLastHttpHeaders headerBlock = new DefaultLastHttpHeaders();
        headerBlock.appendHeaders(headers);
        if (streaming) {
            headerBlock.setHeader(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            headerBlock.removeHeader(HttpHeaderNames.CONTENT_LENGTH);
        }
        this.context.sendEncoded(response);
        this.context.sendEncoded(headerBlock);
    }

    @Override
    public void sendContent(ByteBuf content, boolean last) {
        if (last) {
            ByteBuf payload = (content != null && content.readableBytes() > 0) ? content : ByteBuf.EMPTY;
            DefaultLastHttpContent lastContent = new DefaultLastHttpContent(payload);
            this.context.sendEncoded(lastContent);
        } else {
            if (content != null && content.readableBytes() > 0) {
                this.context.sendEncoded(new DefaultHttpContent(content));
            }
        }
    }

    @Override
    public void flush() {
        this.context.flush();
    }

    @Override
    public HttpVersion protocolVersion() {
        return this.version;
    }
}
