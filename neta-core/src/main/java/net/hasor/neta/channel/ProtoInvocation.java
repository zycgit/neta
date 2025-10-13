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
import net.hasor.cobble.logging.Logger;

import java.util.Objects;

/**
 * RCV_UP and RCV_DOWN,SND_UP and SND_DOWN. Is the name of RCV and SND under different endpoints.
 * When the {@link ProtoStack} forms a chain, the rcv event upward propagates,the snd event downward propagates.
 * <p>
 * When two {@link ProtoDuplexer} are connected, the endpoint object is shared in the same direction. e.g., RCV_DOWN and RCV_UP.
 * For convenience, use the DOWN name
 * </p>
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
    public static final  String                                            RCV_ERROR_TAG = ProtoChainRoot.class.getName() + "-rcv-error-tag";
    public static final  String                                            SND_ERROR_TAG = ProtoChainRoot.class.getName() + "-snd-error-tag";
    private static final Logger                                            logger        = Logger.getLogger(ProtoInvocation.class);
    protected final      ProtoQueue<Object>                                rcvUp;
    protected final      ProtoQueue<Object>                                sndUp;
    private final        String                                            name;
    private final        ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> handler;
    //
    private final        ProtoChainRoot                                    chainRoot;
    protected            ProtoInvocation<Object, Object, Object, Object>   previous;
    protected            ProtoInvocation<Object, Object, Object, Object>   next;

    ProtoInvocation(String name, int rcvSize, int sndSize, ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> handler, ProtoChainRoot chainRoot) {
        Objects.requireNonNull(handler, "handler is null.");

        this.name = name;
        this.handler = handler;

        this.rcvUp = new ProtoQueue<>(rcvSize < 0 ? -1 : rcvSize);
        this.sndUp = new ProtoQueue<>(sndSize < 0 ? -1 : sndSize);

        this.chainRoot = chainRoot;
    }

    ProtoInvocation(String name, ProtoConfig protoConf, ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> handler, ProtoChainRoot chainRoot) {
        Objects.requireNonNull(protoConf, "protoConf is null.");
        Objects.requireNonNull(handler, "handler is null.");

        this.name = name;
        this.handler = handler;

        int rcvSize = protoConf.getRcvSlotSize();
        int sndSize = protoConf.getSndSlotSize();
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

    public String toMonitorRcvString() {
        int capacity = this.rcvUp.getCapacity();
        if (capacity > 500) {
            return this.rcvUp.queueSize() + "/500+";
        } else {
            return this.rcvUp.queueSize() + "/" + capacity;
        }
    }

    public String toMonitorSndString() {
        int capacity = this.sndUp.getCapacity();
        if (capacity > 500) {
            return this.sndUp.queueSize() + "/500+";
        } else {
            return this.sndUp.queueSize() + "/" + capacity;
        }
    }

    public void onInit(ProtoContext protoCtx) throws Throwable {
        try {
            protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_NAME, this.name);
            this.handler.onInit(protoCtx);
        } finally {
            protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_NAME, null);
        }
    }

    public void onActive(ProtoContext protoCtx) throws Throwable {
        try {
            protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_NAME, this.name);
            this.handler.onActive(protoCtx);
        } finally {
            protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_NAME, null);
        }
    }

    public void onClose(ProtoContext protoCtx) {
        try {
            protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_NAME, this.name);
            this.handler.onClose(protoCtx);
        } finally {
            protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_NAME, null);
        }
    }

    public boolean onEvent(ProtoContext protoCtx, SoUserEvent event) throws Throwable {
        return this.handler.onUserEvent(protoCtx, event);
    }

    public ProtoStatus doLayer(ProtoContext protoCtx, boolean isRcv) throws Throwable {
        ProtoRcvQueue<RCV_UP> rcvUp = (ProtoRcvQueue<RCV_UP>) this.rcvUp;
        ProtoSndQueue<RCV_DOWN> rcvDown = (ProtoSndQueue<RCV_DOWN>) (this.next == null ? this.chainRoot.getTailRcvDown() : this.next.rcvUp);
        ProtoRcvQueue<SND_UP> sndUp = (ProtoRcvQueue<SND_UP>) this.sndUp;
        ProtoSndQueue<SND_DOWN> sndDown = (ProtoSndQueue<SND_DOWN>) (this.previous == null ? this.chainRoot.getHeadSndDown() : this.previous.sndUp);

        String errorTag = isRcv ? RCV_ERROR_TAG : SND_ERROR_TAG;
        Throwable ctxError = protoCtx.flash(errorTag);
        try {
            protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_NAME, this.name);
            if (ctxError == null) {
                return this.handler.onMessage(protoCtx, isRcv, rcvUp, rcvDown, sndUp, sndDown);
            } else {
                return this.handler.onError(protoCtx, isRcv, ctxError, this.createExceptionHandler(isRcv, protoCtx));
            }
        } catch (Throwable e) {
            if (ctxError == null) {
                String msgTag = isRcv ? "rcv" : "snd";
                long channelID = protoCtx.getChannel().getChannelId();
                if (protoCtx.getConfig().isPrintLog()) {
                    logger.error(msgTag + "(" + channelID + ") " + this.handler.getClass() + " an error has occurred " + e.getClass().getName() + ": " + e.getMessage(), e);
                } else {
                    logger.error(msgTag + "(" + channelID + ") " + this.handler.getClass() + " an error has occurred " + e.getClass().getName() + ": " + e.getMessage());
                }

                protoCtx.flash(errorTag, e);
                return this.handler.onError(protoCtx, isRcv, e, this.createExceptionHandler(isRcv, protoCtx));
            } else {
                throw e;
            }
        } finally {
            protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_NAME, null);
            rcvUp.rcvSubmit();
            rcvDown.sndSubmit();
            sndUp.rcvSubmit();
            sndDown.sndSubmit();
        }
    }

    private ProtoExceptionHolder createExceptionHandler(boolean isRcv, ProtoContext protoCtx) {
        return new ProtoExceptionHolderImpl(isRcv, protoCtx);
    }

    private static class ProtoExceptionHolderImpl implements ProtoExceptionHolder {
        private final String       errorTag;
        private final ProtoContext context;

        public ProtoExceptionHolderImpl(boolean isRcv, ProtoContext context) {
            this.errorTag = isRcv ? RCV_ERROR_TAG : SND_ERROR_TAG;
            this.context = context;
        }

        @Override
        public void clear() {
            this.context.flash(this.errorTag, null);
        }
    }
}