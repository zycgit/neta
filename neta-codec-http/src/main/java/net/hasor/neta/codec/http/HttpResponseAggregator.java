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
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;

/**
 * Aggregates staged response-side {@link HttpObject} sequences into {@link FullHttpResponse}.
 * <p>
 * Use this handler after {@link HttpResponseDecoder} when downstream code prefers complete
 * response objects instead of staged headers and body chunks.
 * <p>
 * pipeline view:
 * <pre>
 *   socket bytes
 *      -> HttpResponseDecoder
 *      -> HttpResponse + HttpHeaders + HttpContent ...
 *      -> HttpResponseAggregator
 *      -> FullHttpResponse
 * </pre>
 * <p>
 * For client pipelines this is usually the receive-side aggregation choice. If you want the
 * same idea packaged as a duplex node, use {@link HttpClientDuplexeAggregator}.
 */
public class HttpResponseAggregator extends AbstractHttpAggregator<HttpResponse> {
    private static final Logger logger = Logger.getLogger(HttpResponseAggregator.class);

    public HttpResponseAggregator() {
        super();
    }

    public HttpResponseAggregator(int maxContentLength) {
        super(maxContentLength);
    }

    @Override
    protected boolean isStartMessage(HttpObject msg) {
        return msg instanceof HttpResponse;
    }

    @Override
    protected boolean isFullMessage(HttpObject msg) {
        return msg instanceof FullHttpResponse;
    }

    @Override
    protected HttpResponse castStartMessage(HttpObject msg) {
        return (HttpResponse) msg;
    }

    @Override
    protected HttpObject buildAggregatedMessage(HttpResponse message, ByteBuf aggregated, DefaultHttpHeaders headers) {
        DefaultFullHttpResponse fullResp = new DefaultFullHttpResponse(message.protocolVersion(), message.status(), aggregated, headers);
        fullResp.streamId(message.streamId());
        return fullResp;
    }

    @Override
    protected void logAggregated(ProtoContext context, HttpResponse message, int contentLength) {
        if (context.getConfig().isPrintLog()) {
            long channelID = context.getChannel().getChannelId();
            logger.info(this.logPrefix() + " channel=" + channelID + " response status=" + message.status().code() + " streamId=" + message.streamId() + " contentLength=" + contentLength);
        }
    }

    @Override
    protected String logPrefix() {
        return "[HTTP-RESP-AGG]";
    }
}