/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
/**
 * Bidirectional aggregator for server-side {@link HttpObject} streams.
 * <p>
 * This class is typically placed after {@link HttpServerDuplex} to collapse segmented
 * {@link HttpObject} messages on both server-side directions into complete messages. The inbound
 * side aggregates request object streams into {@link FullHttpRequest}, while the outbound side
 * aggregates response object streams into {@link FullHttpResponse}.
 * <p>
 * In other words, {@link HttpServerDuplex} turns connection bytes into an {@link HttpObject}
 * stream, and this class continues aggregating that stream until a complete request or response is
 * available.
 * <p>
 * Pipeline view:
 * <pre>
 *   inbound:  HttpObject parts -> HttpServerDuplexeAggregator -> FullHttpRequest
 *   outbound: HttpObject parts -> HttpServerDuplexeAggregator -> FullHttpResponse
 * </pre>
 * <p>
 * It is intended for server-side logic that prefers to work with complete requests and responses
 * instead of handling request lines, header blocks, and content fragments manually.
 * <p>
 * Typical usage:
 * <pre>
 *   ctx.addLast("http", new HttpServerDuplexe());
 *   ctx.addLast("http-agg", new HttpServerDuplexeAggregator(1048576));
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-13
 */
public class HttpServerDuplexAggregator implements ProtoDuplex<HttpObject, HttpObject, HttpObject, HttpObject> {
    private final HttpRequestAggregator  requestAggregator;
    private final HttpResponseAggregator responseAggregator;

    /**
     * Creates a server duplex aggregator with the default maximum content length.
     */
    public HttpServerDuplexAggregator() {
        this(AbstractHttpAggregator.DEFAULT_MAX_CONTENT_LENGTH);
    }

    /**
     * Creates a server duplex aggregator with an explicit maximum content length.
     * @param maxContentLength the maximum content length
     */
    public HttpServerDuplexAggregator(int maxContentLength) {
        this.requestAggregator = new HttpRequestAggregator(maxContentLength);
        this.responseAggregator = new HttpResponseAggregator(maxContentLength);
    }

    /**
     * Initializes the aggregators on both inbound and outbound sides.
     */
    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.requestAggregator.onInit(name + "-request", rcvSize, context);
        this.responseAggregator.onInit(name + "-response", sndSize, context);
    }

    /**
     * Dispatches events according to direction.
     */
    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        if (isRcv) {
            return this.requestAggregator.onEvent(context, event);
        } else {
            return this.responseAggregator.onEvent(context, event);
        }
    }

    /**
     * Processes messages according to direction.
     */
    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown, ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        if (isRcv) {
            return this.requestAggregator.onMessage(context, rcvUp, rcvDown);
        } else {
            return this.responseAggregator.onMessage(context, sndUp, sndDown);
        }
    }

    /**
     * Processes exceptions according to direction.
     */
    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.requestAggregator.onError(context, e, eh);
        } else {
            return this.responseAggregator.onError(context, e, eh);
        }
    }

    /**
     * Closes and releases aggregator state on both sides.
     */
    @Override
    public void onClose(ProtoContext context) {
        this.requestAggregator.onClose(context);
        this.responseAggregator.onClose(context);
    }
}
