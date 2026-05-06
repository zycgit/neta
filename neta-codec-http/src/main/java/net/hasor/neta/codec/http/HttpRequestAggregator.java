/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.codec.http;
import java.util.concurrent.Future;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoRcvQueueView;
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
    private static final int PREALLOCATE_COPY_THRESHOLD = 1024;

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
        ProtoRcvQueueView<HttpObject> staged = this.stagedView(src);
        if (staged == null) {
            this.resetAggregation(src);
            return;
        }

        HttpObject first = staged.takeMessage();
        if (!(first instanceof HttpRequest)) {
            if (first != null) {
                first.release();
            }
            this.resetAggregation(src);
            return;
        }

        HttpRequest request = (HttpRequest) first;
        HttpRequest requestToRelease = request;
        DefaultHttpHeaders mergedHeaders = null;
        DefaultHttpRequest requestLine = this.requestLineForFull(request);
        if (requestLine == request) {
            requestToRelease = null;
        }
        DefaultFullHttpRequest fullReq = null;
        ByteBuf aggregatedContent = ByteBuf.EMPTY;
        ByteBuf contiguousContent = null;
        HttpObject current = null;
        boolean handled = false;
        boolean success = false;
        try {
            HttpContext.RequestDecodeState reqCtx = HttpContext.getOrCreate(context).req;
            int contentLength = 0;
            long declaredLength = reqCtx.contentLength;
            while ((current = staged.takeMessage()) != null) {
                HttpObject part = current;
                current = null;
                boolean releasePart = true;
                if (part instanceof HttpHeaders) {
                    mergedHeaders = this.mergeHeadersBlock(mergedHeaders, (HttpHeaders) part, request.streamId());
                    if (declaredLength < 0) {
                        declaredLength = mergedHeaders.getLong(HttpHeaderNames.CONTENT_LENGTH, -1);
                    }
                    if (part == mergedHeaders) {
                        releasePart = false;
                    }
                    if (part instanceof LastHttpHeaders && !this.isHeadersClosedHandled()) {
                        this.onHeadersClosed(context, request, mergedHeaders, declaredLength);
                        if (mergedHeaders.isBad() || this.isDiscardMode()) {
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
                        mergedHeaders = this.ensureMergedHeaders(mergedHeaders, request.streamId());
                        if (this.onContentTooLarge(context, request, mergedHeaders, newLength) && this.isDiscardMode()) {
                            handled = true;
                            return;
                        }
                        throw new HttpContentTooLargeException("content length exceeds maximum: " + newLength + " > " + this.maxContentLength(), this.maxContentLength(), newLength);
                    }

                    contentLength = newLength;
                    if (readable > 0) {
                        if (contiguousContent == null && this.shouldPreallocateContentBuffer(declaredLength, contentLength - readable)) {
                            contiguousContent = this.allocateContentBuffer(content, declaredLength);
                            aggregatedContent = contiguousContent;
                        }

                        if (contiguousContent != null) {
                            contiguousContent.writeBuffer(content, readable);
                        } else {
                            aggregatedContent = this.appendContent(aggregatedContent, part);
                        }
                    }
                }

                if (releasePart) {
                    part.release();
                }
            }

            mergedHeaders = this.ensureMergedHeaders(mergedHeaders, request.streamId());
            fullReq = new DefaultFullHttpRequest(requestLine, mergedHeaders, aggregatedContent);
            this.completeFullRequest(request, fullReq, reqCtx, contentLength);
            dst.offerMessage(fullReq);

            this.logAggregatedRequest(context, request, contentLength);
            success = true;
        } finally {
            this.releaseAggregatedRequest(requestToRelease, current, fullReq, aggregatedContent, mergedHeaders, success);
            this.finishAggregation(src, success, handled);
        }
    }

    @Override
    protected void onHeadersStaged(ProtoContext context, ProtoRcvQueue<HttpObject> src) {
        ProtoRcvQueueView<HttpObject> staged = this.stagedView(src);
        if (staged == null) {
            return;
        }

        HttpObject first = staged.peekMessage();
        if (!(first instanceof HttpRequest)) {
            return;
        }

        HttpRequest request = (HttpRequest) first;
        HttpContext.RequestDecodeState reqCtx = HttpContext.getOrCreate(context).req;
        this.onHeadersClosed(context, request, reqCtx.contentLength, reqCtx.expectHeader, reqCtx.connectionHeader);
    }

    /**
     * Finalizes the aggregated request headers and propagates request-line metadata.
     */
    private void completeFullRequest(HttpRequest request, DefaultFullHttpRequest fullReq, HttpContext.RequestDecodeState reqCtx, int contentLength) {
        if (reqCtx.contentLength != contentLength || reqCtx.chunked || reqCtx.contentLength < 0) {
            fullReq.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(contentLength));
        }
        if (reqCtx.chunked) {
            fullReq.removeHeader(HttpHeaderNames.TRANSFER_ENCODING);
        }

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

    private DefaultHttpRequest requestLineForFull(HttpRequest request) {
        if (request instanceof DefaultHttpRequest) {
            return (DefaultHttpRequest) request;
        }
        return new DefaultHttpRequest(request.protocolVersion(), request.method(), request.uri());
    }

    private ByteBuf appendContent(ByteBuf current, HttpObject part) {
        if (part instanceof HttpContent) {
            return DefaultFullHttpRequest.appendContent(current, ((HttpContent) part).transferContent());
        }
        if (part instanceof DefaultHttpByteBuf) {
            return DefaultFullHttpRequest.appendContent(current, ((DefaultHttpByteBuf) part).transferContent());
        }
        throw new IllegalStateException("unexpected content-bearing request part: " + part.getClass().getName());
    }

    private DefaultHttpHeaders mergeHeadersBlock(DefaultHttpHeaders mergedHeaders, HttpHeaders headers, long streamId) {
        if (mergedHeaders == null) {
            if (headers instanceof DefaultHttpHeaders) {
                DefaultHttpHeaders adopted = (DefaultHttpHeaders) headers;
                adopted.streamId(streamId);
                return adopted;
            }
            mergedHeaders = new DefaultLastHttpHeaders();
            mergedHeaders.streamId(streamId);
        }

        mergedHeaders.appendOrTransferHeaders(headers);
        return mergedHeaders;
    }

    private DefaultHttpHeaders ensureMergedHeaders(DefaultHttpHeaders mergedHeaders, long streamId) {
        if (mergedHeaders != null) {
            return mergedHeaders;
        }

        DefaultHttpHeaders headers = new DefaultLastHttpHeaders();
        headers.streamId(streamId);
        return headers;
    }

    private boolean shouldPreallocateContentBuffer(long declaredLength, int accumulatedLength) {
        return false;
    }

    private ByteBuf allocateContentBuffer(ByteBuf content, long declaredLength) {
        ByteBufAllocator allocator = content.alloc();
        if (allocator == null) {
            allocator = ByteBufAllocator.DEFAULT;
        }
        return allocator.buffer((int) declaredLength, (int) declaredLength);
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
    private void releaseAggregatedRequest(HttpRequest request, HttpObject current, DefaultFullHttpRequest fullReq, ByteBuf aggregatedContent, DefaultHttpHeaders mergedHeaders, boolean success) {
        if (!success && fullReq != null) {
            fullReq.release();
        }
        if (!success && fullReq == null && aggregatedContent != null && aggregatedContent != ByteBuf.EMPTY) {
            aggregatedContent.release();
        }
        if (!success && fullReq == null && mergedHeaders != null) {
            mergedHeaders.release();
        }
        if (current != null) {
            current.release();
        }
        if (request != null) {
            request.release();
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
        HttpContext.RequestDecodeState reqCtx = HttpContext.getOrCreate(context).req;
        this.onHeadersClosed(context, message, contentLength, reqCtx.expectHeader, reqCtx.connectionHeader);
    }

    private void onHeadersClosed(ProtoContext context, HttpRequest message, long contentLength, String expect, String connection) {
        if (contentLength > this.maxContentLength()) {
            this.sendAutoResponse(context, message.protocolVersion(), message.streamId(), HttpStatus.REQUEST_ENTITY_TOO_LARGE, this.isKeepAlive(message, connection));
            this.enterDiscardMode();
            return;
        }
        this.handleExpectation(context, message, expect, connection, contentLength);
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
    private boolean handleExpectation(ProtoContext context, HttpRequest request, String expect, String connection, long contentLength) {
        if (expect == null) {
            return false;
        }

        String expectValue = expect.trim();
        if (expectValue.isEmpty()) {
            return false;
        }
        if (!StringUtils.equalsIgnoreCase(expectValue, HttpHeaderValues.CONTINUE)) {
            this.sendAutoResponse(context, request.protocolVersion(), request.streamId(), HttpStatus.EXPECTATION_FAILED, this.isKeepAlive(request, connection));
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
        this.sendAutoResponse(context, request.protocolVersion(), request.streamId(), status, this.isKeepAlive(request, headerValue(headers, HttpHeaderNames.CONNECTION)));
    }

    private boolean isKeepAlive(HttpRequest request, String connection) {
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

    private static String headerValue(HttpHeaders headers, String name) {
        return headers != null ? headers.getString(name) : null;
    }

}
