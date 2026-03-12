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
 * Aggregates a sequence of {@link HttpObject}s (a start line followed by header blocks,
 * {@link HttpContent}s and a {@link LastHttpContent}) into a single
 * {@link FullHttpRequest} or {@link FullHttpResponse}.
 * <p>
 * This handler sits after the decoder in the pipeline and collects the streamed
 * HTTP message parts into a complete message object.
 * <p>
 * If the content exceeds {@code maxContentLength}, an
 * {@link HttpContentTooLargeException} is thrown.
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLastDecoder("http-request", new HttpRequestDecoder());
 *   ctx.addLastDecoder("http-aggregator", new HttpObjectAggregator(1048576)); // 1MB max
 * </pre>
 */
public class HttpObjectAggregator implements ProtoHandler<HttpObject, HttpObject> {
    private final HttpRequestAggregator  requestAggregator;
    private final HttpResponseAggregator responseAggregator;
    private       AggregateTarget        activeTarget;

    /** Creates an aggregator with the default maximum content length (1 MB). */
    public HttpObjectAggregator() {
        this(AbstractHttpAggregator.DEFAULT_MAX_CONTENT_LENGTH);
    }

    /**
     * Creates an aggregator with the specified maximum content length.
     * @param maxContentLength the maximum allowed content length in bytes
     */
    public HttpObjectAggregator(int maxContentLength) {
        this.requestAggregator = new HttpRequestAggregator(maxContentLength);
        this.responseAggregator = new HttpResponseAggregator(maxContentLength);
        this.activeTarget = AggregateTarget.NONE;
    }

    @Override
    public void onInit(String name, int poolSize, ProtoContext context) {
        this.requestAggregator.onInit(name + "-request", poolSize, context);
        this.responseAggregator.onInit(name + "-response", poolSize, context);
    }

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
        this.activeTarget = AggregateTarget.NONE;
        return this.requestAggregator.onUserEvent(context, event) && this.responseAggregator.onUserEvent(context, event);
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        HttpContext httpContext = HttpContext.getOrCreate(context);
        httpContext.consumeInboundErrorType();
        if (httpContext.isTransparentMode()) {
            this.activeTarget = AggregateTarget.NONE;
            while (src.hasMore()) {
                HttpObject msg = src.takeMessage();
                if (msg != null) {
                    dst.offerMessage(msg);
                }
            }
            return ProtoStatus.Next;
        }

        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }
            this.routeOne(context, msg, dst);
        }

        return ProtoStatus.Next;
    }

    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (this.activeTarget == AggregateTarget.RESPONSE) {
            ProtoStatus status = this.responseAggregator.onError(context, e, eh);
            this.activeTarget = this.responseAggregator.isIdle() ? AggregateTarget.NONE : AggregateTarget.RESPONSE;
            return status;
        }
        ProtoStatus status = this.requestAggregator.onError(context, e, eh);
        this.activeTarget = this.requestAggregator.isIdle() ? AggregateTarget.NONE : AggregateTarget.REQUEST;
        return status;
    }

    @Override
    public void onClose(ProtoContext context) {
        this.requestAggregator.onClose(context);
        this.responseAggregator.onClose(context);
        this.activeTarget = AggregateTarget.NONE;
    }

    private void routeOne(ProtoContext context, HttpObject msg, ProtoSndQueue<HttpObject> dst) throws Throwable {
        AggregateTarget target = this.selectTarget(msg);
        ProtoQueue<HttpObject> single = new ProtoQueue<>(-1);
        single.offerMessage(msg);
        single.sndSubmit();

        if (target == AggregateTarget.REQUEST) {
            this.requestAggregator.onMessage(context, single, dst);
            this.activeTarget = this.requestAggregator.isIdle() ? AggregateTarget.NONE : AggregateTarget.REQUEST;
        } else {
            this.responseAggregator.onMessage(context, single, dst);
            this.activeTarget = this.responseAggregator.isIdle() ? AggregateTarget.NONE : AggregateTarget.RESPONSE;
        }
    }

    private AggregateTarget selectTarget(HttpObject msg) {
        if (this.activeTarget != AggregateTarget.NONE) {
            return this.activeTarget;
        }
        if (msg instanceof HttpRequest || msg instanceof FullHttpRequest) {
            return AggregateTarget.REQUEST;
        }
        if (msg instanceof HttpResponse || msg instanceof FullHttpResponse) {
            return AggregateTarget.RESPONSE;
        }
        throw new HttpProtocolViolationException("received " + msg.getClass().getSimpleName() + " without preceding start line");
    }

    private enum AggregateTarget {
        NONE,
        REQUEST,
        RESPONSE
    }
}
