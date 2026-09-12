/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/**
 * Incrementally aggregates an ordered HTTP object stream.
 * <p>
 * Consumed header entries and content buffers belong to this handler until the terminal
 * {@link LastHttpContent} transfers them to a full message. Intermediate wrappers are
 * released as they are consumed; no receive-side staging queue is retained.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-11
 */
public abstract class AbstractHttpAggregator<M extends HttpObject> implements ProtoHandler<HttpObject, HttpObject> {
    public static final int                DEFAULT_MAX_CONTENT_LENGTH = 1048576;
    protected final     Logger             logger                     = Logger.getLogger(this.getClass());
    private final       int                maxContentLength;
    private             AggregateState     state                      = AggregateState.IDLE;
    private             M                  message;
    private             DefaultHttpHeaders headers;
    private             ByteBuf            content                    = ByteBuf.EMPTY;
    private             int                contentLength;
    private             boolean            headersClosedHandled;

    protected AbstractHttpAggregator() {
        this(DEFAULT_MAX_CONTENT_LENGTH);
    }

    protected AbstractHttpAggregator(int maxContentLength) {
        if (maxContentLength <= 0) {
            throw new IllegalArgumentException("maxContentLength must be positive");
        }
        this.maxContentLength = maxContentLength;
    }

    private enum AggregateState {
        IDLE,
        DISCARD,
        PADDING
    }

    @Override
    public void onInit(String name, int poolSize, ProtoContext context) {
        HttpContext.getOrCreate(context);
    }

    @Override
    public boolean onEvent(ProtoContext context, SoEvent event) {
        if (event.getEventType() != HttpThroughEvent.class) {
            return true;
        }
        HttpThroughEvent throughEvent = (HttpThroughEvent) event.getData();
        HttpContext.getOrCreate(context).switchTransparentMode(throughEvent.isEnabled(), throughEvent.streamId());
        this.resetAggregation();
        if (context.getConfig().isPrintLog()) {
            logger.info(this.logPrefix() + " channel=" + context.getChannel().getChannelId() + " transparent-mode=" + throughEvent.isEnabled() + ", aggregation reset");
        }
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        HttpContext httpContext = HttpContext.getOrCreate(context);
        httpContext.consumeInboundErrorType();
        try {
            if (httpContext.isTransparentMode()) {
                this.resetAggregation();
                while (src.hasMore()) {
                    if (!dst.hasSlot()) {
                        return ProtoStatus.Stop;
                    }
                    this.offerMessage(dst, src.takeMessage());
                }
                return ProtoStatus.Next;
            }

            while (src.hasMore()) {
                HttpObject next = src.peekMessage();
                if (next == null) {
                    src.skipMessage(1);
                    continue;
                }
                if (this.state == AggregateState.DISCARD) {
                    boolean last = next instanceof LastHttpContent;
                    SoUtils.release(src.takeMessage());
                    if (last) {
                        this.state = AggregateState.IDLE;
                    }
                    continue;
                }
                if (this.state == AggregateState.IDLE) {
                    if (next instanceof FullHttpRequest || next instanceof FullHttpResponse) {
                        if (!dst.hasSlot()) {
                            return ProtoStatus.Stop;
                        }
                        this.offerMessage(dst, src.takeMessage());
                        continue;
                    }
                    if (!this.isStartMessage(next)) {
                        this.enterDiscardMode();
                        HttpProtocolException e = new HttpProtocolException("unexpected HTTP object for aggregation: " + next.getClass().getName());
                        if (this.handleProtocolError(context, e, false, null, null)) {
                            continue;
                        }
                        throw e;
                    }
                    this.message = (M) src.takeMessage();
                    this.state = AggregateState.PADDING;
                    continue;
                }

                // Leave the terminal object in the input queue until the full message can be published.
                boolean last = next instanceof LastHttpContent;
                if (last && !dst.hasSlot()) {
                    return ProtoStatus.Stop;
                }
                this.appendPart(context, src.takeMessage());
                if (last) {
                    if (this.state == AggregateState.PADDING) {
                        this.emitAggregated(context, dst);
                    } else if (this.state == AggregateState.DISCARD) {
                        this.state = AggregateState.IDLE;
                    }
                }
            }
            return ProtoStatus.Next;
        } catch (Throwable e) {
            this.resetAggregation();
            throw e;
        }
    }

    private void appendPart(ProtoContext context, HttpObject part) {
        boolean releasePart = true;
        try {
            if (part instanceof HttpHeaders) {
                if (this.headers == null && part instanceof DefaultHttpHeaders) {
                    this.headers = (DefaultHttpHeaders) part;
                    releasePart = false;
                    this.headers.streamId(this.message.streamId());
                } else {
                    DefaultHttpHeaders merged = this.ensureHeaders();
                    if (part.isBad()) {
                        merged.markBad(part.badReason());
                    }
                    merged.appendOrTransferHeaders((HttpHeaders) part);
                }
                if (part instanceof LastHttpHeaders && !this.headersClosedHandled) {
                    this.headersClosedHandled = true;
                    this.onHeadersClosed(context, this.message, this.headers, this.headers.getLong(HttpHeaderNames.CONTENT_LENGTH, -1));
                    if (this.state != AggregateState.PADDING) {
                        return;
                    }
                }
            }

            ByteBuf incoming = part instanceof HttpContent ? ((HttpContent) part).content() : part instanceof HttpByteBuf ? ((HttpByteBuf) part).content() : null;
            if (incoming == null) {
                return;
            }
            int readable = incoming.readableBytes();
            if (readable == 0) {
                return;
            }
            if (readable > this.maxContentLength - this.contentLength) {
                long newLength = (long) this.contentLength + readable;
                if (this.onContentTooLarge(context, this.message, this.ensureHeaders(), (int) Math.min(Integer.MAX_VALUE, newLength)) && this.isDiscardMode()) {
                    return;
                }
                throw new HttpContentTooLargeException("content length exceeds maximum: " + newLength + " > " + this.maxContentLength, this.maxContentLength, newLength);
            }

            ByteBuf transferred = part instanceof HttpContent ? ((HttpContent) part).transferContent() : ((HttpByteBuf) part).transferContent();
            try {
                this.content = this.content == ByteBuf.EMPTY && transferred == incoming ? transferred : DefaultFullHttpRequest.appendContent(this.content, transferred);
            } catch (Throwable e) {
                SoUtils.release(transferred);
                throw e;
            }
            this.contentLength += readable;
        } finally {
            if (releasePart) {
                part.release();
            }
        }
    }

    private DefaultHttpHeaders ensureHeaders() {
        if (this.headers == null) {
            this.headers = new DefaultLastHttpHeaders();
            this.headers.streamId(this.message.streamId());
        }
        return this.headers;
    }

    private void emitAggregated(ProtoContext context, ProtoSndQueue<HttpObject> dst) throws ProtoFullException {
        DefaultHttpHeaders merged = this.ensureHeaders();
        boolean chunked = HttpCharSequences.containsIgnoreCase(merged.getString(HttpHeaderNames.TRANSFER_ENCODING), HttpHeaderValues.CHUNKED);
        if (chunked || merged.getLong(HttpHeaderNames.CONTENT_LENGTH, -1) != this.contentLength) {
            merged.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(this.contentLength));
        }
        if (chunked) {
            merged.removeHeader(HttpHeaderNames.TRANSFER_ENCODING);
        }
        HttpObject full = this.newFullMessage(context, this.message, merged, this.content);
        this.clearState();
        this.offerMessage(dst, full);
    }

    private void offerMessage(ProtoSndQueue<HttpObject> dst, HttpObject message) throws ProtoFullException {
        if (message == null) {
            return;
        }
        boolean offered = false;
        try {
            offered = dst.offerMessage(message);
            if (!offered) {
                throw ProtoFullException.INSTANCE;
            }
        } finally {
            if (!offered) {
                message.release();
            }
        }
    }

    @Override
    public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) throws Throwable {
        if (e instanceof HttpProtocolException && this.handleProtocolError(context, (HttpProtocolException) e, true, null, null)) {
            eh.clear();
            return ProtoStatus.Next;
        }
        if (!(e instanceof HttpContentTooLargeException && this.state == AggregateState.DISCARD)) {
            this.resetAggregation();
        }
        return ProtoStatus.Next;
    }

    @Override
    public void onClose(ProtoContext context) {
        this.resetAggregation();
    }

    protected final int maxContentLength() {
        return this.maxContentLength;
    }

    protected final boolean isDiscardMode() {
        return this.state == AggregateState.DISCARD;
    }

    /** Releases the partial message and returns to the initial state. */
    protected final void resetAggregation() {
        M oldMessage = this.message;
        DefaultHttpHeaders oldHeaders = this.headers;
        ByteBuf oldContent = this.content;
        this.clearState();
        try {
            SoUtils.release(oldMessage);
        } finally {
            try {
                SoUtils.release(oldHeaders);
            } finally {
                SoUtils.release(oldContent);
            }
        }
    }

    private void clearState() {
        this.message = null;
        this.headers = null;
        this.content = ByteBuf.EMPTY;
        this.contentLength = 0;
        this.headersClosedHandled = false;
        this.state = AggregateState.IDLE;
    }

    /** Releases the partial message and discards input through its terminal content. */
    protected final void enterDiscardMode() {
        this.resetAggregation();
        this.state = AggregateState.DISCARD;
    }

    protected final boolean isHeadersClosedHandled() {
        return this.headersClosedHandled;
    }

    protected boolean handleProtocolError(ProtoContext context, HttpProtocolException e, boolean fromPipelineError, M message, HttpHeaders headers) {
        this.resetAggregation();
        return false;
    }

    /** Called once with all initial headers, before any following content is appended. */
    protected void onHeadersClosed(ProtoContext context, M message, HttpHeaders headers, long contentLength) {
        if (contentLength > this.maxContentLength) {
            throw new HttpContentTooLargeException("content length exceeds maximum: " + contentLength + " > " + this.maxContentLength, this.maxContentLength, contentLength);
        }
    }

    protected boolean onContentTooLarge(ProtoContext context, M message, HttpHeaders headers, int newLength) {
        return false;
    }

    protected abstract boolean isStartMessage(HttpObject msg);

    /**
     * Creates a full message, transferring ownership of all arguments on successful return.
     * On failure, ownership remains with the aggregator.
     */
    protected abstract HttpObject newFullMessage(ProtoContext context, M message, DefaultHttpHeaders headers, ByteBuf content);

    protected abstract String logPrefix();
}
