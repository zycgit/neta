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
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;

/**
 * Aggregates request-related {@link HttpObject} streams into a {@link FullHttpRequest}.
 * <p>
 * This handler consumes segmented request objects such as {@link HttpRequest},
 * {@link HttpHeaders}, and {@link HttpContent}, and emits a fully aggregated request object.
 * <p>
 * Use it after {@link HttpRequestDecoder} or {@link HttpServerDuplexe} when downstream business
 * logic only wants complete requests instead of processing the request line, header block, and
 * message body in pieces.
 * If both request aggregation and response aggregation are needed in the same duplex node, use
 * {@link HttpServerDuplexeAggregator} instead.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-13
 */
public class HttpRequestAggregator extends AbstractHttpAggregator<HttpRequest> {
    private static final Logger logger = Logger.getLogger(HttpRequestAggregator.class);

    /**
     * Creates a request aggregator with the default maximum content length.
     */
    public HttpRequestAggregator() {
        super();
    }

    /**
     * Creates a request aggregator with an explicit maximum content length.
     * @param maxContentLength the maximum content length
     */
    public HttpRequestAggregator(int maxContentLength) {
        super(maxContentLength);
    }

    /**
     * Returns whether the current object is a request start message.
     */
    @Override
    protected boolean isStartMessage(HttpObject msg) {
        return msg instanceof HttpRequest;
    }

    /**
     * Casts the start message to a request object.
     */
    @Override
    protected HttpRequest castStartMessage(HttpObject msg) {
        return (HttpRequest) msg;
    }

    /**
     * Builds the aggregated full request.
     */
    @Override
    protected HttpObject buildAggregatedMessage(HttpRequest message, ByteBuf aggregated, DefaultHttpHeaders headers) {
        DefaultFullHttpRequest fullReq = new DefaultFullHttpRequest(message.protocolVersion(), message.method(), message.uri(), aggregated, headers);
        fullReq.streamId(message.streamId());
        if (message.isBad()) {
            fullReq.markBad(message.badReason());
        }
        return fullReq;
    }

    /**
     * Logs completion of request aggregation.
     */
    @Override
    protected void logAggregated(ProtoContext context, HttpRequest message, int contentLength) {
        if (context.getConfig().isPrintLog()) {
            long channelID = context.getChannel().getChannelId();
            logger.info(this.logPrefix() + " channel=" + channelID + " " + message.method() + " " + message.uri() + " streamId=" + message.streamId() + " contentLength=" + contentLength);
        }
    }

    /**
     * Returns the log prefix.
     */
    @Override
    protected String logPrefix() {
        return "[HTTP-REQ-AGG]";
    }

    @Override
    /**
     * Validates content length after the header section closes and handles Expect semantics.
     */ protected void onHeadersClosed(ProtoContext context, HttpRequest message, long contentLength) {
        if (contentLength > this.maxContentLength()) {
            this.sendAutoResponse(context, message, HttpStatus.REQUEST_ENTITY_TOO_LARGE);
            this.enterDiscardMode();
            return;
        }
        if (this.handleExpectation(context, message, contentLength)) {
        }
    }

    @Override
    /**
     * Sends an automatic response and enters discard mode when aggregated content exceeds the limit.
     */ protected boolean onContentTooLarge(ProtoContext context, HttpRequest message, int newLength) {
        this.sendAutoResponse(context, message, HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        this.enterDiscardMode();
        return true;
    }

    @Override
    /**
     * Handles protocol exceptions during request aggregation and auto-replies with an error when needed.
     */ protected boolean handleProtocolError(ProtoContext context, HttpProtocolException e, boolean fromPipelineError) {
        HttpRequest request = this.currentMessage();
        if (request != null) {
            this.sendAutoResponse(context, request, e.status());
            this.enterDiscardMode();
            return true;
        }

        HttpContext httpContext = HttpContext.getOrCreate(context);
        HttpContext.InboundMessageType inboundType = fromPipelineError ? httpContext.consumeInboundErrorType() : null;
        if (inboundType == HttpContext.InboundMessageType.REQUEST) {
            this.sendAutoResponse(context, HttpVersion.HTTP_1_1, 0, e.status(), false);
            this.resetAggregation();
            return true;
        }

        this.resetAggregation();
        return false;
    }

    private boolean handleExpectation(ProtoContext context, HttpRequest request, long contentLength) {
        DefaultHttpHeaders headers = this.currentHeaders();
        String expect = headers != null ? headers.getString(HttpHeaderNames.EXPECT) : null;
        if (expect == null) {
            return false;
        }

        String expectValue = expect.trim();
        if (expectValue.isEmpty()) {
            return false;
        }
        if (!StringUtils.equalsIgnoreCase(expectValue, HttpHeaderValues.CONTINUE)) {
            this.sendAutoResponse(context, request, HttpStatus.EXPECTATION_FAILED);
            this.enterDiscardMode();
            return true;
        }

        if (contentLength < 0 || contentLength <= this.maxContentLength()) {
            this.sendAutoResponse(context, request.protocolVersion(), request.streamId(), HttpStatus.CONTINUE, true);
        }
        return false;
    }

    private void sendAutoResponse(ProtoContext context, HttpRequest request, HttpStatus status) {
        this.sendAutoResponse(context, request.protocolVersion(), request.streamId(), status, this.isKeepAlive(request));
    }

    private void sendAutoResponse(ProtoContext context, HttpVersion protocolVersion, long streamId, HttpStatus status, boolean keepAlive) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(protocolVersion, status);
        response.streamId(streamId);
        response.setHeader(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO);

        if (!keepAlive) {
            response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }

        context.sendData(response);
        if (context.getConfig().isPrintLog()) {
            long channelID = context.getChannel().getChannelId();
            logger.info(this.logPrefix() + " channel=" + channelID + " auto-response status=" + status.code() + " keepAlive=" + keepAlive + " streamId=" + streamId);
        }
    }

    private boolean isKeepAlive(HttpRequest request) {
        DefaultHttpHeaders headers = this.currentHeaders();
        String connection = headers != null ? headers.getString(HttpHeaderNames.CONNECTION) : null;
        if (connection != null) {
            if (StringUtils.containsIgnoreCase(connection, HttpHeaderValues.CLOSE)) {
                return false;
            }
            if (StringUtils.containsIgnoreCase(connection, HttpHeaderValues.KEEP_ALIVE)) {
                return true;
            }
        }
        return request.protocolVersion().isKeepAliveDefault();
    }
}