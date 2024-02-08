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
import net.hasor.neta.channel.PipeContext;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RCV_UP and RCV_DOWN,SND_UP and SND_DOWN. Is the name of RCV and SND under different endpoints.
 * When the pipeline forms a chain, the rcv event upward propagates,the snd event downward propagates.
 * <p>
 * When two {@link PipeDuplex} are connected, the endpoint object is shared in the same direction. e.g., RCV_DOWN and RCV_UP.
 * For convenience, use the DOWN name
 * </p>
 * <pre>
 *                PipeLayer(0)                    PipeLayer (1)
 *         ┏━━━━━━━━━━━━━━━━━━━━━━━━┓       ┏━━━━━━━━━━━━━━━━━━━━━━━━┓
 *         ┃                        ┃       ┃                        ┃
 *         ┃             ╭┄┄┄┄┄┄┄┄┄┄┸┄┄┄┄┄┄┄┸┄┄┄┄┄┄┄┄┄┄╮             ┃
 * DATA -> ┃ RCV_UP      ┆ RCV_DOWN    <=>    RCV_UP   ┆    RCV_DOWN ┃  -> ...
 *         ┃             ┆                             ┆             ┃
 * ...  <- ┃ SND_DOWN    ┆ SND_UP      <=>    SND_DOWN ┆      SND_UP ┃  <- DATA
 *         ┃             ╰┄┄┄┄┄┄┄┄┄┄┰┄┄┄┄┄┄┄┰┄┄┄┄┄┄┄┄┄┄╯             ┃
 *         ┃                        ┃       ┃                        ┃
 *         ┗━━━━━━━━━━━━━━━━━━━━━━━━┛       ┗━━━━━━━━━━━━━━━━━━━━━━━━┛
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
class PipeInvocation<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> {
    private static final Logger                                         logger        = Logger.getLogger(PipeInvocation.class);
    public static final  String                                         RCV_ERROR_TAG = PipeChainRoot.class.getName() + "-rcv-error-tag";
    public static final  String                                         SND_ERROR_TAG = PipeChainRoot.class.getName() + "-snd-error-tag";
    private final        String                                         name;
    private final        PipeConfig                                     config;
    private final        AtomicBoolean                                  inited;
    //
    private              PipeQueue<RCV_DOWN>                            rcvDown;
    private              PipeQueue<SND_DOWN>                            sndDown;
    private final        PipeDuplex<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> pipeLayer;

    public PipeInvocation(String name, PipeConfig config, PipeDuplex<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> pipeLayer) {
        Objects.requireNonNull(config, "pipeConfig is null.");
        Objects.requireNonNull(pipeLayer, "pipeLayer is null.");

        this.name = name;
        this.config = config;
        this.inited = new AtomicBoolean();
        this.pipeLayer = pipeLayer;
    }

    /** return this {@link PipeDuplex} name. */
    public String getName() {
        return this.name;
    }

    /** the {@link PipeDuplex} RCV_DOWN to connect the next {@link PipeDuplex} RCV_UP. */
    public PipeQueue<RCV_DOWN> getRcvDown() {
        return this.rcvDown;
    }

    /** the {@link PipeDuplex} SND_DOWN to connect the next {@link PipeDuplex} SND_UP. */
    public PipeQueue<SND_DOWN> getSndDown() {
        return this.sndDown;
    }

    @Override
    public String toString() {
        return "PipeLayer [name=" + this.name + ", queue=" + this.rcvDown.queueSize() + ", slot=" + this.sndDown.slotSize() + "]";
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

    public void onInit(PipeContext pipeContext) throws Throwable {
        if (this.inited.compareAndSet(false, true)) {
            this.rcvDown = new PipeQueue<>(this.config.getPipeRcvDownStackSize());
            this.sndDown = new PipeQueue<>(this.config.getPipeSndUpStackSize());
            this.pipeLayer.onInit(pipeContext);
        }
    }

    public void onActive(PipeContext pipeContext) throws Throwable {
        this.pipeLayer.onActive(pipeContext);
    }

    public void onClose(PipeContext pipeContext) {
        if (this.inited.compareAndSet(true, false)) {
            this.pipeLayer.onClose(pipeContext);
        }
    }

    public PipeStatus doLayer(PipeContext context, boolean isRcv, PipeRcvQueue<RCV_UP> rcvUp, PipeRcvQueue<SND_UP> sndUp) throws Throwable {
        String errorTag = isRcv ? RCV_ERROR_TAG : SND_ERROR_TAG;
        Throwable ctxError = context.flash(errorTag);
        try {
            if (ctxError == null) {
                return this.pipeLayer.onMessage(context, isRcv, rcvUp, this.rcvDown, sndUp, this.sndDown);
            } else {
                return this.pipeLayer.onError(context, isRcv, ctxError, this.createExceptionHandler(isRcv, context, rcvUp, sndUp));
            }
        } catch (Throwable e) {
            if (ctxError == null) {
                String msgTag = isRcv ? "rcv" : "snd";
                long channelID = context.getChannel().getChannelID();
                if (context.getConfig().isNetlog()) {
                    logger.error(msgTag + "(" + channelID + ") " + this.pipeLayer.getClass() + " an error has occurred " + e.getClass().getName() + ": " + e.getMessage(), e);
                } else {
                    logger.error(msgTag + "(" + channelID + ") " + this.pipeLayer.getClass() + " an error has occurred " + e.getClass().getName() + ": " + e.getMessage());
                }

                context.flash(errorTag, e);
                return this.pipeLayer.onError(context, isRcv, e, this.createExceptionHandler(isRcv, context, rcvUp, sndUp));
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

    private PipeExceptionHolder createExceptionHandler(boolean isRcv, PipeContext pipeContext, PipeRcvQueue<RCV_UP> rcvUp, PipeRcvQueue<SND_UP> sndUp) {
        return new PipeExceptionHandlerImpl(isRcv, pipeContext);
    }

    private static class PipeExceptionHandlerImpl implements PipeExceptionHolder {
        private final String      errorTag;
        private final PipeContext context;

        public PipeExceptionHandlerImpl(boolean isRcv, PipeContext context) {
            this.errorTag = isRcv ? RCV_ERROR_TAG : SND_ERROR_TAG;
            this.context = context;
        }

        @Override
        public void clear() {
            this.context.flash(this.errorTag, null);
        }

    }
}