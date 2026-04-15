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
import java.util.List;
import java.util.concurrent.Future;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/**
 * Aggregates an ordered HTTP request object sequence into a {@link FullHttpRequest}.
 * <p>
 * The first staged object is the request line, the last staged object is the terminal {@link LastHttpContent}, and all
 * staged objects in between are appended in-order to the aggregated request.
 * <p>
 * Request aggregation also owns the HTTP/1.1 expectation handling and the automatic 413 or 417 responses that may be
 * emitted during request validation.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-11
 */
public class HttpRequestAggregator extends AbstractHttpAggregator<HttpRequest> {
    /**
     * Creates a request aggregator with the default maximum content length.
     */
    public HttpRequestAggregator() {
        super();
    }

    /**
     * Creates a request aggregator with an explicit maximum content length.
     * @param maxContentLength maximum accepted aggregated payload length
     */
    public HttpRequestAggregator(int maxContentLength) {
        super(maxContentLength);
    }

    /**
     * Request aggregation always starts from the request line object.
     */
    @Override
    protected boolean isStartMessage(HttpObject msg) {
        return msg instanceof HttpRequest;
    }

    /**
     * Builds a full request directly on top of {@link DefaultFullHttpRequest} by appending the staged parts in order.
     */
    @Override
    protected void emitAggregated(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) {
        List<HttpObject> parts = this.takeStagedParts(src);
        if (parts == null || parts.isEmpty()) {
            this.resetAggregation(src);
            return;
        }

        HttpRequest request = (HttpRequest) parts.get(0);
        DefaultFullHttpRequest fullReq = new DefaultFullHttpRequest(request.protocolVersion(), request.method(), request.uri(), ByteBuf.EMPTY);
        boolean handled = false;
        boolean success = false;
        try {
            int contentLength = 0;
            for (int index = 0; index < parts.size(); index++) {
                HttpObject part = parts.get(index);
                if (part instanceof HttpHeaders) {
                    fullReq.appendHeaders((HttpHeaders) part);
                    if (part instanceof LastHttpHeaders && !this.isHeadersClosedHandled()) {
                        long declaredLength = fullReq.getLong(HttpHeaderNames.CONTENT_LENGTH, -1);
                        this.onHeadersClosed(context, request, fullReq, declaredLength);
                        if (fullReq.isBad() || this.isDiscardMode()) {
                            handled = true;
                            return;
                        }
                    }
                }
                ByteBuf content = this.contentOf(part);
                if (content != null) {
                    int readable = content.readableBytes();
                    int newLength = contentLength + readable;
                    if (newLength > this.maxContentLength()) {
                        if (this.onContentTooLarge(context, request, fullReq, newLength) && this.isDiscardMode()) {
                            handled = true;
                            return;
                        }
                        throw new HttpContentTooLargeException("content length exceeds maximum: " + newLength + " > " + this.maxContentLength(), this.maxContentLength(), newLength);
                    }
                    contentLength = newLength;
                    if (readable > 0) {
                        this.appendContent(fullReq, part, content);
                    }
                }
            }

            this.completeFullRequest(request, fullReq, contentLength);
            dst.offerMessage(fullReq);
            this.logAggregatedRequest(context, request, contentLength);
            success = true;
        } finally {
            this.releaseAggregatedRequest(parts, fullReq, success);
            this.finishAggregation(src, success, handled);
        }
    }

    @Override
    protected void onHeadersStaged(ProtoContext context, ProtoRcvQueue<HttpObject> src) {
        List<HttpObject> parts = this.peekStagedParts(src);
        if (parts == null || parts.isEmpty()) {
            return;
        }

        HttpRequest request = (HttpRequest) parts.get(0);
        DefaultHttpHeaders headers = new DefaultLastHttpHeaders();
        for (HttpObject part : parts) {
            if (part instanceof HttpHeaders) {
                headers.appendHeaders((HttpHeaders) part);
            }
        }

        long declaredLength = headers.getLong(HttpHeaderNames.CONTENT_LENGTH, -1);
        this.onHeadersClosed(context, request, headers, declaredLength);
    }

    /**
     * Finalizes the aggregated request headers and propagates request-line metadata.
     */
    private void completeFullRequest(HttpRequest request, DefaultFullHttpRequest fullReq, int contentLength) {
        fullReq.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(contentLength));
        fullReq.removeHeader(HttpHeaderNames.TRANSFER_ENCODING);
        fullReq.streamId(request.streamId());
        if (request.isBad()) {
            fullReq.markBad(request.badReason());
        }
    }

    private ByteBuf contentOf(HttpObject part) {
        if (part instanceof HttpContent) {
            return ((HttpContent) part).content();
        }
        if (part instanceof HttpByteBuf) {
            return ((HttpByteBuf) part).content();
        }
        return null;
    }

    private void appendContent(DefaultFullHttpRequest fullReq, HttpObject part, ByteBuf content) {
        if (part instanceof HttpContent) {
            fullReq.appendContent((HttpContent) part);
            return;
        }
        fullReq.appendContent(new DefaultHttpContent(content));
    }

    /**
     * Writes the aggregation summary log line when HTTP logging is enabled.
     */
    private void logAggregatedRequest(ProtoContext context, HttpRequest request, int contentLength) {
        if (context.getConfig().isPrintLog()) {
            long channelID = context.getChannel().getChannelId();
            logger.info(this.logPrefix() + " channel=" + channelID + " " + request.method() + " " + request.uri() + " streamId=" + request.streamId() + " contentLength=" + contentLength);
        }
    }

    /**
     * Releases the staged request parts and, on failure, also releases the not-yet-emitted full request.
     */
    private void releaseAggregatedRequest(List<HttpObject> parts, DefaultFullHttpRequest fullReq, boolean success) {
        if (!success) {
            fullReq.release();
        }
        for (HttpObject part : parts) {
            if (part != null) {
                part.release();
            }
        }
    }

    /**
     * Performs queue cleanup and state transition for the current aggregation result.
     */
    private void finishAggregation(ProtoRcvQueue<HttpObject> src, boolean success, boolean handled) {
        if (success) {
            this.resetAggregation(src);
        } else if (handled && this.isDiscardMode()) {
            this.clearAggregationQueue(src);
        } else if (handled) {
            this.resetAggregation(src);
        } else {
            this.clearAggregationQueue(src);
        }
    }

    @Override
    protected String logPrefix() {
        return "[HTTP-REQ-AGG]";
    }

    /**
     * Validates the completed request head and applies expectation handling.
     */
    @Override
    protected void onHeadersClosed(ProtoContext context, HttpRequest message, HttpHeaders headers, long contentLength) {
        if (contentLength > this.maxContentLength()) {
            this.sendAutoResponse(context, message, HttpStatus.REQUEST_ENTITY_TOO_LARGE, headers);
            this.enterDiscardMode();
            return;
        }
        this.handleExpectation(context, message, headers, contentLength);
    }

    /**
     * Sends a 413 response and switches to discard mode once the request body grows beyond the configured limit.
     */
    @Override
    protected boolean onContentTooLarge(ProtoContext context, HttpRequest message, HttpHeaders headers, int newLength) {
        this.sendAutoResponse(context, message, HttpStatus.REQUEST_ENTITY_TOO_LARGE, headers);
        this.enterDiscardMode();
        return true;
    }

    /**
     * Converts request-side protocol failures into HTTP auto-responses when enough request context is available.
     */
    @Override
    protected boolean handleProtocolError(ProtoContext context, HttpProtocolException e, boolean fromPipelineError, HttpRequest request, HttpHeaders headers) {
        if (request != null) {
            this.sendAutoResponse(context, request, e.status(), headers);
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

    /**
     * Processes the {@code Expect} header after the request head has been fully assembled.
     */
    private boolean handleExpectation(ProtoContext context, HttpRequest request, HttpHeaders headers, long contentLength) {
        String expect = headers != null ? headers.getString(HttpHeaderNames.EXPECT) : null;
        if (expect == null) {
            return false;
        }

        String expectValue = expect.trim();
        if (expectValue.isEmpty()) {
            return false;
        }
        if (!StringUtils.equalsIgnoreCase(expectValue, HttpHeaderValues.CONTINUE)) {
            this.sendAutoResponse(context, request, HttpStatus.EXPECTATION_FAILED, headers);
            this.enterDiscardMode();
            return true;
        }

        if (contentLength < 0 || contentLength <= this.maxContentLength()) {
            this.sendAutoResponse(context, request.protocolVersion(), request.streamId(), HttpStatus.CONTINUE, true);
        }
        return false;
    }

    /**
     * Sends an automatic response that reuses the current request version and keep-alive policy.
     */
    private void sendAutoResponse(ProtoContext context, HttpRequest request, HttpStatus status, HttpHeaders headers) {
        this.sendAutoResponse(context, request.protocolVersion(), request.streamId(), status, this.isKeepAlive(request, headers));
    }

    /**
     * Sends an automatically generated HTTP response and waits for the send future to complete.
     */
    private void sendAutoResponse(ProtoContext context, HttpVersion protocolVersion, long streamId, HttpStatus status, boolean keepAlive) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(protocolVersion, status, ByteBuf.wrap(new byte[0]));
        response.streamId(streamId);
        response.setHeader(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO);

        if (!keepAlive) {
            response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        }

        Future<?> sendFuture = context.sendData(response);
        try {
            if (sendFuture != null) {
                sendFuture.get();
            }
        } catch (Exception e) {
            throw new RuntimeException("failed to send auto response", e);
        }
        if (context.getConfig().isPrintLog()) {
            long channelID = context.getChannel().getChannelId();
            logger.info(this.logPrefix() + " channel=" + channelID + " auto-response status=" + status.code() + " keepAlive=" + keepAlive + " streamId=" + streamId);
        }
    }

    /**
     * Resolves the effective keep-alive policy from the request version and the current connection header.
     */
    private boolean isKeepAlive(HttpRequest request, HttpHeaders headers) {
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
