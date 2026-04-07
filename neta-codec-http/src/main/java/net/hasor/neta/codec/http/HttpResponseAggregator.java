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
 * Aggregates response-related {@link HttpObject} streams into a {@link FullHttpResponse}.
 * <p>
 * This handler consumes segmented response objects such as {@link HttpResponse},
 * {@link HttpHeaders}, and {@link HttpContent}, and emits a fully aggregated response object.
 * <p>
 * Use it after {@link HttpResponseDecoder} or {@link HttpClientDuplexe} when downstream business
 * logic only wants complete responses instead of processing the status line, header block, and
 * message body in pieces.
 * If both response aggregation and request aggregation are needed in the same duplex node, use
 * {@link HttpClientDuplexeAggregator} instead.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-13
 */
public class HttpResponseAggregator extends AbstractHttpAggregator<HttpResponse> {
    private static final Logger logger = Logger.getLogger(HttpResponseAggregator.class);

    /**
     * Creates a response aggregator with the default maximum content length.
     */
    public HttpResponseAggregator() {
        super();
    }

    /**
     * Creates a response aggregator with an explicit maximum content length.
     * @param maxContentLength the maximum content length
     */
    public HttpResponseAggregator(int maxContentLength) {
        super(maxContentLength);
    }

    /**
     * Returns whether the current object is a response start message.
     * @param msg the object to test
     * @return true if this is a response start message
     */
    @Override
    protected boolean isStartMessage(HttpObject msg) {
        return msg instanceof HttpResponse;
    }

    /**
     * Casts the start message to a response object.
     * @param msg the start message
     * @return the response object
     */
    @Override
    protected HttpResponse castStartMessage(HttpObject msg) {
        return (HttpResponse) msg;
    }

    /**
     * Builds the aggregated full response.
     * @param message the start response object
     * @param aggregated the aggregated content buffer
     * @param headers the aggregated header collection
     * @return the full response object
     */
    @Override
    protected HttpObject buildAggregatedMessage(HttpResponse message, ByteBuf aggregated, DefaultHttpHeaders headers) {
        DefaultFullHttpResponse fullResp = new DefaultFullHttpResponse(message.protocolVersion(), message.status(), aggregated, headers);
        fullResp.streamId(message.streamId());
        return fullResp;
    }

    /**
     * Logs completion of response aggregation.
     * @param context the protocol context
     * @param message the start response object
     * @param contentLength the aggregated content length
     */
    @Override
    protected void logAggregated(ProtoContext context, HttpResponse message, int contentLength) {
        if (context.getConfig().isPrintLog()) {
            long channelID = context.getChannel().getChannelId();
            logger.info(this.logPrefix() + " channel=" + channelID + " response status=" + message.status().code() + " streamId=" + message.streamId() + " contentLength=" + contentLength);
        }
    }

    /**
     * Returns the log prefix.
     * @return the log prefix
     */
    @Override
    protected String logPrefix() {
        return "[HTTP-RESP-AGG]";
    }
}