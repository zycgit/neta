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
 * HTTP/2 implementation of {@link ResponseSink}.
 * <p>Writes HEADERS frames and DATA frames via the neta HTTP/2 codec. The {@code streamId} is
 * carried on every {@link HttpObject} via {@link HttpObject#streamId(long)}, and the per-stream
 * partition in neta routes it back to the correct client stream.</p>
 * <p>{@code Transfer-Encoding: chunked} is never added — HTTP/2 does not use it.</p>
 * @author 赵永春 (zyc@hasor.net)
 */
class Http2ResponseSink implements ResponseSink {
    private final ProtoContext context;
    private final long         streamId;

    Http2ResponseSink(ProtoContext context, long streamId) {
        this.context = context;
        this.streamId = streamId;
    }

    @Override
    public void sendHeaders(int statusCode, HttpHeaders headers, boolean streaming) {
        HttpStatus status = HttpStatus.valueOf(statusCode);
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_2_0, status);
        response.streamId(this.streamId);
        DefaultLastHttpHeaders headerBlock = new DefaultLastHttpHeaders();
        headerBlock.streamId(this.streamId);
        headerBlock.appendHeaders(headers);
        headerBlock.removeHeader(HttpHeaderNames.TRANSFER_ENCODING);
        this.context.sendEncoded(new Object[] { response, headerBlock });
    }

    @Override
    public void sendContent(ByteBuf content, boolean last) {
        if (last) {
            ByteBuf payload = (content != null && content.readableBytes() > 0) ? content : ByteBuf.EMPTY;
            DefaultLastHttpContent lastContent = new DefaultLastHttpContent(payload);
            lastContent.streamId(this.streamId);
            this.context.sendEncoded(lastContent);
        } else {
            if (content != null && content.readableBytes() > 0) {
                DefaultHttpContent chunk = new DefaultHttpContent(content);
                chunk.streamId(this.streamId);
                this.context.sendEncoded(chunk);
            }
        }
    }

    @Override
    public void flush() {
        this.context.flush();
    }

    @Override
    public HttpVersion protocolVersion() {
        return HttpVersion.HTTP_2_0;
    }
}
