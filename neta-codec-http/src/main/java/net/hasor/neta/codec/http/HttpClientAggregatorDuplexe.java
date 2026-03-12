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
 * Client-side aggregation duplexer.
 * <p>
 * RCV direction: response-side {@link HttpObject} → {@link FullHttpResponse}
 * <p>
 * SND direction: request-side {@link HttpObject} → {@link FullHttpRequest}
 */
public class HttpClientAggregatorDuplexe implements ProtoDuplexer<HttpObject, HttpObject, HttpObject, HttpObject> {
    private final HttpResponseAggregator responseAggregator;
    private final HttpRequestAggregator  requestAggregator;

    public HttpClientAggregatorDuplexe() {
        this(AbstractHttpAggregator.DEFAULT_MAX_CONTENT_LENGTH);
    }

    public HttpClientAggregatorDuplexe(int maxContentLength) {
        this.responseAggregator = new HttpResponseAggregator(maxContentLength);
        this.requestAggregator = new HttpRequestAggregator(maxContentLength);
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        this.responseAggregator.onInit(name + "-response", rcvSize, context);
        this.requestAggregator.onInit(name + "-request", sndSize, context);
    }

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) throws Throwable {
        if (isRcv) {
            return this.responseAggregator.onUserEvent(context, event);
        }
        return this.requestAggregator.onUserEvent(context, event);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<HttpObject> rcvUp, ProtoSndQueue<HttpObject> rcvDown, ProtoRcvQueue<HttpObject> sndUp, ProtoSndQueue<HttpObject> sndDown) throws Throwable {
        if (isRcv) {
            return this.responseAggregator.onMessage(context, rcvUp, rcvDown);
        }
        return this.requestAggregator.onMessage(context, sndUp, sndDown);
    }

    @Override
    public ProtoStatus onError(ProtoContext context, boolean isRcv, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (isRcv) {
            return this.responseAggregator.onError(context, e, eh);
        }
        return this.requestAggregator.onError(context, e, eh);
    }

    @Override
    public void onClose(ProtoContext context) {
        this.responseAggregator.onClose(context);
        this.requestAggregator.onClose(context);
    }
}