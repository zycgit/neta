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
package net.hasor.neta.channel;
import java.util.Objects;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/**
 * Single node in the doubly linked handler chain of {@link ProtoStackChain}.
 * <p>It wraps one {@link ProtoDuplexer} together with its RCV_UP and SND_UP queues and implements
 * bidirectional event propagation through links to previous and next nodes.</p>
 * <pre>
 *              Protocol Layer(0)               Protocol Layer(1)
 *         ┏━━━━━━━━━━━━━━━━━━━━━━━━┓       ┏━━━━━━━━━━━━━━━━━━━━━━━━┓
 *         ┃             ╭┄┄┄┄┄┄┄┄┄┄┸┄┄┄┄┄┄┄┸┄┄┄┄┄┄┄┄┄┄╮             ┃
 * DATA -> ┃ RCV_UP      ┆ RCV_DOWN    <=>    RCV_UP   ┆    RCV_DOWN ┃  -> ...
 *         ┃             ┆                             ┆             ┃
 * ...  <- ┃ SND_DOWN    ┆ SND_UP      <=>    SND_DOWN ┆      SND_UP ┃  <- DATA
 *         ┃             ╰┄┄┄┄┄┄┄┄┄┄┰┄┄┄┄┄┄┄┰┄┄┄┄┄┄┄┄┄┄╯             ┃
 *         ┗━━━━━━━━━━━━━━━━━━━━━━━━┛       ┗━━━━━━━━━━━━━━━━━━━━━━━━┛
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
class ProtoInvocation<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> {
    public static final String                                      RCV_ERROR_TAG = ProtoStackChain.class.getName() + "-rcv-error-tag";
    public static final String                                      SND_ERROR_TAG = ProtoStackChain.class.getName() + "-snd-error-tag";
    private static final Logger                                     logger        = Logger.getLogger(ProtoInvocation.class);
    private final ProtoQueueView                                    rcvUp;
    private final ProtoQueueView                                    sndUp;
    private final String                                            name;
    private final ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> handler;
    //
    private final ProtoStackChain                             chainRoot;
    protected ProtoInvocation<Object, Object, Object, Object> previous;
    protected ProtoInvocation<Object, Object, Object, Object> next;

    ProtoInvocation(String name, int rcvSize, int sndSize, ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> handler, ProtoStackChain chainRoot) {
        Objects.requireNonNull(handler, "handler is null.");

        this.name = name;
        this.handler = handler;
        this.rcvUp = new ProtoQueueView(rcvSize);
        this.sndUp = new ProtoQueueView(sndSize);
        this.chainRoot = chainRoot;
    }

    /**
     * Bind an on-writable-recovered callback for this node's inbound upstream queue.
     * @param callback callback triggered when the queue recovers from full to writable
     */
    public void bindRcvUpWritable(Runnable callback) {
        this.rcvUp.onRecoveredWritable(callback);
    }

    /**
     * Bind an on-writable-recovered callback for this node's outbound upstream queue.
     * @param callback callback triggered when the queue recovers from full to writable
     */
    public void bindSndUpWritable(Runnable callback) {
        this.sndUp.onRecoveredWritable(callback);
    }

    /**
     * Offer a batch of data into this node's inbound upstream queue.
     * @param offerData data array to offer
     * @return whether the whole batch was accepted successfully
     */
    public boolean offerRcvUp(Object[] offerData) {
        return this.rcvUp.offerMessage(offerData);
    }

    /**
     * Offer a batch of data into this node's outbound upstream queue.
     * @param offerData data array to offer
     * @return whether the whole batch was accepted successfully
     */
    public boolean offerSndUp(Object[] offerData) {
        return this.sndUp.offerMessage(offerData);
    }

    /**
     * Return the current remaining slot count of the inbound upstream queue.
     * @return remaining writable slot count
     */
    public int rcvUpSlotSize() {
        return this.rcvUp.slotSize();
    }

    /**
     * Return the current remaining slot count of the outbound upstream queue.
     * @return remaining writable slot count
     */
    public int sndUpSlotSize() {
        return this.sndUp.slotSize();
    }

    /** Return the name of the current {@link ProtoDuplexer}. */
    public String getName() {
        return this.name;
    }

    /**
     * Return the compact monitor string for the current node.
     * @return string containing the name and queue-capacity information
     */
    @Override
    public String toString() {
        return "Handler [name=" + this.name + ", queue=" + this.rcvUp.queueSize() + ", slot=" + this.sndUp.slotSize() + "]";
    }

    /** Return RCV queue occupancy as {@code "current/capacity"}; for unbounded queues, return {@code "n/500+"}. */
    public String toMonitorRcvString() {
        int capacity = this.rcvUp.getCapacity();
        if (capacity > 500) {
            return this.rcvUp.queueSize() + "/500+";
        } else {
            return this.rcvUp.queueSize() + "/" + capacity;
        }
    }

    /** Return SND queue occupancy as {@code "current/capacity"}; for unbounded queues, return {@code "n/500+"}. */
    public String toMonitorSndString() {
        int capacity = this.sndUp.getCapacity();
        if (capacity > 500) {
            return this.sndUp.queueSize() + "/500+";
        } else {
            return this.sndUp.queueSize() + "/" + capacity;
        }
    }

    //

    /** Set {@code stackName} in the context, then invoke {@link ProtoDuplexer#onInit} on the wrapped handler. */
    public void onInit(ProtoContext protoCtx) throws Throwable {
        ProtoContextService ctx = (ProtoContextService) protoCtx;
        try {
            ctx.setStackName(this.name);
            this.handler.onInit(this.name, this.rcvUp.getCapacity(), this.sndUp.getCapacity(), protoCtx);
        } catch (Throwable e) {
            long channelID = protoCtx.getChannel().getChannelId();
            if (protoCtx.getConfig().isPrintLog()) {
                logger.error("channel(" + channelID + ") Stack " + this.name + " onInit error: " + e.getMessage(), e);
            } else {
                logger.error("channel(" + channelID + ") Stack " + this.name + " onInit error: " + e.getMessage());
            }
            throw e;
        } finally {
            ctx.setStackName(null);
        }
    }

    /** Set {@code stackName} in the context, then invoke {@link ProtoDuplexer#onActive} on the wrapped handler. */
    public void onActive(ProtoContext protoCtx) throws Throwable {
        ProtoContextService ctx = (ProtoContextService) protoCtx;
        try {
            ctx.setStackName(this.name);
            this.handler.onActive(protoCtx);
        } catch (Throwable e) {
            long channelID = protoCtx.getChannel().getChannelId();
            if (protoCtx.getConfig().isPrintLog()) {
                logger.error("channel(" + channelID + ") Stack " + this.name + " onActive error: " + e.getMessage(), e);
            } else {
                logger.error("channel(" + channelID + ") Stack " + this.name + " onActive error: " + e.getMessage());
            }
            throw e;
        } finally {
            ctx.setStackName(null);
        }
    }

    /** Set {@code stackName} in the context, then invoke {@link ProtoDuplexer#onClose} on the wrapped handler. */
    public void onClose(ProtoContext protoCtx) {
        ProtoContextService ctx = (ProtoContextService) protoCtx;
        try {
            ctx.setStackName(this.name);
            this.handler.onClose(protoCtx);
        } catch (Throwable e) {
            long channelID = protoCtx.getChannel().getChannelId();
            if (protoCtx.getConfig().isPrintLog()) {
                logger.error("channel(" + channelID + ") Stack " + this.name + " onClose error: " + e.getClass().getName() + ": " + e.getMessage(), e);
            } else {
                logger.error("channel(" + channelID + ") Stack " + this.name + " onClose error: " + e.getMessage());
            }
        } finally {
            ctx.setStackName(null);
        }
    }

    /**
     * Perform final queue cleanup after {@link #onClose(ProtoContext)} returns.
     * <p>This method releases messages still left in the current handler node and still owned by
     * its queues. It is separated from {@code onClose} so handler close logic runs first, and queue
     * leftovers are reclaimed unconditionally afterward in the protocol-stack close path.</p>
     */
    public void afterClose() {
        this.rcvUp.clearAndClose();
        this.sndUp.clearAndClose();
    }

    /** Deliver a network event to the wrapped handler. Return {@code true} to continue propagation, or {@code false} to consume the event. */
    public boolean onEvent(ProtoContext protoCtx, SoEvent event, boolean isRcv) throws Throwable {
        ProtoContextService ctx = (ProtoContextService) protoCtx;
        try {
            ctx.setStackName(this.name);
            return this.handler.onEvent(protoCtx, event, isRcv);
        } catch (Throwable e) {
            long channelID = protoCtx.getChannel().getChannelId();
            if (protoCtx.getConfig().isPrintLog()) {
                logger.error("channel(" + channelID + ") " + (isRcv ? "rcv " : "snd ") + this.name + " onEvent error: " + e.getMessage(), e);
            } else {
                logger.error("channel(" + channelID + ") " + (isRcv ? "rcv " : "snd ") + this.name + " onEvent error: " + e.getMessage());
            }
            throw e;
        } finally {
            ctx.setStackName(null);
        }
    }

    /** Execute one processing pass for the current handler node. */
    public ProtoStatus doLayer(ProtoContext protoCtx, boolean isRcv) throws Throwable {
        ProtoContextService ctx = (ProtoContextService) protoCtx;
        ProtoRcvQueue<RCV_UP> rcvUp = (ProtoRcvQueue<RCV_UP>) this.rcvUp;
        ProtoSndQueue<RCV_DOWN> rcvDown = (ProtoSndQueue<RCV_DOWN>) (this.next == null ? this.chainRoot.getTailRcvDown() : this.next.rcvUp);
        ProtoRcvQueue<SND_UP> sndUp = (ProtoRcvQueue<SND_UP>) this.sndUp;
        ProtoSndQueue<SND_DOWN> sndDown = (ProtoSndQueue<SND_DOWN>) (this.previous == null ? this.chainRoot.getHeadSndDown() : this.previous.sndUp);

        try {
            ctx.setStackName(this.name);
            Throwable ctxError = isRcv ? ctx.getRcvError() : ctx.getSndError();
            if (ctxError == null) {
                try {
                    return this.handler.onMessage(protoCtx, isRcv, rcvUp, rcvDown, sndUp, sndDown);
                } catch (Throwable e) {
                    String msgTag = isRcv ? "rcv" : "snd";
                    long channelID = protoCtx.getChannel().getChannelId();
                    if (protoCtx.getConfig().isPrintLog()) {
                        logger.error(msgTag + "(" + channelID + ") " + this.handler.getClass() + " an error has occurred " + e.getClass().getName() + ": " + e.getMessage(), e);
                    } else {
                        logger.error(msgTag + "(" + channelID + ") " + this.handler.getClass() + " an error has occurred " + e.getClass().getName() + ": " + e.getMessage());
                    }

                    if (isRcv) {
                        ctx.setRcvError(e);
                    } else {
                        ctx.setSndError(e);
                    }

                    ctxError = e;
                }
            }

            return this.handler.onError(protoCtx, isRcv, ctxError, this.createExceptionHandler(isRcv, ctx));
        } finally {
            ctx.setStackName(null);
        }
    }

    private ProtoExceptionHolder createExceptionHandler(boolean isRcv, ProtoContextService ctx) {
        return new ProtoExceptionHolderImpl(isRcv, ctx);
    }

    private static class ProtoExceptionHolderImpl implements ProtoExceptionHolder {
        private final boolean             isRcv;
        private final ProtoContextService context;

        public ProtoExceptionHolderImpl(boolean isRcv, ProtoContextService context) {
            this.isRcv = isRcv;
            this.context = context;
        }

        @Override
        public void clear() {
            if (this.isRcv) {
                this.context.setRcvError(null);
            } else {
                this.context.setSndError(null);
            }
        }
    }
}