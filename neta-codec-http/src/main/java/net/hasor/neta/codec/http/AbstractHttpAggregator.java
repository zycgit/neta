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
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoRcvQueueView;
import net.hasor.neta.channel.data.ProtoSndQueue;
/**
 * HTTP/1.x message aggregation base class built around a receive-side staging queue.
 * <p>
 * The handler does not assemble full messages while scanning the main receive queue. It first moves the ordered
 * {@link HttpObject} sequence into a named staging view and waits until the terminal {@link LastHttpContent} arrives.
 * Once the staged sequence is complete, subclasses turn that ordered list into a full request or response object.
 * <p>
 * The staging queue name is fixed per aggregator type. Under the current pipeline layout, request aggregation and
 * response aggregation already live in separate handler instances, so type-level naming is enough.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-11
 */
public abstract class AbstractHttpAggregator<M extends HttpObject> implements ProtoHandler<HttpObject, HttpObject> {
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 1048576;
    protected final Logger  logger                     = Logger.getLogger(this.getClass());
    private final String    STAGING_QUEUE_PREFIX       = this.getClass().getName() + ".staging";
    private final int       maxContentLength;
    private final String    stagingQueueKey            = STAGING_QUEUE_PREFIX;
    private AggregateState  state                      = AggregateState.IDLE;
    private boolean         headersClosedHandled       = false;

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

        HttpContext httpCtx = HttpContext.getOrCreate(context);
        HttpThroughEvent throughEvent = (HttpThroughEvent) event.getData();
        httpCtx.switchTransparentMode(throughEvent.isEnabled(), throughEvent.streamId());
        this.resetAggregation();

        if (context.getConfig().isPrintLog()) {
            long channelID = context.getChannel().getChannelId();
            logger.info(this.logPrefix() + " channel=" + channelID + " transparent-mode=" + throughEvent.isEnabled() + ", aggregation reset");
        }
        return true;
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst) throws Throwable {
        HttpContext httpContext = HttpContext.getOrCreate(context);
        httpContext.consumeInboundErrorType();
        if (httpContext.isTransparentMode()) {
            this.resetAggregation(src);
            while (src.hasMore()) {
                // Backpressure boundary: do not take from src until dst can accept one message.
                if (!dst.hasSlot()) {
                    return ProtoStatus.Stop;
                }

                HttpObject msg = src.takeMessage();
                if (msg != null) {
                    dst.offerMessage(msg);
                }
            }
            return ProtoStatus.Next;
        }

        while (src.hasMore()) {
            if (src.peekMessage() == null) {
                src.skipMessage(1);
                continue;
            }

            if (this.state == AggregateState.DISCARD) {
                this.clearAggregationQueue(src);

                HttpObject next = src.takeMessage();
                SoUtils.release(next);

                if (next instanceof LastHttpContent) {
                    this.state = AggregateState.IDLE;
                }

                continue;
            }

            if (this.state == AggregateState.IDLE) {
                HttpObject next = src.peekMessage();
                if (!this.isStartMessage(next)) {
                    this.enterDiscardMode(src);
                    HttpProtocolException e = new HttpProtocolException("unexpected HTTP object for aggregation: " + next.getClass().getName());
                    if (this.handleProtocolError(context, e, false, null, null)) {
                        continue;
                    }
                    throw e;
                }

                this.state = AggregateState.PADDING;
            }

            if (this.state == AggregateState.PADDING) {
                while (src.hasMore()) {
                    HttpObject next = src.peekMessage();
                    src.drainToQueue(this.stagingQueueKey, 1);

                    if (next instanceof LastHttpHeaders && !(next instanceof LastHttpContent) && !this.headersClosedHandled) {
                        this.onHeadersStaged(context, src);
                        this.headersClosedHandled = true;
                        if (this.state != AggregateState.PADDING) {
                            break;
                        }
                    }

                    if (next instanceof LastHttpContent) {
                        this.emitAggregated(context, src, dst);
                        if (this.state != AggregateState.PADDING) {
                            break;
                        }
                    }
                }
            }
        }

        return ProtoStatus.Next;
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

    /**
     * Clears the local aggregation state when the handler leaves the pipeline lifecycle.
     */
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

    /**
     * Resets only the local state machine.
     * <p>
     * This variant is used when the current receive queue is not available.
     */
    protected final void resetAggregation() {
        this.state = AggregateState.IDLE;
        this.headersClosedHandled = false;
    }

    protected final void resetAggregation(ProtoRcvQueue<HttpObject> src) {
        this.clearAggregationQueue(src);
        this.state = AggregateState.IDLE;
        this.headersClosedHandled = false;
    }

    /**
     * Switches the local state machine into discard mode without touching external queue state.
     */
    protected final void enterDiscardMode() {
        this.state = AggregateState.DISCARD;
    }

    /**
     * Clears the current staging queue and then switches the state machine into discard mode.
     */
    protected final void enterDiscardMode(ProtoRcvQueue<HttpObject> src) {
        this.clearAggregationQueue(src);
        this.state = AggregateState.DISCARD;
    }

    protected final boolean isHeadersClosedHandled() {
        return this.headersClosedHandled;
    }

    /**
     * Handles protocol-level aggregation failures.
     * <p>
     * The default behavior only resets local state and lets the error continue through the pipeline.
     */
    protected boolean handleProtocolError(ProtoContext context, HttpProtocolException e, boolean fromPipelineError, M message, HttpHeaders headers) {
        this.resetAggregation();
        return false;
    }

    /**
     * Called after the initial header section has been fully appended to the current aggregated message.
     */
    protected void onHeadersClosed(ProtoContext context, M message, HttpHeaders headers, long contentLength) {
        if (contentLength > this.maxContentLength) {
            throw new HttpContentTooLargeException("content length exceeds maximum: " + contentLength + " > " + this.maxContentLength, this.maxContentLength, contentLength);
        }
    }

    /**
     * Called when appending one more content chunk would exceed {@link #maxContentLength()}.
     */
    protected boolean onContentTooLarge(ProtoContext context, M message, HttpHeaders headers, int newLength) {
        return false;
    }

    /**
     * Returns all currently staged objects for this aggregator type in receive order.
     */
    protected final List<HttpObject> takeStagedParts(ProtoRcvQueue<HttpObject> src) {
        ProtoRcvQueueView<HttpObject> staged = src.queueView(this.stagingQueueKey);
        if (staged == null || !staged.hasMore()) {
            return null;
        }
        return staged.takeMessage(-1);
    }

    protected final List<HttpObject> peekStagedParts(ProtoRcvQueue<HttpObject> src) {
        ProtoRcvQueueView<HttpObject> staged = src.queueView(this.stagingQueueKey);
        if (staged == null || !staged.hasMore()) {
            return null;
        }
        return staged.peekMessage(-1);
    }

    protected final ProtoRcvQueueView<HttpObject> stagedView(ProtoRcvQueue<HttpObject> src) {
        ProtoRcvQueueView<HttpObject> staged = src.queueView(this.stagingQueueKey);
        if (staged == null || !staged.hasMore()) {
            return null;
        }
        return staged;
    }

    protected void onHeadersStaged(ProtoContext context, ProtoRcvQueue<HttpObject> src) {
    }

    /**
     * Discards the temporary staging queue from the provided receive queue.
     */
    protected final void clearAggregationQueue(ProtoRcvQueue<HttpObject> src) {
        if (src != null && src.hasQueue(this.stagingQueueKey)) {
            src.discard(this.stagingQueueKey);
        }
    }

    /**
     * Determines whether the current object can open a new aggregation sequence.
     */
    protected abstract boolean isStartMessage(HttpObject msg);

    /**
     * Builds and emits a full message from the staged ordered HTTP object sequence.
     */
    protected abstract void emitAggregated(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<HttpObject> dst);

    /**
     * Returns the log prefix used by the concrete aggregator.
     */
    protected abstract String logPrefix();
}
