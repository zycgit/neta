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
 *
 * <p>
 *     When two PipeLayer are connected, the endpoint object is shared in the same direction. e.g., RCV_DOWN and RCV_UP.
 *
 *     For convenience, use the DOWN name
 * </p>
 *
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
 *
 * @version : 2023-10-20
 * @author 赵永春 (zyc@hasor.net)
 */
class PipeLayerInvocation<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> {
    private static final Logger                                        logger        = Logger.getLogger(PipeLayerInvocation.class);
    public static final  String                                        RCV_ERROR_TAG = PipeChainRoot.class.getName() + "-rcv-error-tag";
    public static final  String                                        SND_ERROR_TAG = PipeChainRoot.class.getName() + "-snd-error-tag";
    private final        String                                        name;
    private final        PipeConfig                                    config;
    private final        AtomicBoolean                                 inited;
    //
    private              PipeQueue<RCV_DOWN>                           rcvDownEnd;
    private              PipeQueue<SND_DOWN>                           sndDownEnd;
    private final        PipeLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> pipeLayer;

    public PipeLayerInvocation(String name, PipeConfig config, PipeLayer<RCV_UP, RCV_DOWN, SND_UP, SND_DOWN> pipeLayer) {
        Objects.requireNonNull(config, "pipeConfig is null.");
        Objects.requireNonNull(pipeLayer, "pipeLayer is null.");

        this.name = name;
        this.config = config;
        this.inited = new AtomicBoolean();
        this.pipeLayer = pipeLayer;
    }

    /** return this {@link PipeLayer} name. */
    public String getName() {
        return this.name;
    }

    /** the {@link PipeLayer} RCV_DOWN to connect the next {@link PipeLayer} RCV_UP. */
    public PipeQueue<RCV_DOWN> getRcvDown() {
        return this.rcvDownEnd;
    }

    /** the {@link PipeLayer} SND_DOWN to connect the next {@link PipeLayer} SND_UP. */
    public PipeQueue<SND_DOWN> getSndDown() {
        return this.sndDownEnd;
    }

    @Override
    public String toString() {
        return "PipeLayer [name=" + this.name + ", queue=" + this.rcvDownEnd.queueSize() + ", slot=" + this.sndDownEnd.slotSize() + "]";
    }

    public String toMonitorRcvString() {
        int capacity = this.rcvDownEnd.getCapacity();
        if (capacity > 500) {
            return this.rcvDownEnd.queueSize() + "/500+";
        } else {
            return this.rcvDownEnd.queueSize() + "/" + capacity;
        }
    }

    public String toMonitorSndString() {
        int capacity = this.sndDownEnd.getCapacity();
        if (capacity > 500) {
            return this.sndDownEnd.queueSize() + "/500+";
        } else {
            return this.sndDownEnd.queueSize() + "/" + capacity;
        }
    }

    public void initLayer(PipeContext pipeContext) throws Throwable {
        if (this.inited.compareAndSet(false, true)) {
            this.rcvDownEnd = new PipeQueue<>(this.config.getPipeRcvDownStackSize());
            this.sndDownEnd = new PipeQueue<>(this.config.getPipeSndUpStackSize());
            this.pipeLayer.init(pipeContext);
        }
    }

    public void releaseLayer(PipeContext pipeContext) {
        if (this.inited.compareAndSet(true, false)) {
            this.pipeLayer.release(pipeContext);
        }
    }

    public PipeStatus doLayer(PipeContext context, boolean isRcv, PipeRcvQueue<RCV_UP> rcvUp, PipeRcvQueue<SND_UP> sndUp) throws Throwable {
        String errorTag = isRcv ? RCV_ERROR_TAG : SND_ERROR_TAG;
        Throwable ctxError = context.flash(errorTag);
        try {
            if (ctxError == null) {
                return this.pipeLayer.doLayer(context, isRcv, rcvUp, this.rcvDownEnd, sndUp, this.sndDownEnd);
            } else {
                return this.pipeLayer.doError(context, isRcv, ctxError, this.createExceptionHandler(isRcv, context, rcvUp, sndUp));
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
                return this.pipeLayer.doError(context, isRcv, e, this.createExceptionHandler(isRcv, context, rcvUp, sndUp));
            } else {
                throw e;
            }
        } finally {
            rcvUp.rcvSubmit();
            this.rcvDownEnd.sndSubmit();
            sndUp.rcvSubmit();
            this.sndDownEnd.sndSubmit();
        }
    }

    private PipeExceptionHolder createExceptionHandler(boolean isRcv, PipeContext pipeContext, PipeRcvQueue<RCV_UP> rcvUp, PipeRcvQueue<SND_UP> sndUp) {
        return new PipeExceptionHandlerImpl(isRcv, pipeContext, rcvUp, this.rcvDownEnd, sndUp, this.sndDownEnd);
    }

    private static class PipeExceptionHandlerImpl implements PipeExceptionHolder {
        private final boolean         isRcv;
        private final String          errorTag;
        private final PipeContext     context;
        private final PipeRcvQueue<?> rcvUp;
        private final PipeSndQueue<?> rcvDown;
        private final PipeRcvQueue<?> sndUp;
        private final PipeSndQueue<?> sndDown;

        public PipeExceptionHandlerImpl(boolean isRcv, PipeContext context, //
                PipeRcvQueue<?> rcvUp, PipeSndQueue<?> rcvDown, PipeRcvQueue<?> sndUp, PipeSndQueue<?> sndDown) {
            this.isRcv = isRcv;
            this.errorTag = isRcv ? RCV_ERROR_TAG : SND_ERROR_TAG;
            this.context = context;
            this.rcvUp = rcvUp;
            this.rcvDown = rcvDown;
            this.sndUp = sndUp;
            this.sndDown = sndDown;
        }

        @Override
        public void clear() {
            this.context.flash(this.errorTag, null);
        }

        @Override
        public PipeRcvQueue<?> src() {
            return this.isRcv ? rcvUp : sndUp;
        }

        @Override
        public PipeSndQueue<?> dst() {
            return this.isRcv ? rcvDown : sndDown;
        }
    }
}