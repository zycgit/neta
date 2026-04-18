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
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.bytebuf.CompositeByteBuf;
import net.hasor.neta.codec.http.*;

/**
 * Incremental aggregator for HTTP request and response fragments used during websocket handshake.
 * <p>
 * It captures the start line, headers, and body into a reusable handshake snapshot.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-22
 */
final class HttpMessageParts {
    private HttpVersion        protocolVersion;
    private HttpMethod         method;
    private HttpStatus         status;
    private String             uri;
    private long               streamId;
    private DefaultHttpHeaders headers;
    private CompositeByteBuf   body;
    private boolean            active;
    private boolean            complete;

    /**
     * Determine whether aggregation has started for the current message.
     * @return {@code true} once at least one start-line fragment has been seen
     */
    public boolean isActive() {
        return this.active;
    }

    /**
     * Determine whether the current aggregated message has reached its end marker.
     * @return {@code true} when the request or response is complete
     */
    public boolean isComplete() {
        return this.complete;
    }

    /**
     * Return the aggregated protocol version.
     * @return request or response protocol version
     */
    public HttpVersion protocolVersion() {
        return this.protocolVersion;
    }

    /**
     * Return the aggregated request method.
     * @return request method, or {@code null} for responses
     */
    public HttpMethod method() {
        return this.method;
    }

    /**
     * Return the aggregated response status.
     * @return response status, or {@code null} for requests
     */
    public HttpStatus status() {
        return this.status;
    }

    /**
     * Return the aggregated request URI.
     * @return request URI, or {@code null} for responses
     */
    public String uri() {
        return this.uri;
    }

    /**
     * Return the stream identifier associated with the aggregated message.
     * @return stream identifier
     */
    public long streamId() {
        return this.streamId;
    }

    /**
     * Return one header value from the aggregated header set.
     * @param name header name to resolve
     * @return header value, or {@code null} when absent
     */
    public String header(String name) {
        return this.headers == null ? null : this.headers.getString(name);
    }

    /**
     * Create a defensive copy of the aggregated headers.
     * @return copied header set
     */
    public HttpHeaders headersSnapshot() {
        DefaultHttpHeaders copy = new DefaultHttpHeaders();
        if (this.headers != null) {
            copy.appendHeaders(this.headers);
        }
        return copy;
    }

    /**
     * Return the aggregated body content.
     * @return aggregated body buffer, or {@link ByteBuf#EMPTY} when no body exists
     */
    public ByteBuf body() {
        return this.body == null ? ByteBuf.EMPTY : this.body;
    }

    /**
     * Reset the aggregation state so it can be reused for the next message.
     */
    public void reset() {
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

    /**
     * Append one request-side HTTP fragment into the current aggregation state.
     * @param msg request-side HTTP fragment
     */
    public void appendRequest(HttpObject msg) {
        boolean aggregateLike = msg instanceof HttpRequest && msg instanceof HttpContent;
        if (msg instanceof HttpRequest) {
            this.captureRequestStart((HttpRequest) msg);
        }

        this.appendHeaders(msg);
        if (msg instanceof HttpContent) {
            this.appendSharedBody(((HttpContent) msg).content());
        }

        this.markComplete(msg, aggregateLike);
    }

    /**
     * Append one request-side HTTP fragment while taking ownership of any body payload.
     * <p>
     * This is only valid when the caller will not use the original content wrapper again
     * except to release the remainder of its state.
     * @param msg request-side HTTP fragment
     */
    public void appendOwnedRequest(HttpObject msg) {
        boolean aggregateLike = msg instanceof HttpRequest && msg instanceof HttpContent;
        if (msg instanceof HttpRequest) {
            this.captureRequestStart((HttpRequest) msg);
        }

        this.appendHeaders(msg);
        if (msg instanceof HttpContent) {
            this.appendOwnedBody(((HttpContent) msg).transferContent());
        }

        this.markComplete(msg, aggregateLike);
    }

    /**
     * Append one response-side HTTP fragment into the current aggregation state.
     * @param msg response-side HTTP fragment
     */
    public void appendResponse(HttpObject msg) {
        boolean aggregateLike = msg instanceof HttpResponse && msg instanceof HttpContent;
        if (msg instanceof HttpResponse) {
            this.captureResponseStart((HttpResponse) msg);
        }

        this.appendHeaders(msg);
        if (msg instanceof HttpContent) {
            this.appendSharedBody(((HttpContent) msg).content());
        }

        this.markComplete(msg, aggregateLike);
    }

    /**
     * Append one response-side HTTP fragment while taking ownership of any body payload.
     * <p>
     * This is only valid when the caller will not use the original content wrapper again
     * except to release the remainder of its state.
     * @param msg response-side HTTP fragment
     */
    public void appendOwnedResponse(HttpObject msg) {
        boolean aggregateLike = msg instanceof HttpResponse && msg instanceof HttpContent;
        if (msg instanceof HttpResponse) {
            this.captureResponseStart((HttpResponse) msg);
        }

        this.appendHeaders(msg);
        if (msg instanceof HttpContent) {
            this.appendOwnedBody(((HttpContent) msg).transferContent());
        }

        this.markComplete(msg, aggregateLike);
    }

    private void captureRequestStart(HttpRequest request) {
        this.protocolVersion = request.protocolVersion();
        this.method = request.method();
        this.uri = request.uri();
        this.streamId = request.streamId();
        this.active = true;
    }

    private void captureResponseStart(HttpResponse response) {
        this.protocolVersion = response.protocolVersion();
        this.status = response.status();
        this.streamId = response.streamId();
        this.active = true;
    }

    private void appendHeaders(HttpObject msg) {
        if (msg instanceof HttpHeaders) {
            this.headers().appendHeaders((HttpHeaders) msg);
        }
    }

    private void markComplete(HttpObject msg, boolean aggregateLike) {
        if (msg instanceof LastHttpContent || aggregateLike) {
            this.complete = true;
        }
    }

    private CompositeByteBuf ensureBody() {
        if (this.body == null) {
            this.body = ByteBufUtils.compositeBuffer();
        }
        return this.body;
    }

    private void appendSharedBody(ByteBuf content) {
        if (content == null) {
            return;
        }
        if (content.readableBytes() == 0) {
            return;
        }
        this.ensureBody().addComponent(content.retain());
    }

    private void appendOwnedBody(ByteBuf content) {
        if (content == null) {
            return;
        }
        this.ensureBody().addComponent(content);
    }
}