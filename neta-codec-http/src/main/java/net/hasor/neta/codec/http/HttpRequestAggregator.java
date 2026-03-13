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
 * Aggregates staged request-side {@link HttpObject} sequences into {@link FullHttpRequest}.
 * <p>
 * Use this handler after {@link HttpRequestDecoder} when downstream code prefers complete
 * request objects instead of staged headers and body chunks.
 * <p>
 * pipeline view:
 * <pre>
 *   socket bytes
 *      -> HttpRequestDecoder
 *      -> HttpRequest + HttpHeaders + HttpContent ...
 *      -> HttpRequestAggregator
 *      -> FullHttpRequest
 * </pre>
 * <p>
 * For server pipelines this is usually the receive-side aggregation choice. If you want the
 * same idea packaged as a duplex node, use {@link HttpServerDuplexeAggregator}.
 */
public class HttpRequestAggregator extends AbstractHttpAggregator<HttpRequest> {
    private static final Logger logger = Logger.getLogger(HttpRequestAggregator.class);

    public HttpRequestAggregator() {
        super();
    }

    public HttpRequestAggregator(int maxContentLength) {
        super(maxContentLength);
    }

    @Override
    protected boolean isStartMessage(HttpObject msg) {
        return msg instanceof HttpRequest;
    }

    @Override
    protected boolean isFullMessage(HttpObject msg) {
        return msg instanceof FullHttpRequest;
    }

    @Override
    protected HttpRequest castStartMessage(HttpObject msg) {
        return (HttpRequest) msg;
    }

    @Override
    protected HttpObject buildAggregatedMessage(HttpRequest message, ByteBuf aggregated, DefaultHttpHeaders headers) {
        DefaultFullHttpRequest fullReq = new DefaultFullHttpRequest(message.protocolVersion(), message.method(), message.uri(), aggregated, headers);
        fullReq.streamId(message.streamId());
        return fullReq;
    }

    @Override
    protected void logAggregated(ProtoContext context, HttpRequest message, int contentLength) {
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info(this.logPrefix() + " channel=" + channelID + " " + message.method() + " " + message.uri() + " streamId=" + message.streamId() + " contentLength=" + contentLength);
        }
    }

    @Override
    protected String logPrefix() {
        return "[HTTP-REQ-AGG]";
    }

    @Override
    protected void onHeadersClosed(ProtoContext context, HttpRequest message, long contentLength) {
        if (contentLength > this.maxContentLength()) {
            this.sendAutoResponse(context, message, HttpStatus.REQUEST_ENTITY_TOO_LARGE);
            this.enterDiscardMode();
            return;
        }
        if (this.handleExpectation(context, message, contentLength)) {
            return;
        }
    }

    @Override
    protected boolean onContentTooLarge(ProtoContext context, HttpRequest message, int newLength) {
        this.sendAutoResponse(context, message, HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        this.enterDiscardMode();
        return true;
    }

    @Override
    protected boolean handleProtocolError(ProtoContext context, HttpProtocolException e, boolean fromPipelineError) {
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

    private void sendAutoResponse(ProtoContext context, HttpVersion protocolVersion, int streamId, HttpStatus status, boolean keepAlive) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(protocolVersion, status);
        response.streamId(streamId);
        response.setHeader(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO);

        if (!keepAlive) {
            response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }

        context.sendData(response);
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
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