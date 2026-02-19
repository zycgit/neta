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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.constant.HttpHeaderNames;

/**
 * Aggregates a sequence of {@link HttpObject}s (an {@link HttpMessage} followed by
 * {@link HttpContent}s and a {@link LastHttpContent}) into a single
 * {@link FullHttpRequest} or {@link FullHttpResponse}.
 * <p>
 * This handler sits after the decoder in the pipeline and collects the streamed
 * HTTP message parts into a complete message object.
 * <p>
 * If the content exceeds {@code maxContentLength}, an
 * {@link net.hasor.neta.codec.http.exception.HttpContentTooLargeException} is thrown.
 * <p>Pipeline usage:</p>
 * <pre>
 *   ctx.addLastDecoder("http-request", new HttpRequestDecoder());
 *   ctx.addLastDecoder("http-aggregator", new HttpObjectAggregator(1048576)); // 1MB max
 * </pre>
 */
public class HttpObjectAggregator implements ProtoHandler<HttpObject, HttpObject> {
    /** Default maximum content length (1 MB). */
    private static final int         DEFAULT_MAX_CONTENT_LENGTH = 1048576;
    private final        int         maxContentLength;
    // Aggregation state
    private              HttpMessage currentMessage;
    private              ByteBuf     aggregatedContent;
    private              HttpHeaders trailingHeaders;
    private              int         currentContentLength;

    /** Creates an aggregator with the default maximum content length (1 MB). */
    public HttpObjectAggregator() {
        this(DEFAULT_MAX_CONTENT_LENGTH);
    }

    /**
     * Creates an aggregator with the specified maximum content length.
     * @param maxContentLength the maximum allowed content length in bytes
     */
    public HttpObjectAggregator(int maxContentLength) {
        if (maxContentLength <= 0) {
            throw new IllegalArgumentException("maxContentLength must be positive");
        }
        this.maxContentLength = maxContentLength;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            if (msg instanceof FullHttpRequest || msg instanceof FullHttpResponse) {
                // Already aggregated, pass through
                dst.offerMessage(msg);
                continue;
            }

            if (msg instanceof HttpRequest) {
                // Start of a new request
                startAggregation(context, (HttpRequest) msg);
                continue;
            }

            if (msg instanceof HttpResponse) {
                // Start of a new response
                startAggregation(context, (HttpResponse) msg);
                continue;
            }

            if (msg instanceof LastHttpContent) {
                // End of message - finalize and emit
                LastHttpContent last = (LastHttpContent) msg;
                appendContent(last.content());

                if (last.trailerHeaders() != null && !last.trailerHeaders().isEmpty()) {
                    trailingHeaders = last.trailerHeaders();
                }

                emitAggregated(dst);
                continue;
            }

            if (msg instanceof HttpContent) {
                // Body chunk - accumulate
                appendContent(((HttpContent) msg).content());
            }
        }

        return ProtoStatus.Next;
    }

    /**
     * Starts aggregation for a new HTTP message.
     */
    private void startAggregation(ProtoContext context, HttpMessage message) {
        // Reset state from previous message
        if (aggregatedContent != null) {
            aggregatedContent.free();
        }
        currentMessage = message;
        // Initial capacity: min(256, maxContentLength) to avoid requesting more than the limit
        int initCap = Math.min(256, maxContentLength);
        aggregatedContent = context.byteBufAllocator().buffer(initCap, maxContentLength);
        trailingHeaders = new HttpHeaders();
        currentContentLength = 0;
    }

    /**
     * Appends content to the aggregated buffer.
     */
    private void appendContent(ByteBuf content) {
        if (content == null || content.readableBytes() == 0) {
            return;
        }

        if (currentMessage == null) {
            throw new net.hasor.neta.codec.http.exception.HttpMalformedRequestException("received HttpContent without preceding HttpMessage");
        }

        int readable = content.readableBytes();
        int newLength = currentContentLength + readable;
        if (newLength > maxContentLength) {
            throw new net.hasor.neta.codec.http.exception.HttpContentTooLargeException("content length exceeds maximum: " + newLength + " > " + maxContentLength);
        }

        int written = aggregatedContent.writeBuffer(content, readable);
        currentContentLength += written;
    }

    /**
     * Emits the aggregated message to the output queue.
     * <p>
     * Automatically sets the {@code Content-Length} header on the aggregated
     * message based on the actual accumulated content size, and removes
     * {@code Transfer-Encoding} if present (since the body is now complete).
     */
    private void emitAggregated(ProtoSndQueue<HttpObject> dst) {
        if (currentMessage == null) {
            return;
        }

        // Finalize the content buffer
        aggregatedContent.markWriter();

        // Auto-set Content-Length and remove Transfer-Encoding
        HttpHeaders headers = currentMessage.headers();
        headers.set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(currentContentLength));
        headers.remove(HttpHeaderNames.TRANSFER_ENCODING);

        if (currentMessage instanceof HttpRequest) {
            HttpRequest req = (HttpRequest) currentMessage;
            DefaultFullHttpRequest fullReq = new DefaultFullHttpRequest(req.protocolVersion(), req.method(), req.uri(), aggregatedContent, req.headers(), trailingHeaders != null ? trailingHeaders : new HttpHeaders());
            dst.offerMessage(fullReq);
        } else if (currentMessage instanceof HttpResponse) {
            HttpResponse resp = (HttpResponse) currentMessage;
            DefaultFullHttpResponse fullResp = new DefaultFullHttpResponse(resp.protocolVersion(), resp.status(), aggregatedContent, resp.headers(), trailingHeaders != null ? trailingHeaders : new HttpHeaders());
            dst.offerMessage(fullResp);
        }

        // Reset but don't free aggregatedContent - it's now owned by the full message
        currentMessage = null;
        aggregatedContent = null;
        trailingHeaders = null;
        currentContentLength = 0;
    }

    @Override
    public void onClose(ProtoContext context) {
        if (aggregatedContent != null) {
            aggregatedContent.free();
            aggregatedContent = null;
        }
        currentMessage = null;
        trailingHeaders = null;
    }
}
