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
import net.hasor.neta.codec.http.event.HttpThroughEvent;

/**
 * Shared base class for HTTP/1.x staged-message aggregation.
 * <p>
 * This handler converts the staged output of an HTTP decoder into one full in-memory message.
 * Concrete subclasses decide whether the start line is request-side or response-side and which
 * aggregated type to build.
 * <p>
 * Expected staged input shape:
 * <pre>
 *   Start-Line Object
 *      -> HttpHeaders
 *      -> HttpContent ...
 *      -> LastHttpContent
 *      => FullHttpRequest or FullHttpResponse
 * </pre>
 * <p>
 * pipeline view:
 * <pre>
 *   HttpRequestDecoder / HttpResponseDecoder
 *      -> HttpObject parts
 *      -> AbstractHttpAggregator subclass
 *      -> FullHttpRequest / FullHttpResponse
 * </pre>
 * <p>
 * When transparent mode is enabled, aggregation is reset and objects are forwarded as-is,
 * because upgraded protocols no longer follow HTTP message framing.
 */
public abstract class AbstractHttpAggregator<M extends HttpObject> implements ProtoHandler<HttpObject, HttpObject> {
    private final       Logger                    logger                     = Logger.getLogger(this.getClass());
    public static final int                       DEFAULT_MAX_CONTENT_LENGTH = 1048576;
    //
    private final       int                       maxContentLength;
    private             AggregatePhase            phase                      = AggregatePhase.IDLE;
    private             M                         currentMessage;
    private             DefaultHttpHeaders        currentHeaders;
    private             DefaultTrailerHttpHeaders trailingHeaders;
    private             ByteBuf                   aggregatedContent;
    private             int                       currentContentLength;
    private             boolean                   headersClosed;

    protected AbstractHttpAggregator() {
        this(DEFAULT_MAX_CONTENT_LENGTH);
    }

    protected AbstractHttpAggregator(int maxContentLength) {
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
            logger.info(this.logPrefix() + " channel=" + channelID + " transparent-mode=" + modeEvent.enabled() + ", aggregation reset");
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

    protected final boolean isIdle() {
        return this.phase == AggregatePhase.IDLE;
    }

    protected final M currentMessage() {
        return this.currentMessage;
    }

    protected final DefaultHttpHeaders currentHeaders() {
        return this.currentHeaders;
    }

    protected final int maxContentLength() {
        return this.maxContentLength;
    }

    protected final void resetAggregation() {
        this.releaseAggregationState(false);
        this.phase = AggregatePhase.IDLE;
        this.currentMessage = null;
        this.currentHeaders = null;
        this.trailingHeaders = null;
        this.aggregatedContent = null;
        this.currentContentLength = 0;
        this.headersClosed = false;
    }

    protected final void enterDiscardMode() {
        this.releaseAggregationState(false);
        this.phase = AggregatePhase.DISCARD;
        this.currentMessage = null;
        this.currentHeaders = null;
        this.trailingHeaders = null;
        this.aggregatedContent = null;
        this.currentContentLength = 0;
        this.headersClosed = true;
    }

    protected boolean handleProtocolError(ProtoContext context, HttpProtocolException e, boolean fromPipelineError) {
        this.resetAggregation();
        return false;
    }

    protected void onHeadersClosed(ProtoContext context, M message, long contentLength) {
        if (contentLength > this.maxContentLength) {
            throw new HttpContentTooLargeException("content length exceeds maximum: " + contentLength + " > " + this.maxContentLength, this.maxContentLength, contentLength);
        }
    }

    protected boolean onContentTooLarge(ProtoContext context, M message, int newLength) {
        return false;
    }

    protected abstract boolean isStartMessage(HttpObject msg);

    protected abstract boolean isFullMessage(HttpObject msg);

    protected abstract M castStartMessage(HttpObject msg);

    protected abstract HttpObject buildAggregatedMessage(M message, ByteBuf aggregated, DefaultHttpHeaders headers);

    protected abstract void logAggregated(ProtoContext context, M message, int contentLength);

    protected abstract String logPrefix();

    private void handleMessage(ProtoContext context, HttpObject msg, ProtoSndQueue<HttpObject> dst) {
        if (this.isFullMessage(msg)) {
            if (this.phase != AggregatePhase.IDLE) {
                throw new HttpProtocolViolationException("received a full HTTP message before the previous aggregated message completed");
            }
            dst.offerMessage(msg);
            return;
        }

        if (this.isStartMessage(msg)) {
            if (this.phase != AggregatePhase.IDLE) {
                throw new HttpProtocolViolationException("received " + msg.getClass().getSimpleName() + " before previous aggregated message completed");
            }
            this.resetFor(this.castStartMessage(msg));
            return;
        }

        if (msg instanceof HttpHeaders) {
            try {
                this.appendHeaders(context, (HttpHeaders) msg);
            } finally {
                msg.release();
            }
            return;
        }

        if (msg instanceof LastHttpContent) {
            LastHttpContent last = (LastHttpContent) msg;
            try {
                this.appendContent(context, last.content(), true);
            } finally {
                msg.release();
            }
            this.emitAggregated(context, dst);
            return;
        }

        if (msg instanceof HttpContent) {
            try {
                this.appendContent(context, ((HttpContent) msg).content(), false);
            } finally {
                msg.release();
            }
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
            this.onHeadersClosed(context, this.currentMessage, contentLength);
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
            if (this.onContentTooLarge(context, this.currentMessage, newLength)) {
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

        HttpObject fullMessage = this.buildAggregatedMessage(this.currentMessage, aggregated, headers);
        dst.offerMessage(fullMessage);
        this.logAggregated(context, this.currentMessage, this.currentContentLength);

        if (this.aggregatedContent != null) {
            this.aggregatedContent.free();
        }

        this.releaseAggregationState(true);
        this.phase = AggregatePhase.IDLE;
        this.currentMessage = null;
        this.currentHeaders = null;
        this.trailingHeaders = null;
        this.aggregatedContent = null;
        this.currentContentLength = 0;
        this.headersClosed = false;
    }

    private void resetFor(M message) {
        this.releaseAggregationState(false);
        this.phase = AggregatePhase.START;
        this.currentMessage = message;
        this.currentHeaders = new DefaultHttpHeaders();
        this.trailingHeaders = new DefaultTrailerHttpHeaders();
        this.aggregatedContent = null;
        this.currentContentLength = 0;
        this.headersClosed = false;
    }

    private void releaseAggregationState(boolean headersTransferred) {
        if (this.currentMessage != null) {
            this.currentMessage.release();
        }
        if (this.trailingHeaders != null) {
            this.trailingHeaders.release();
        }
        if (!headersTransferred) {
            if (this.currentHeaders != null) {
                this.currentHeaders.release();
            }
            if (this.aggregatedContent != null) {
                this.aggregatedContent.free();
            }
        }
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