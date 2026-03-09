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
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.bytebuf.CompositeByteBuf;
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
    private static final Logger                    logger                     = Logger.getLogger(HttpObjectAggregator.class);
    /** Default maximum content length (1 MB). */
    private static final int                       DEFAULT_MAX_CONTENT_LENGTH = 1048576;
    private final        int                       maxContentLength;
    private              AggregatePhase            phase                      = AggregatePhase.IDLE;
    private              HttpObject                currentMessage;
    private              DefaultHttpHeaders        currentHeaders;
    private              DefaultTrailerHttpHeaders trailingHeaders;
    private              ByteBuf                   aggregatedContent;
    private              int                       currentContentLength;
    private              boolean                   headersClosed;

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
    public void onInit(String name, int poolSize, ProtoContext context) {
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            if (msg instanceof FullHttpRequest || msg instanceof FullHttpResponse) {
                if (this.phase != AggregatePhase.IDLE) {
                    throw new HttpProtocolViolationException("received a full HTTP message before the previous aggregated message completed");
                }
                dst.offerMessage(msg);
                continue;
            }

            if (msg instanceof HttpRequest) {
                if (this.phase != AggregatePhase.IDLE) {
                    throw new HttpProtocolViolationException("received HttpRequest before previous aggregated message completed");
                }
                this.resetFor(msg);
                continue;
            }

            if (msg instanceof HttpResponse) {
                if (this.phase != AggregatePhase.IDLE) {
                    throw new HttpProtocolViolationException("received HttpResponse before previous aggregated message completed");
                }
                this.resetFor(msg);
                continue;
            }

            if (msg instanceof HttpHeaders) {
                appendHeaders((HttpHeaders) msg);
                continue;
            }

            if (msg instanceof LastHttpContent) {
                LastHttpContent last = (LastHttpContent) msg;
                appendContent(last.content(), true);
                emitAggregated(context, dst);
                continue;
            }

            if (msg instanceof HttpContent) {
                appendContent(((HttpContent) msg).content(), false);
            }
        }

        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        this.resetAggregation();
    }

    //

    private void appendHeaders(HttpHeaders headers) {
        if (this.currentMessage == null) {
            throw new HttpBadRequestException("received HttpHeaders without preceding start line");
        }

        if (headers instanceof TrailerHttpHeaders) {
            if (!this.headersClosed) {
                throw new HttpProtocolViolationException("received trailer headers before header section completed");
            }
            this.phase = AggregatePhase.TRAILERS;
            this.trailingHeaders.appendHeaders(headers);
            return;
        }

        if (this.headersClosed) {
            throw new HttpProtocolViolationException("received initial headers after header section already closed");
        }

        if (!(headers instanceof TrailerHttpHeaders)) {
            this.phase = AggregatePhase.HEADERS;
            this.currentHeaders.appendHeaders(headers);
            if (headers instanceof LastHttpHeaders) {
                this.headersClosed = true;
                this.phase = AggregatePhase.BODY;
            }
            return;
        }
    }

    private void appendContent(ByteBuf content, boolean lastContent) {
        if (this.currentMessage == null) {
            throw new HttpBadRequestException("received HttpContent without preceding HttpMessage");
        }
        if (!this.headersClosed) {
            throw new HttpProtocolViolationException("received HttpContent before LastHttpHeaders");
        }
        if (this.phase == AggregatePhase.TRAILERS) {
            if (!lastContent || content != null && content.readableBytes() > 0) {
                throw new HttpProtocolViolationException("received HttpContent after trailer headers");
            }
            return;
        }

        if (content == null || content.readableBytes() == 0) {
            if (!lastContent) {
                this.phase = AggregatePhase.BODY;
            }
            return;
        }

        int readable = content.readableBytes();
        int newLength = this.currentContentLength + readable;
        if (newLength > maxContentLength) {
            throw new HttpContentTooLargeException("content length exceeds maximum: " + newLength + " > " + maxContentLength, maxContentLength, newLength);
        }

        ByteBuf aggregated = this.aggregatedContent;
        if (aggregated == null) {
            this.aggregatedContent = content.retain();
        } else if (aggregated instanceof CompositeByteBuf) {
            ((CompositeByteBuf) aggregated).addComponent(content);
        } else {
            CompositeByteBuf composite = ByteBufUtils.compositeBuffer(aggregated.alloc());
            composite.addComponent(aggregated);
            aggregated.free();
            composite.addComponent(content);
            this.aggregatedContent = composite;
        }
        this.currentContentLength = newLength;
        this.phase = AggregatePhase.BODY;
    }

    /** Emits the aggregated message to the output queue. */
    private void emitAggregated(ProtoContext context, ProtoSndQueue<HttpObject> dst) {
        if (this.currentMessage == null) {
            return;
        }

        ByteBuf aggregatedContent = this.aggregatedContent != null ? this.aggregatedContent : ByteBuf.EMPTY;

        // Auto-set Content-Length and remove Transfer-Encoding
        DefaultHttpHeaders headers = this.currentHeaders != null ? this.currentHeaders : new DefaultHttpHeaders();
        if (this.trailingHeaders != null && this.trailingHeaders.headerSize() > 0) {
            headers.appendHeaders(this.trailingHeaders);
        }
        headers.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(this.currentContentLength));
        headers.removeHeader(HttpHeaderNames.TRANSFER_ENCODING);

        boolean printLog = context.getConfig() != null && context.getConfig().isPrintLog();
        long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
        if (this.currentMessage instanceof HttpRequest) {
            HttpRequest req = (HttpRequest) this.currentMessage;
            DefaultFullHttpRequest fullReq = new DefaultFullHttpRequest(req.protocolVersion(), req.method(), req.uri(), aggregatedContent, headers);
            fullReq.streamId(req.streamId()); // propagate streamId for H2/H3 multiplexing

            dst.offerMessage(fullReq);
            if (printLog) {
                logger.info("[HTTP-AGG] channel=" + channelID + " " + req.method() + " " + req.uri() + " streamId=" + req.streamId() + " contentLength=" + this.currentContentLength);
            }
        } else if (this.currentMessage instanceof HttpResponse) {
            HttpResponse resp = (HttpResponse) this.currentMessage;
            DefaultFullHttpResponse fullResp = new DefaultFullHttpResponse(resp.protocolVersion(), resp.status(), aggregatedContent, headers);
            fullResp.streamId(resp.streamId()); // propagate streamId for H2/H3 multiplexing

            dst.offerMessage(fullResp);
            if (printLog) {
                logger.info("[HTTP-AGG] channel=" + channelID + " response status=" + resp.status().code() + " streamId=" + resp.streamId() + " contentLength=" + this.currentContentLength);
            }
        }

        this.resetAggregation();
    }

    private void resetAggregation() {
        if (this.aggregatedContent != null) {
            this.aggregatedContent.free();
        }
        this.phase = AggregatePhase.IDLE;
        this.currentMessage = null;
        this.currentHeaders = null;
        this.trailingHeaders = null;
        this.aggregatedContent = null;
        this.currentContentLength = 0;
        this.headersClosed = false;
    }

    private void resetFor(HttpObject currentMessage) {
        this.phase = AggregatePhase.START;
        this.currentMessage = currentMessage;
        this.currentHeaders = new DefaultHttpHeaders();
        this.trailingHeaders = new DefaultTrailerHttpHeaders();
        this.aggregatedContent = null;
        this.currentContentLength = 0;
        this.headersClosed = false;
    }

    private enum AggregatePhase {
        IDLE,
        START,
        HEADERS,
        BODY,
        TRAILERS
    }
}
