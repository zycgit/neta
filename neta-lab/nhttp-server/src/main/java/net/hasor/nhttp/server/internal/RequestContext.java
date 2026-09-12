/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.internal;

import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpRequest;
import net.hasor.nhttp.server.connector.ResponseSink;

/**
 * Holds the per-request state for the entire lifecycle of one HTTP request: from receipt on
 * the IO thread to response completion on the worker thread.
 *
 * <h3>Threading model</h3>
 * <p>Instances are created on the <b>IO thread</b> inside {@code HttpRequestHandler} and then
 * handed to {@link RequestManager}, which submits them to the worker pool. All fields are
 * effectively final after construction — no further mutation by the IO thread occurs once
 * {@link RequestManager#tryDispatch} has been called.
 *
 * <p>Thread-safety for body data transfer is provided by {@link InternalBodyChannel}: the IO
 * thread produces ({@link InternalBodyChannel#offer}), the worker thread consumes
 * ({@link InternalBodyChannel#read}). The response is written exclusively by the worker thread
 * through {@link ResponseSink}.
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public class RequestContext {

    /** HTTP request line (method, URI, protocol version). */
    public final HttpRequest httpRequest;

    /** All HTTP request headers, accumulated from header blocks before the body starts. */
    public final HttpHeaders httpHeaders;

    /**
     * Streaming body channel. The IO thread pushes {@code HttpContent} chunks as they arrive;
     * the worker thread reads them on demand. Must be {@link InternalBodyChannel#close() closed}
     * after processing (done automatically by {@link RequestManager}).
     */
    public final InternalBodyChannel bodyChannel;

    /** Protocol-aware response writer (HTTP/1.1 or HTTP/2). */
    public final ResponseSink responseSink;

    /** Underlying network channel for connection-level operations (e.g. forced close). */
    public final NetChannel channel;

    /**
     * neta pipeline context for this stream / connection.
     * Use for low-level sends when {@link ResponseSink} is insufficient.
     */
    public final ProtoContext protoContext;

    /** {@code true} if the connection is secured by TLS. */
    public final boolean secure;

    /**
     * Wall-clock time (milliseconds since epoch) when the request headers were received.
     * Used for request-timeout enforcement and metrics reporting.
     */
    public final long receivedTimeMillis;

    /**
     * Set to {@code true} by the container when the request has transitioned to async
     * processing via {@link net.hasor.nhttp.server.ServletRequest#startAsync()}.
     *
     * <p>When {@code true}, {@link RequestManager} skips the concurrency-slot release and
     * body-channel cleanup in its {@code finally} block; the {@link InternalAsyncContext}
     * owns those resources and releases them when {@code AsyncContext.complete()} is called.</p>
     */
    public volatile boolean asyncStarted = false;

    /**
     * Constructs a new {@code RequestContext}.
     *
     * @param httpRequest  HTTP request line (method + URI + version)
     * @param httpHeaders  all HTTP request headers accumulated by the IO thread
     * @param bodyChannel  streaming body channel (pre-allocated by the IO thread)
     * @param responseSink protocol-aware response writer
     * @param channel      underlying network channel
     * @param protoContext neta pipeline context
     * @param secure       {@code true} if TLS is in use
     */
    public RequestContext(HttpRequest httpRequest, HttpHeaders httpHeaders, InternalBodyChannel bodyChannel, ResponseSink responseSink, NetChannel channel, ProtoContext protoContext, boolean secure) {
        this.httpRequest = httpRequest;
        this.httpHeaders = httpHeaders;
        this.bodyChannel = bodyChannel;
        this.responseSink = responseSink;
        this.channel = channel;
        this.protoContext = protoContext;
        this.secure = secure;
        this.receivedTimeMillis = System.currentTimeMillis();
    }
}
