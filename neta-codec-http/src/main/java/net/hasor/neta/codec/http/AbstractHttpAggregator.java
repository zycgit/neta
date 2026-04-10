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
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/**
 * Common base class for staged HTTP/1.x message aggregation.
 * <p>
 * This handler converts the staged messages emitted by the HTTP decoder into a single in-memory message.
 * Concrete subclasses decide whether the start line belongs to the request side or the response side,
 * and which aggregated message type should be produced.
 * <p>
 * When transparent mode is enabled, the aggregation state is reset and received objects are forwarded
 * unchanged because the upgraded protocol no longer follows HTTP message framing.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
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

    /**
     * Create an aggregator that uses the default maximum content length.
     */
    protected AbstractHttpAggregator() {
        this(DEFAULT_MAX_CONTENT_LENGTH);
    }

    /**
     * Create an aggregator with the specified maximum content length.
     * @param maxContentLength maximum allowed content length
     */
    protected AbstractHttpAggregator(int maxContentLength) {
        if (maxContentLength <= 0) {
            throw new IllegalArgumentException("maxContentLength must be positive");
        }
        this.maxContentLength = maxContentLength;
    }

    /**
     * Initialize the aggregator context.
     */
    @Override
    public void onInit(String name, int poolSize, ProtoContext context) {
        HttpContext.getOrCreate(context);
    }

    /**
     * Handle transparent-mode toggle events.
     */
    @Override
    public boolean onEvent(ProtoContext context, SoEvent event) {
        if (event.getEventType() != HttpThroughEvent.class) {
            return true;
        }

        HttpThroughEvent throughEvent = (HttpThroughEvent) event.getData();
        HttpContext.getOrCreate(context).switchTransparentMode(throughEvent.isEnabled(), throughEvent.streamId());
        this.resetAggregation();
        if (context.getConfig().isPrintLog()) {
            long channelID = context.getChannel().getChannelId();
            logger.info(this.logPrefix() + " channel=" + channelID + " transparent-mode=" + throughEvent.isEnabled() + ", aggregation reset");
        }
        return true;
    }

    /**
     * Aggregate received HTTP objects or pass them through unchanged.
     */
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

    /**
     * Handle exceptions raised during aggregation.
     */
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

    /**
     * Close the aggregator and reset its state.
     */
    @Override
    public void onClose(ProtoContext context) {
        this.resetAggregation();
    }

    //

    /**
     * Return whether the aggregator is currently idle.
     * @return whether the aggregator is idle
     */
    protected final boolean isIdle() {
        return this.phase == AggregatePhase.IDLE;
    }

    /**
     * Return the start message that is currently being aggregated.
     * @return the current start message
     */
    protected final M currentMessage() {
        return this.currentMessage;
    }

    /**
     * Return the header block that is currently being aggregated.
     * @return the current header block
     */
    protected final DefaultHttpHeaders currentHeaders() {
        return this.currentHeaders;
    }

    /**
     * Return the maximum content length allowed for aggregation.
     * @return the maximum content length
     */
    protected final int maxContentLength() {
        return this.maxContentLength;
    }

    /**
     * Reset the current aggregation state.
     */
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

    /**
     * Enter discard mode and clear the current aggregation state.
     */
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

    /**
     * Handle a protocol-level exception.
     * @param context protocol context
     * @param e protocol exception
     * @param fromPipelineError whether the exception came from the pipeline error callback
     * @return whether the exception has been consumed
     */
    protected boolean handleProtocolError(ProtoContext context, HttpProtocolException e, boolean fromPipelineError) {
        this.resetAggregation();
        return false;
    }

    /**
     * Perform validation after the header section has been closed.
     * @param context protocol context
     * @param message start message
     * @param contentLength parsed content length
     */
    protected void onHeadersClosed(ProtoContext context, M message, long contentLength) {
        if (contentLength > this.maxContentLength) {
            throw new HttpContentTooLargeException("content length exceeds maximum: " + contentLength + " > " + this.maxContentLength, this.maxContentLength, contentLength);
        }
    }

    /**
     * Perform custom handling when aggregated content exceeds the limit.
     * @param context protocol context
     * @param message start message
     * @param newLength new aggregated length
     * @return whether the oversized-content condition has already been handled
     */
    protected boolean onContentTooLarge(ProtoContext context, M message, int newLength) {
        return false;
    }

    /**
     * Determine whether the current object is the start message for aggregation.
     */
    protected abstract boolean isStartMessage(HttpObject msg);

    /**
     * Cast the start message to the concrete type used by the aggregator.
     */
    protected abstract M castStartMessage(HttpObject msg);

    /**
     * Build the fully aggregated message object.
     */
    protected abstract HttpObject buildAggregatedMessage(M message, ByteBuf aggregated, DefaultHttpHeaders headers);

    /**
     * Emit the log entry for a completed aggregation.
     */
    protected abstract void logAggregated(ProtoContext context, M message, int contentLength);

    /**
     * Return the log prefix.
     */
    protected abstract String logPrefix();

    private void handleMessage(ProtoContext context, HttpObject msg, ProtoSndQueue<HttpObject> dst) {
        boolean startMessage = this.isStartMessage(msg);
        if (startMessage && msg instanceof LastHttpHeaders && msg instanceof LastHttpContent) {
            dst.offerMessage(msg);
            return;
        }

        if (startMessage) {
            if (this.phase != AggregatePhase.IDLE) {
                throw new HttpProtocolStateException("received " + msg.getClass().getSimpleName() + " before previous aggregated message completed");
            }
            this.resetFor(this.castStartMessage(msg));
        }

        if (msg instanceof HttpHeaders) {
            try {
                this.appendHeaders(context, (HttpHeaders) msg);
            } finally {
                if (!startMessage) {
                    msg.release();
                }
            }
            return;
        }

        if (msg instanceof LastHttpContent) {
            LastHttpContent last = (LastHttpContent) msg;
            try {
                this.appendContent(context, last.content(), true);
            } finally {
                if (!startMessage) {
                    msg.release();
                }
            }
            this.emitAggregated(context, dst);
            return;
        }

        if (msg instanceof HttpContent) {
            try {
                this.appendContent(context, ((HttpContent) msg).content(), false);
            } finally {
                if (!startMessage) {
                    msg.release();
                }
            }
            return;
        }

        if (startMessage) {
            return;
        }

        throw new HttpProtocolException("unsupported HTTP object for aggregation: " + msg.getClass().getName());
    }

    private boolean discardMessage(HttpObject msg) {
        if (msg instanceof LastHttpContent) {
            this.resetAggregation();
            return true;
        }
        if (msg instanceof HttpContent || msg instanceof HttpHeaders) {
            return true;
        }
        if (this.isStartMessage(msg)) {
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
                throw new HttpProtocolStateException("received trailer headers before header section completed");
            }
            this.phase = AggregatePhase.TRAILERS;
            this.trailingHeaders.appendHeaders(headers);
            return;
        }

        if (this.headersClosed) {
            throw new HttpProtocolStateException("received initial headers after header section already closed");
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
            throw new HttpProtocolStateException("received HttpContent before LastHttpHeaders");
        }
        if (this.phase == AggregatePhase.TRAILERS) {
            if (!lastContent || content != null && content.readableBytes() > 0) {
                throw new HttpProtocolStateException("received HttpContent after trailer headers");
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