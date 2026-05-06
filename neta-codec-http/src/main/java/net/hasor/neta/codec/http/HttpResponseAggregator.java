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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoRcvQueueView;
import net.hasor.neta.channel.data.ProtoSndQueue;
/**
 * Aggregates an ordered HTTP response object sequence into a {@link FullHttpResponse}.
 * <p>
 * The first staged object is the response line, the last staged object is the terminal {@link LastHttpContent}, and
 * all staged objects in between are appended in-order to the aggregated response.
 * <p>
 * Unlike request aggregation, the response path does not synthesize protocol auto-responses. It only assembles the
 * full response, enforces the configured size limit, and leaves protocol failures to the common pipeline flow.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-11
 */
public class HttpResponseAggregator extends AbstractHttpAggregator<HttpResponse> {
    private static final int PREALLOCATE_COPY_THRESHOLD = 1024;

    /**
     * Creates a response aggregator with the default maximum content length.
     */
    public HttpResponseAggregator() {
        super();
    }

    /**
     * Creates a response aggregator with an explicit maximum content length.
     * @param maxContentLength maximum accepted aggregated payload length
     */
    public HttpResponseAggregator(int maxContentLength) {
        super(maxContentLength);
    }

    /**
     * Response aggregation always starts from the response line object.
     */
    @Override
    protected boolean isStartMessage(HttpObject msg) {
        return msg instanceof HttpResponse;
    }

    /**
     * Builds a full response directly on top of {@link DefaultFullHttpResponse} by appending the staged parts in order.
     */
    @Override
    protected void emitAggregated(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) {
        ProtoRcvQueueView<HttpObject> staged = this.stagedView(src);
        if (staged == null) {
            this.resetAggregation(src);
            return;
        }

        HttpObject first = staged.takeMessage();
        if (!(first instanceof HttpResponse)) {
            if (first != null) {
                first.release();
            }
            this.resetAggregation(src);
            return;
        }

        HttpResponse response = (HttpResponse) first;
        HttpResponse responseToRelease = response;
        DefaultHttpHeaders mergedHeaders = null;
        DefaultHttpResponse responseLine = this.responseLineForFull(response);
        if (responseLine == response) {
            responseToRelease = null;
        }
        DefaultFullHttpResponse fullResp = null;
        ByteBuf aggregatedContent = ByteBuf.EMPTY;
        ByteBuf contiguousContent = null;
        HttpObject current = null;
        boolean handled = false;
        boolean success = false;
        try {
            HttpContext.ResponseDecodeState respCtx = HttpContext.getOrCreate(context).resp;
            int contentLength = 0;
            long declaredLength = respCtx.contentLength;
            while ((current = staged.takeMessage()) != null) {
                HttpObject part = current;
                current = null;
                boolean releasePart = true;
                if (part instanceof HttpHeaders) {
                    mergedHeaders = this.mergeHeadersBlock(mergedHeaders, (HttpHeaders) part, response.streamId());
                    if (part == mergedHeaders) {
                        releasePart = false;
                    }
                    if (part instanceof LastHttpHeaders && !this.isHeadersClosedHandled()) {
                        declaredLength = mergedHeaders.getLong(HttpHeaderNames.CONTENT_LENGTH, -1);
                        this.onHeadersClosed(context, response, mergedHeaders, declaredLength);
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
                        mergedHeaders = this.ensureMergedHeaders(mergedHeaders, response.streamId());
                        if (this.onContentTooLarge(context, response, mergedHeaders, newLength) && this.isDiscardMode()) {
                            handled = true;
                            return;
                        }
                        throw new HttpContentTooLargeException("content length exceeds maximum: " + newLength + " > " + this.maxContentLength(), this.maxContentLength(), newLength);
                    }
                    if (readable > 0) {
                        if (contiguousContent == null && this.shouldPreallocateContentBuffer(declaredLength, contentLength)) {
                            contiguousContent = this.allocateContentBuffer(content, declaredLength);
                            aggregatedContent = contiguousContent;
                        }

                        if (contiguousContent != null) {
                            contiguousContent.writeBuffer(content, readable);
                        } else {
                            aggregatedContent = this.appendContent(aggregatedContent, part);
                        }
                    }
                    contentLength = newLength;
                }

                if (releasePart) {
                    part.release();
                }
            }

            mergedHeaders = this.ensureMergedHeaders(mergedHeaders, response.streamId());
            fullResp = new DefaultFullHttpResponse(responseLine, mergedHeaders, aggregatedContent);
            this.completeFullResponse(response, fullResp, respCtx, contentLength);
            dst.offerMessage(fullResp);

            this.logAggregatedResponse(context, response, contentLength);
            success = true;
        } finally {
            this.releaseAggregatedResponse(responseToRelease, current, fullResp, aggregatedContent, mergedHeaders, success);
            this.finishAggregation(src, success, handled);
        }
    }

    /**
     * Finalizes the aggregated response headers and propagates response-line metadata.
     */
    private void completeFullResponse(HttpResponse response, DefaultFullHttpResponse fullResp, HttpContext.ResponseDecodeState respCtx, int contentLength) {
        if (respCtx.contentLength != contentLength || respCtx.chunked || respCtx.contentLength < 0) {
            fullResp.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(contentLength));
        }
        if (respCtx.chunked) {
            fullResp.removeHeader(HttpHeaderNames.TRANSFER_ENCODING);
        }

        fullResp.streamId(response.streamId());
        if (response.isBad()) {
            fullResp.markBad(response.badReason());
        }
    }

    private DefaultHttpResponse responseLineForFull(HttpResponse response) {
        if (response instanceof DefaultHttpResponse) {
            return (DefaultHttpResponse) response;
        }
        return new DefaultHttpResponse(response.protocolVersion(), response.status());
    }

    private ByteBuf contentOf(HttpObject part) {
        if (part instanceof HttpContent) {
            return ((HttpContent) part).content();
        } else if (part instanceof HttpByteBuf) {
            return ((HttpByteBuf) part).content();
        } else {
            return null;
        }
    }

    private ByteBuf appendContent(ByteBuf current, HttpObject part) {
        if (part instanceof HttpContent) {
            return DefaultFullHttpResponse.appendContent(current, ((HttpContent) part).transferContent());
        } else if (part instanceof HttpByteBuf) {
            return DefaultFullHttpResponse.appendContent(current, ((HttpByteBuf) part).transferContent());
        } else {
            throw new IllegalStateException("unexpected content-bearing response part: " + part.getClass().getName());
        }
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

    private boolean shouldPreallocateContentBuffer(long declaredLength, int contentLength) {
        return declaredLength >= PREALLOCATE_COPY_THRESHOLD && declaredLength <= this.maxContentLength() && contentLength == 0;
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
    private void logAggregatedResponse(ProtoContext context, HttpResponse response, int contentLength) {
        if (context.getConfig().isPrintLog()) {
            long channelID = context.getChannel().getChannelId();
            logger.info(this.logPrefix() + " channel=" + channelID + " response status=" + response.status().code() + " streamId=" + response.streamId() + " contentLength=" + contentLength);
        }
    }

    /**
     * Releases the staged response parts and, on failure, also releases the not-yet-emitted full response.
     */
    private void releaseAggregatedResponse(HttpResponse response, HttpObject current, DefaultFullHttpResponse fullResp, ByteBuf aggregatedContent, DefaultHttpHeaders mergedHeaders, boolean success) {
        if (!success && fullResp != null) {
            fullResp.release();
        }
        if (!success && fullResp == null && aggregatedContent != null && aggregatedContent != ByteBuf.EMPTY) {
            aggregatedContent.release();
        }
        if (!success && fullResp == null && mergedHeaders != null) {
            mergedHeaders.release();
        }
        if (current != null) {
            current.release();
        }
        if (response != null) {
            response.release();
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
            this.resetAggregation(src);
        }
    }

    /**
     * Returns the log prefix used by response aggregation.
     */
    @Override
    protected String logPrefix() {
        return "[HTTP-RESP-AGG]";
    }
}
