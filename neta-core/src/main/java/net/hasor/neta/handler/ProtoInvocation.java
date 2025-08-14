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
package net.hasor.neta.handler;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoStack;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final Logger                                            logger        = Logger.getLogger(ProtoInvocation.class);
    public static final  String                                            RCV_ERROR_TAG = ProtoChainRoot.class.getName() + "-rcv-error-tag";
    public static final  String                                            SND_ERROR_TAG = ProtoChainRoot.class.getName() + "-snd-error-tag";
    private final        String                                            name;
    private final        ProtoConfig                                       config;
    private final        AtomicBoolean                                     inited;
    //
    private              ProtoQueue<RCV_DOWN>                              rcvDown;
    private              ProtoQueue<SND_DOWN>                              sndDown;
    private final        ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> handler;

    public ProtoInvocation(String name, ProtoConfig protoConf, ProtoDuplexer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> handler) {
        Objects.requireNonNull(protoConf, "protoConf is null.");
        Objects.requireNonNull(handler, "handler is null.");

        this.name = name;
        this.config = protoConf;
        this.inited = new AtomicBoolean();
        this.handler = handler;
    }

    /** return this {@link ProtoDuplexer} name. */
    public String getName() {
        return this.name;
    }

    /** the {@link ProtoDuplexer} RCV_DOWN to connect the next {@link ProtoDuplexer} RCV_UP. */
    public ProtoQueue<RCV_DOWN> getRcvDown() {
        return this.rcvDown;
    }

    /** the {@link ProtoDuplexer} SND_DOWN to connect the next {@link ProtoDuplexer} SND_UP. */
    public ProtoQueue<SND_DOWN> getSndDown() {
        return this.sndDown;
    }

    @Override
    public String toString() {
        return "Handler [name=" + this.name + ", queue=" + this.rcvDown.queueSize() + ", slot=" + this.sndDown.slotSize() + "]";
    }

    public String toMonitorRcvString() {
        int capacity = this.rcvDown.getCapacity();
        if (capacity > 500) {
            return this.rcvDown.queueSize() + "/500+";
        } else {
            return this.rcvDown.queueSize() + "/" + capacity;
        }
    }

    public String toMonitorSndString() {
        int capacity = this.sndDown.getCapacity();
        if (capacity > 500) {
            return this.sndDown.queueSize() + "/500+";
        } else {
            return this.sndDown.queueSize() + "/" + capacity;
        }
    }

    public void onInit(ProtoContext protoCtx) throws Throwable {
        if (this.inited.compareAndSet(false, true)) {
            this.rcvDown = new ProtoQueue<>(this.config.getRcvDownSlotSize());
            this.sndDown = new ProtoQueue<>(this.config.getSndUpSlotSize());
            this.handler.onInit(protoCtx);
        }
    }

    public void onActive(ProtoContext protoCtx) throws Throwable {
        this.handler.onActive(protoCtx);
    }

    public void onClose(ProtoContext protoCtx) {
        if (this.inited.compareAndSet(true, false)) {
            this.handler.onClose(protoCtx);
        }
    }

    public ProtoStatus doLayer(ProtoContext protoCtx, boolean isRcv, ProtoRcvQueue<RCV_UP> rcvUp, ProtoRcvQueue<SND_UP> sndUp) throws Throwable {
        String errorTag = isRcv ? RCV_ERROR_TAG : SND_ERROR_TAG;
        Throwable ctxError = protoCtx.flash(errorTag);
        try {
            if (ctxError == null) {
                return this.handler.onMessage(protoCtx, isRcv, rcvUp, this.rcvDown, sndUp, this.sndDown);
            } else {
                return this.handler.onError(protoCtx, isRcv, ctxError, this.createExceptionHandler(isRcv, protoCtx, rcvUp, sndUp));
            }
        } catch (Throwable e) {
            if (ctxError == null) {
                String msgTag = isRcv ? "rcv" : "snd";
                long channelID = protoCtx.getChannel().getChannelID();
                if (protoCtx.getConfig().isPrintLog()) {
                    logger.error(msgTag + "(" + channelID + ") " + this.handler.getClass() + " an error has occurred " + e.getClass().getName() + ": " + e.getMessage(), e);
                } else {
                    logger.error(msgTag + "(" + channelID + ") " + this.handler.getClass() + " an error has occurred " + e.getClass().getName() + ": " + e.getMessage());
                }

                protoCtx.flash(errorTag, e);
                return this.handler.onError(protoCtx, isRcv, e, this.createExceptionHandler(isRcv, protoCtx, rcvUp, sndUp));
            } else {
                throw e;
            }
        } finally {
            rcvUp.rcvSubmit();
            this.rcvDown.sndSubmit();
            sndUp.rcvSubmit();
            this.sndDown.sndSubmit();
        }
    }

    private ProtoExceptionHolder createExceptionHandler(boolean isRcv, ProtoContext protoCtx, ProtoRcvQueue<RCV_UP> rcvUp, ProtoRcvQueue<SND_UP> sndUp) {
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