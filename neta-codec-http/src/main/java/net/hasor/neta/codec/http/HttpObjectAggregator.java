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
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.bytebuf.CompositeByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.event.HttpThroughEvent;

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
        HttpContext.getOrCreate(context);
    }

    @Override
    public boolean onUserEvent(ProtoContext context, SoUserEvent event) {
        if (event.getEventType() != HttpThroughEvent.class) {
            return true;
        }

        HttpThroughEvent modeEvent = (HttpThroughEvent) event.getData();
        HttpContext.getOrCreate(context).switchTransparentMode(modeEvent.enabled());
        this.resetAggregation();
        if (context.getConfig() != null && context.getConfig().isPrintLog()) {
            long channelID = context.getChannel() != null ? context.getChannel().getChannelId() : 0;
            logger.info("[HTTP-AGG] channel=" + channelID + " transparent-mode=" + modeEvent.enabled() + ", aggregation reset");
        }
        return true;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        HttpContext httpContext = HttpContext.getOrCreate(context);
        httpContext.consumeInboundErrorType();
        if (httpContext.isTransparentMode()) {
            this.resetAggregation();
            while (src.hasMore()) {
                HttpObject msg = src.takeMessage();
                if (msg != null) {
                    dst.offerMessage(msg);
                }
            }
            return ProtoStatus.Next;
        }

        while (src.hasMore()) {
            HttpObject msg = src.takeMessage();
            if (msg == null) {
                continue;
            }

            if (this.phase == AggregatePhase.DISCARD && this.discardMessage(msg)) {
                continue;
            }

            try {
                this.handleMessage(context, msg, dst);
            } catch (HttpProtocolException e) {
                if (this.handleProtocolError(context, e, false)) {
                    continue;
                }
                this.resetAggregation();
                throw e;
            }
        }

        return ProtoStatus.Next;
    }

    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (e instanceof HttpProtocolException && this.handleProtocolError(context, (HttpProtocolException) e, true)) {
            eh.clear();
            return ProtoStatus.Next;
        }
        if (!(e instanceof HttpContentTooLargeException && this.phase == AggregatePhase.DISCARD)) {
            this.resetAggregation();
        }
        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        this.resetAggregation();
    }

    //

    private void handleMessage(ProtoContext context, HttpObject msg, ProtoSndQueue<HttpObject> dst) {
        if (msg instanceof FullHttpRequest || msg instanceof FullHttpResponse) {
            if (this.phase != AggregatePhase.IDLE) {
                throw new HttpProtocolViolationException("received a full HTTP message before the previous aggregated message completed");
            }
            dst.offerMessage(msg);
            return;
        }

        if (msg instanceof HttpRequest) {
            if (this.phase != AggregatePhase.IDLE) {
                throw new HttpProtocolViolationException("received HttpRequest before previous aggregated message completed");
            }
            this.resetFor(msg);
            return;
        }

        if (msg instanceof HttpResponse) {
            if (this.phase != AggregatePhase.IDLE) {
                throw new HttpProtocolViolationException("received HttpResponse before previous aggregated message completed");
            }
            this.resetFor(msg);
            return;
        }

        if (msg instanceof HttpHeaders) {
            appendHeaders(context, (HttpHeaders) msg);
            return;
        }

        if (msg instanceof LastHttpContent) {
            LastHttpContent last = (LastHttpContent) msg;
            appendContent(context, last.content(), true);
            emitAggregated(context, dst);
            return;
        }

        if (msg instanceof HttpContent) {
            appendContent(context, ((HttpContent) msg).content(), false);
            return;
        }

        throw new HttpProtocolViolationException("unsupported HTTP object for aggregation: " + msg.getClass().getName());
    }

    private boolean discardMessage(HttpObject msg) {
        if (msg instanceof LastHttpContent) {
            this.resetAggregation();
            return true;
        }
        if (msg instanceof HttpContent || msg instanceof HttpHeaders) {
            return true;
        }
        if (msg instanceof FullHttpRequest || msg instanceof FullHttpResponse || msg instanceof HttpRequest || msg instanceof HttpResponse) {
            this.resetAggregation();
            return false;
        }
        return true;
    }

    private void appendHeaders(ProtoContext context, HttpHeaders headers) {
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

        this.phase = AggregatePhase.HEADERS;
        this.currentHeaders.appendHeaders(headers);
        if (headers instanceof LastHttpHeaders) {
            this.headersClosed = true;
            this.phase = AggregatePhase.BODY;

            long contentLength = this.currentHeaders.getLong(HttpHeaderNames.CONTENT_LENGTH, -1);

            if (this.currentMessage instanceof HttpRequest) {
                HttpRequest request = (HttpRequest) this.currentMessage;
                if (contentLength > this.maxContentLength) {
                    this.sendAutoResponse(context, request, HttpStatus.REQUEST_ENTITY_TOO_LARGE);
                    this.enterDiscardMode();
                    return;
                }

                if (this.handleExpectation(context, request, contentLength)) {
                    return;
                }
            }

            if (contentLength > this.maxContentLength) {
                throw new HttpContentTooLargeException("content length exceeds maximum: " + contentLength + " > " + this.maxContentLength, this.maxContentLength, contentLength);
            }
        }
    }

    private void appendContent(ProtoContext context, ByteBuf content, boolean lastContent) {
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
        if (newLength > this.maxContentLength) {
            if (this.currentMessage instanceof HttpRequest) {
                this.sendAutoResponse(context, (HttpRequest) this.currentMessage, HttpStatus.REQUEST_ENTITY_TOO_LARGE);
                this.enterDiscardMode();
                return;
            }
            throw new HttpContentTooLargeException("content length exceeds maximum: " + newLength + " > " + this.maxContentLength, this.maxContentLength, newLength);
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

    private void emitAggregated(ProtoContext context, ProtoSndQueue<HttpObject> dst) {
        if (this.currentMessage == null) {
            return;
        }

        ByteBuf aggregated = this.aggregatedContent != null ? this.aggregatedContent : ByteBuf.EMPTY;
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
            DefaultFullHttpRequest fullReq = new DefaultFullHttpRequest(req.protocolVersion(), req.method(), req.uri(), aggregated, headers);
            fullReq.streamId(req.streamId());
            dst.offerMessage(fullReq);
            if (printLog) {
                logger.info("[HTTP-AGG] channel=" + channelID + " " + req.method() + " " + req.uri() + " streamId=" + req.streamId() + " contentLength=" + this.currentContentLength);
            }
        } else if (this.currentMessage instanceof HttpResponse) {
            HttpResponse resp = (HttpResponse) this.currentMessage;
            DefaultFullHttpResponse fullResp = new DefaultFullHttpResponse(resp.protocolVersion(), resp.status(), aggregated, headers);
            fullResp.streamId(resp.streamId());
            dst.offerMessage(fullResp);
            if (printLog) {
                logger.info("[HTTP-AGG] channel=" + channelID + " response status=" + resp.status().code() + " streamId=" + resp.streamId() + " contentLength=" + this.currentContentLength);
            }
        }

        this.resetAggregation();
    }

    private boolean handleExpectation(ProtoContext context, HttpRequest request, long contentLength) {
        String expect = this.currentHeaders.getString(HttpHeaderNames.EXPECT);
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

        if (contentLength < 0 || contentLength <= this.maxContentLength) {
            this.sendAutoResponse(context, request.protocolVersion(), request.streamId(), HttpStatus.CONTINUE, true);
        }
        return false;
    }

    private boolean handleProtocolError(ProtoContext context, HttpProtocolException e, boolean fromPipelineError) {
        HttpRequest request = this.currentMessage instanceof HttpRequest ? (HttpRequest) this.currentMessage : null;
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
            logger.info("[HTTP-AGG] channel=" + channelID + " auto-response status=" + status.code() + " keepAlive=" + keepAlive + " streamId=" + streamId);
        }
    }

    private boolean isKeepAlive(HttpRequest request) {
        String connection = this.currentHeaders != null ? this.currentHeaders.getString(HttpHeaderNames.CONNECTION) : null;
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

    private void enterDiscardMode() {
        if (this.aggregatedContent != null) {
            this.aggregatedContent.free();
        }
        this.phase = AggregatePhase.DISCARD;
        this.currentMessage = null;
        this.currentHeaders = null;
        this.trailingHeaders = null;
        this.aggregatedContent = null;
        this.currentContentLength = 0;
        this.headersClosed = true;
    }

    private void resetFor(HttpObject message) {
        this.phase = AggregatePhase.START;
        this.currentMessage = message;
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
        TRAILERS,
        DISCARD
    }
}
