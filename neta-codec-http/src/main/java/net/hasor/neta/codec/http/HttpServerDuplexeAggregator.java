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

/**
 * Server-side duplex wrapper for request and response aggregation.
 * <p>
 * This class bundles {@link HttpRequestAggregator} on the receive side and
 * {@link HttpResponseAggregator} on the send side, so a server pipeline can express full-message
 * aggregation as one duplex node instead of wiring two independent handlers.
 * <p>
 * pipeline view:
 * <pre>
 *   inbound:  HttpObject parts -> HttpServerDuplexeAggregator -> FullHttpRequest
 *   outbound: HttpObject parts -> HttpServerDuplexeAggregator -> FullHttpResponse
 * </pre>
 * <p>
 * Typical usage:
 * <pre>
 *   ctx.addLast("http", new HttpServerDuplexe());
 *   ctx.addLast("http-agg", new HttpServerDuplexeAggregator(1048576));
 * </pre>
 */
public class HttpServerDuplexeAggregator implements ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject> {
    private final HttpRequestAggregator  requestAggregator;
    private final HttpResponseAggregator responseAggregator;

    public HttpServerDuplexeAggregator() {
        this(AbstractHttpAggregator.DEFAULT_MAX_CONTENT_LENGTH);
    }

    public HttpServerDuplexeAggregator(int maxContentLength) {
        this.requestAggregator = new HttpRequestAggregator(maxContentLength);
        this.responseAggregator = new HttpResponseAggregator(maxContentLength);
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.requestAggregator.onInit(name + "-request", rcvSize, context);
        this.responseAggregator.onInit(name + "-response", sndSize, context);
    }

    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        if (isRcv) {
            return this.requestAggregator.onEvent(context, event);
        }
        return this.responseAggregator.onEvent(context, event);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown, ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        if (isRcv) {
            return this.requestAggregator.onMessage(context, rcvUp, rcvDown);
        }
        return this.responseAggregator.onMessage(context, sndUp, sndDown);
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.requestAggregator.onError(context, e, eh);
        }
        return this.responseAggregator.onError(context, e, eh);
    }

    @Override
    public void onClose(ProtoContext context) {
        this.requestAggregator.onClose(context);
        this.responseAggregator.onClose(context);
    }
}