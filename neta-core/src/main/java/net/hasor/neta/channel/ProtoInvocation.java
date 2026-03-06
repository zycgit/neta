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

/**
 * A single node in the {@link ProtoStackChain} doubly-linked handler chain.
 * <p>Wraps one {@link ProtoDuplexer} together with its RCV_UP / SND_UP queues,
 * and links to the previous/next nodes for bidirectional event propagation.</p>
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
    public static final  String                                            RCV_ERROR_TAG = ProtoStackChain.class.getName() + "-rcv-error-tag";
    public static final  String                                            SND_ERROR_TAG = ProtoStackChain.class.getName() + "-snd-error-tag";
    private static final Logger                                            logger        = Logger.getLogger(ProtoInvocation.class);
    protected final      ProtoQueue<Object>                                rcvUp;
    protected final      ProtoQueue<Object>                                sndUp;
    private final        String                                            name;
    private final        ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> handler;
    //
    private final        ProtoStackChain                                   chainRoot;
    protected            ProtoInvocation<Object, Object, Object, Object>   previous;
    protected            ProtoInvocation<Object, Object, Object, Object>   next;

    ProtoInvocation(String name, int rcvSize, int sndSize, ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> handler, ProtoStackChain chainRoot) {
        Objects.requireNonNull(handler, "handler is null.");

        this.name = name;
        this.handler = handler;
        this.rcvUp = new ProtoQueue<>(rcvSize < 0 ? -1 : rcvSize);
        this.sndUp = new ProtoQueue<>(sndSize < 0 ? -1 : sndSize);
        this.chainRoot = chainRoot;
    }

    /** return this {@link ProtoDuplexer} name. */
    public String getName() {
        return this.name;
    }

    @Override
    public String toString() {
        return "Handler [name=" + this.name + ", queue=" + this.rcvUp.queueSize() + ", slot=" + this.sndUp.slotSize() + "]";
    }

    /** RCV queue occupancy as {@code "current/capacity"}, or {@code "n/500+"} for unbounded queues. */
    public String toMonitorRcvString() {
        int capacity = this.rcvUp.getCapacity();
        if (capacity > 500) {
            return this.rcvUp.queueSize() + "/500+";
        } else {
            return this.rcvUp.queueSize() + "/" + capacity;
        }
    }

    /** SND queue occupancy as {@code "current/capacity"}, or {@code "n/500+"} for unbounded queues. */
    public String toMonitorSndString() {
        int capacity = this.sndUp.getCapacity();
        if (capacity > 500) {
            return this.sndUp.queueSize() + "/500+";
        } else {
            return this.sndUp.queueSize() + "/" + capacity;
        }
    }

    /** Calls {@link ProtoDuplexer#onInit} on the wrapped handler, with {@code stackName} set in context. */
    public void onInit(ProtoContext protoCtx) throws Throwable {
        ProtoContextService ctx = (ProtoContextService) protoCtx;
        try {
            ctx.setStackName(this.name);
            this.handler.onInit(protoCtx);
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

    /** Calls {@link ProtoDuplexer#onActive} on the wrapped handler, with {@code stackName} set in context. */
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

    /** Calls {@link ProtoDuplexer#onClose} on the wrapped handler, with {@code stackName} set in context. */
    public void onClose(ProtoContext protoCtx) {
        ProtoContextService ctx = (ProtoContextService) protoCtx;
        try {
            ctx.setStackName(this.name);
            this.handler.onClose(protoCtx);
        } catch (Throwable e) {
            long channelID = protoCtx.getChannel().getChannelId();
            if (protoCtx.getConfig().isPrintLog()) {
                logger.error("channel(" + channelID + ") Stack " + this.name + " onClose error: " + e.getMessage(), e);
            } else {
                logger.error("channel(" + channelID + ") Stack " + this.name + " onClose error: " + e.getMessage());
            }
        } finally {
            ctx.setStackName(null);
        }
    }

    /** Delivers a user-defined event to the wrapped handler. return {@code true} to continue propagation, {@code false} to consume the event */
    public boolean onEvent(ProtoContext protoCtx, SoUserEvent event, boolean isRcv) throws Throwable {
        ProtoContextService ctx = (ProtoContextService) protoCtx;
        try {
            ctx.setStackName(this.name);
            return this.handler.onUserEvent(protoCtx, event, isRcv);
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

    /** Executes one pass of this handler node. */
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
            rcvUp.rcvSubmit();
            rcvDown.sndSubmit();
            sndUp.rcvSubmit();
            sndDown.sndSubmit();
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