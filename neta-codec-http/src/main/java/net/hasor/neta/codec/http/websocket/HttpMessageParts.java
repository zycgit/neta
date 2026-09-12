/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
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
    private HttpHeaders        borrowedHeaders;
    private CompositeByteBuf   body;
    private boolean            active;
    private boolean            complete;

    /**
     * Determine whether aggregation has started for the current message.
     * @return {@code true} once at least one start-line fragment has been seen
     */
    boolean isActive() {
        return this.active;
    }

    /**
     * Determine whether the current aggregated message has reached its end marker.
     * @return {@code true} when the request or response is complete
     */
    boolean isComplete() {
        return this.complete;
    }

    /**
     * Return the aggregated protocol version.
     * @return request or response protocol version
     */
    HttpVersion protocolVersion() {
        return this.protocolVersion;
    }

    /**
     * Return the aggregated request method.
     * @return request method, or {@code null} for responses
     */
    HttpMethod method() {
        return this.method;
    }

    /**
     * Return the aggregated response status.
     * @return response status, or {@code null} for requests
     */
    HttpStatus status() {
        return this.status;
    }

    /**
     * Return the aggregated request URI.
     * @return request URI, or {@code null} for responses
     */
    String uri() {
        return this.uri;
    }

    /**
     * Return the stream identifier associated with the aggregated message.
     * @return stream identifier
     */
    long streamId() {
        return this.streamId;
    }

    /**
     * Return one header value from the aggregated header set.
     * @param name header name to resolve
     * @return header value, or {@code null} when absent
     */
    String header(String name) {
        if (this.borrowedHeaders != null) {
            return this.borrowedHeaders.getString(name);
        }
        return this.headers == null ? null : this.headers.getString(name);
    }

    /**
     * Return the currently aggregated headers without creating a defensive copy.
     */
    DefaultHttpHeaders headersView() {
        if (this.borrowedHeaders != null) {
            DefaultHttpHeaders copy = new DefaultHttpHeaders();
            copy.appendHeaders(this.borrowedHeaders);
            return copy;
        }
        return this.headers;
    }

    /**
     * Return the aggregated body content.
     * @return aggregated body buffer, or {@link ByteBuf#EMPTY} when no body exists
     */
    ByteBuf body() {
        return this.body == null ? ByteBuf.EMPTY : this.body;
    }

    /**
     * Reset the aggregation state so it can be reused for the next message.
     */
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
        this.borrowedHeaders = null;
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
    void appendRequest(HttpObject msg) {
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
     * Append a complete request while borrowing its headers for the duration of
     * the current pipeline call. Fragmented requests fall back to owned aggregation.
     */
    void appendBorrowedRequest(HttpObject msg) {
        boolean aggregateLike = msg instanceof HttpRequest && msg instanceof HttpContent;
        if (!aggregateLike || !(msg instanceof HttpHeaders)) {
            this.appendRequest(msg);
            return;
        }

        this.captureRequestStart((HttpRequest) msg);
        this.borrowedHeaders = (HttpHeaders) msg;
        this.appendSharedBody(((HttpContent) msg).content());
        this.markComplete(msg, true);
    }

    /**
     * Append one request-side HTTP fragment while taking ownership of any body payload.
     * <p>
     * This is only valid when the caller will not use the original content wrapper again
     * except to release the remainder of its state.
     * @param msg request-side HTTP fragment
     */
    void appendOwnedRequest(HttpObject msg) {
        boolean aggregateLike = msg instanceof HttpRequest && msg instanceof HttpContent;
        boolean borrowHeaders = aggregateLike && msg instanceof HttpHeaders && ((HttpContent) msg).content().readableBytes() == 0;
        if (msg instanceof HttpRequest) {
            this.captureRequestStart((HttpRequest) msg);
        }

        if (borrowHeaders) {
            this.borrowedHeaders = (HttpHeaders) msg;
        } else {
            this.appendHeaders(msg);
        }
        if (msg instanceof HttpContent) {
            this.appendOwnedBody(((HttpContent) msg).transferContent());
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
    void appendOwnedResponse(HttpObject msg) {
        boolean aggregateLike = msg instanceof HttpResponse && msg instanceof HttpContent;
        if (msg instanceof HttpResponse) {
            this.captureResponseStart((HttpResponse) msg);
        }

        if (aggregateLike && msg instanceof HttpHeaders) {
            this.borrowedHeaders = (HttpHeaders) msg;
        } else {
            this.appendHeaders(msg);
        }
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
        if (content.readableBytes() == 0) {
            content.release();
            return;
        }
        this.ensureBody().addComponent(content);
    }
}
