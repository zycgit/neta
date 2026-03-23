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
package net.hasor.neta.codec.http.websocket;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.CompositeByteBuf;
import net.hasor.neta.codec.http.*;

/**
 * Incremental collector for staged HTTP handshake parts.
 * <p>
 * Aggregates request or response line, headers, and body into one reusable handshake snapshot.
 */
final class HttpMessageParts {
    private HttpVersion        protocolVersion;
    private HttpMethod         method;
    private HttpStatus         status;
    private String             uri;
    private int                streamId;
    private DefaultHttpHeaders headers;
    private CompositeByteBuf   body;
    private boolean            active;
    private boolean            complete;

    boolean isActive() {
        return this.active;
    }

    boolean isComplete() {
        return this.complete;
    }

    void appendRequest(HttpObject msg) {
        boolean aggregateLike = msg instanceof HttpRequest && msg instanceof HttpContent;
        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;
            this.protocolVersion = request.protocolVersion();
            this.method = request.method();
            this.uri = request.uri();
            this.streamId = request.streamId();
            this.active = true;
        }
        if (msg instanceof HttpHeaders) {
            this.headers().appendHeaders((HttpHeaders) msg);
        }
        if (msg instanceof HttpContent) {
            this.appendBody(((HttpContent) msg).content());
        }
        if (msg instanceof LastHttpContent || aggregateLike) {
            this.complete = true;
        }
    }

    void appendResponse(HttpObject msg) {
        boolean aggregateLike = msg instanceof HttpResponse && msg instanceof HttpContent;
        if (msg instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) msg;
            this.protocolVersion = response.protocolVersion();
            this.status = response.status();
            this.streamId = response.streamId();
            this.active = true;
        }
        if (msg instanceof HttpHeaders) {
            this.headers().appendHeaders((HttpHeaders) msg);
        }
        if (msg instanceof HttpContent) {
            this.appendBody(((HttpContent) msg).content());
        }
        if (msg instanceof LastHttpContent || aggregateLike) {
            this.complete = true;
        }
    }

    HttpVersion protocolVersion() {
        return this.protocolVersion;
    }

    HttpMethod method() {
        return this.method;
    }

    HttpStatus status() {
        return this.status;
    }

    String uri() {
        return this.uri;
    }

    int streamId() {
        return this.streamId;
    }

    String header(String name) {
        return this.headers == null ? null : this.headers.getString(name);
    }

    HttpHeaders headersSnapshot() {
        DefaultHttpHeaders copy = new DefaultHttpHeaders();
        if (this.headers != null) {
            copy.appendHeaders(this.headers);
        }
        return copy;
    }

    ByteBuf body() {
        return this.body == null ? ByteBuf.EMPTY : this.body;
    }

    void reset() {
        if (this.body != null) {
            this.body.free();
        }
        this.protocolVersion = null;
        this.method = null;
        this.status = null;
        this.uri = null;
        this.streamId = 0;
        this.headers = null;
        this.body = null;
        this.active = false;
        this.complete = false;
    }

    private DefaultHttpHeaders headers() {
        if (this.headers == null) {
            this.headers = new DefaultHttpHeaders();
        }
        return this.headers;
    }

    private void appendBody(ByteBuf content) {
        if (content == null || content.readableBytes() == 0) {
            return;
        }
        if (this.body == null) {
            this.body = new CompositeByteBuf(ByteBufAllocator.DEFAULT);
        }
        this.body.addComponent(cloneContent(content));
    }

    private static ByteBuf cloneContent(ByteBuf source) {
        if (source == null || source.readableBytes() == 0) {
            return ByteBuf.EMPTY;
        }
        int length = source.readableBytes();
        byte[] copied = new byte[length];
        source.getBytes(0, copied, 0, length);
        ByteBuf target = ByteBufAllocator.DEFAULT.buffer(length, Integer.MAX_VALUE);
        target.writeBytes(copied, 0, copied.length);
        target.markWriter();
        return target;
    }
}