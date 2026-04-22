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
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
/**
 * Bidirectional aggregator for client-side {@link HttpObject} streams.
 * <p>
 * This class is typically placed after {@link HttpClientDuplex} to collapse segmented
 * {@link HttpObject} messages on both client-side directions into complete messages. The inbound
 * side aggregates response object streams into {@link FullHttpResponse}, while the outbound side
 * aggregates request object streams into {@link FullHttpRequest}.
 * <p>
 * In other words, {@link HttpClientDuplex} turns bytes into an {@link HttpObject} stream, and
 * this class continues aggregating that stream until a complete request or response is available.
 * <p>
 * Pipeline view:
 * <pre>
 *   inbound:  HttpObject parts -> HttpClientDuplexeAggregator -> FullHttpResponse
 *   outbound: HttpObject parts -> HttpClientDuplexeAggregator -> FullHttpRequest
 * </pre>
 * <p>
 * It is intended for client-side logic that prefers to work with complete messages instead of
 * handling request lines, header blocks, and content fragments manually.
 * <p>
 * Typical usage:
 * <pre>
 *   ctx.addLast("http", new HttpClientDuplexe());
 *   ctx.addLast("http-agg", new HttpClientDuplexeAggregator(1048576));
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-13
 */
public class HttpClientDuplexAggregator implements ProtoDuplex<HttpObject, HttpObject, HttpObject, HttpObject> {
    private final HttpResponseAggregator responseAggregator;
    private final HttpRequestAggregator  requestAggregator;

    /**
     * Creates a client duplex aggregator with the default maximum content length.
     */
    public HttpClientDuplexAggregator() {
        this(AbstractHttpAggregator.DEFAULT_MAX_CONTENT_LENGTH);
    }

    /**
     * Creates a client duplex aggregator with an explicit maximum content length.
     * @param maxContentLength the maximum content length
     */
    public HttpClientDuplexAggregator(int maxContentLength) {
        this.responseAggregator = new HttpResponseAggregator(maxContentLength);
        this.requestAggregator = new HttpRequestAggregator(maxContentLength);
    }

    /**
     * Initializes the aggregators on both inbound and outbound sides.
     */
    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.responseAggregator.onInit(name + "-response", rcvSize, context);
        this.requestAggregator.onInit(name + "-request", sndSize, context);
    }

    /**
     * Dispatches events according to direction.
     */
    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        if (isRcv) {
            return this.responseAggregator.onEvent(context, event);
        } else {
            return this.requestAggregator.onEvent(context, event);
        }
    }

    /**
     * Processes messages according to direction.
     */
    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown, ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        if (isRcv) {
            return this.responseAggregator.onMessage(context, rcvUp, rcvDown);
        } else {
            return this.requestAggregator.onMessage(context, sndUp, sndDown);
        }
    }

    /**
     * Processes exceptions according to direction.
     */
    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.responseAggregator.onError(context, e, eh);
        } else {
            return this.requestAggregator.onError(context, e, eh);
        }
    }

    /**
     * Closes and releases aggregator state on both sides.
     */
    @Override
    public void onClose(ProtoContext context) {
        this.responseAggregator.onClose(context);
        this.requestAggregator.onClose(context);
    }
}