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
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.data.ProtoRcvQueue;
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
        List<HttpObject> parts = this.takeStagedParts(src);
        if (parts == null || parts.isEmpty()) {
            this.resetAggregation(src);
            return;
        }

        HttpResponse response = (HttpResponse) parts.get(0);
        DefaultFullHttpResponse fullResp = new DefaultFullHttpResponse(response.protocolVersion(), response.status(), ByteBuf.EMPTY);
        boolean handled = false;
        boolean success = false;
        try {
            int contentLength = 0;
            for (HttpObject part : parts) {
                if (part instanceof HttpHeaders) {
                    fullResp.appendHeaders((HttpHeaders) part);
                    if (part instanceof LastHttpHeaders && !this.isHeadersClosedHandled()) {
                        long declaredLength = fullResp.getLong(HttpHeaderNames.CONTENT_LENGTH, -1);
                        this.onHeadersClosed(context, response, fullResp, declaredLength);
                        if (fullResp.isBad() || this.isDiscardMode()) {
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
                        if (this.onContentTooLarge(context, response, fullResp, newLength) && this.isDiscardMode()) {
                            handled = true;
                            return;
                        }
                        throw new HttpContentTooLargeException("content length exceeds maximum: " + newLength + " > " + this.maxContentLength(), this.maxContentLength(), newLength);
                    }
                    contentLength = newLength;
                    if (readable > 0) {
                        this.appendContent(fullResp, part);
                    }
                }
            }

            this.completeFullResponse(response, fullResp, contentLength);
            dst.offerMessage(fullResp);

            this.logAggregatedResponse(context, response, contentLength);
            success = true;
        } finally {
            this.releaseAggregatedResponse(parts, fullResp, success);
            this.finishAggregation(src, success, handled);
        }
    }

    /**
     * Finalizes the aggregated response headers and propagates response-line metadata.
     */
    private void completeFullResponse(HttpResponse response, DefaultFullHttpResponse fullResp, int contentLength) {
        fullResp.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(contentLength));
        fullResp.removeHeader(HttpHeaderNames.TRANSFER_ENCODING);
        fullResp.streamId(response.streamId());
        if (response.isBad()) {
            fullResp.markBad(response.badReason());
        }
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

    private void appendContent(DefaultFullHttpResponse fullResp, HttpObject part) {
        if (part instanceof HttpContent) {
            fullResp.appendContent(((HttpContent) part).transferContent());
        } else if (part instanceof HttpByteBuf) {
            fullResp.appendContent(((HttpByteBuf) part).transferContent());
        } else {
            throw new IllegalStateException("unexpected content-bearing response part: " + part.getClass().getName());
        }
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
    private void releaseAggregatedResponse(List<HttpObject> parts, DefaultFullHttpResponse fullResp, boolean success) {
        if (!success) {
            fullResp.release();
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