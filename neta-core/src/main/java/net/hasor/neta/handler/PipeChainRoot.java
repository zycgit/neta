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
import net.hasor.cobble.ArrayUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.channel.PipeContextImpl;
import net.hasor.neta.channel.PipeStack;

import java.util.ArrayList;
import java.util.List;

/**
 * root Application stack
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
@SuppressWarnings({ "unchecked" })
class PipeChainRoot implements PipeStack<Object, Object> {
    private static final Logger                                logger        = Logger.getLogger(PipeChainRoot.class);
    private static final String                                RCV_ERROR_TAG = PipeChainRoot.class.getName() + "-rcv-error-tag";
    private static final String                                SND_ERROR_TAG = PipeChainRoot.class.getName() + "-snd-error-tag";
    private static final ByteBuf[]                             EMPTY         = new ByteBuf[0];
    private final        List<PipeLayerInvocation<?, ?, ?, ?>> layers;
    private final        PipeQueue<Object>                     rootRcvUp     = new PipeQueue<>(-1);
    private final        PipeQueue<Object>                     rootSndUp     = new PipeQueue<>(-1);
    private              PipeReceiveListener<Object>           listener;

    public PipeChainRoot() {
        this.layers = new ArrayList<>();
    }

    public void addLayer(PipeLayerInvocation<?, ?, ?, ?> pipeLayer) {
        this.layers.add(pipeLayer);
    }

    public <NEXT_RCV_DOWN> void bindListener(PipeReceiveListener<NEXT_RCV_DOWN> listener) {
        this.listener = (PipeReceiveListener<Object>) listener;
    }

    public PipeReceiveListener<Object> getListener() {
        return this.listener;
    }

    @Override
    public void init(PipeContext pipeContext) throws Throwable {
        for (PipeLayerInvocation<?, ?, ?, ?> layer : this.layers) {
            layer.initLayer(pipeContext);
        }
    }

    @Override
    public void release(PipeContext pipeContext) {
        for (PipeLayerInvocation<?, ?, ?, ?> layer : this.layers) {
            layer.releaseLayer(pipeContext);
        }
    }

    @Override
    public Object[] rcvLayer(PipeContext pipeContext, Object rcvData) {
        try {
            if (rcvData != null) {
                this.rootRcvUp.offerMessage(rcvData);
                this.rootRcvUp.sndSubmit();
            }

            // doPipeline
            PipeStatus status;
            boolean needRestart = false;
            boolean triggerListener = false;
            do {
                for (int i = 0; i < this.layers.size(); i++) {
                    status = this.doLayer(true, pipeContext, i);
                    switch (status) {
                        case Next:
                        case Retry:
                            triggerListener = (i == this.layers.size() - 1); // only the complete pipeline will fire listeners
                            continue;
                        case Again:
                            needRestart = true;// restart when finished
                            continue;
                        case Exit:
                            needRestart = false;
                            triggerListener = false;
                            break;
                        case Restart:
                            needRestart = true;
                            break;
                    }
                }
            } while (needRestart);

            // triggerListener onReceive/onError
            PipeQueue<?> rcvDown = this.layers.get(this.layers.size() - 1).getRcvDown();

            // 1st onReceive
            if (triggerListener && rcvDown.hasMore()) {
                if (this.listener == null) {
                    // trigger tail. print event data to sto
                    while (rcvDown.hasMore()) {
                        Object msg = rcvDown.takeMessage();
                        logger.warn("rcv(" + pipeContext.channel().getChannelID() + ") There are no program listeners, Skipping event: " + msg);
                    }
                } else {
                    // trigger the listener event.
                    while (rcvDown.hasMore()) {
                        Object msg = rcvDown.takeMessage();
                        this.listener.onReceive(pipeContext.channel(), msg);
                    }
                }
                rcvDown.rcvSubmit();
            }

            // 2st onError
            Throwable ctxError = pipeContext.flash(RCV_ERROR_TAG);
            if (ctxError != null) {
                if (this.listener == null) {
                    logger.error("rcv(" + pipeContext.channel().getChannelID() + ") There are no program listeners, Skipping exception: " + ctxError.getMessage(), ctxError);
                } else {
                    this.listener.onError(pipeContext.channel(), ctxError);
                }
            }

            // result
            PipeQueue<?> sndDown = this.layers.get(0).getSndDown();
            if (sndDown.hasMore()) {
                Object[] sndList = sndDown.takeMessage(sndDown.queueSize());
                sndDown.rcvSubmit();
                return sndList;
            } else {
                return EMPTY;
            }
        } catch (Throwable e) {
            // triggerListener onError
            if (this.listener == null) {
                // trigger tail. print error data to sto
                logger.error("rcv(" + pipeContext.channel().getChannelID() + ") There are no program listeners, Skipping exception: " + e.getMessage(), e);
            } else {
                // trigger the listener event.
                this.listener.onError(pipeContext.channel(), e);
            }
            return ArrayUtils.EMPTY_OBJECT_ARRAY;
        } finally {
            ((PipeContextImpl) pipeContext).clearFlash();
        }
    }

    @Override
    public Object[] sndLayer(PipeContext pipeContext, Object sndData) throws Throwable {
        try {
            if (!this.rootSndUp.offerMessage(sndData)) {
                long channelID = pipeContext.channel().getChannelID();
                String message = "snd(" + channelID + ") sndQueue[" + this.rootSndUp.slotSize() + "/" + this.rootSndUp.getCapacity() + "] is full.";
                IllegalStateException e = new IllegalStateException(message);
                logger.error(message, e);
                throw e;
            }
            this.rootSndUp.sndSubmit();

            // doPipeline
            PipeStatus status;
            boolean needRestart = false;
            do {
                for (int i = this.layers.size() - 1; i >= 0; i--) {
                    status = this.doLayer(false, pipeContext, i);
                    switch (status) {
                        case Next:
                        case Retry:
                            continue;
                        case Again:
                            needRestart = true;// restart when finished
                            continue;
                        case Exit:
                            needRestart = false;
                            break;
                        case Restart:
                            needRestart = true;
                            break;
                    }
                }
            } while (needRestart);

            // result
            PipeQueue<?> sndDown = this.layers.get(0).getSndDown();
            if (sndDown.hasMore()) {
                Object[] sndList = sndDown.takeMessage(sndDown.queueSize());
                sndDown.rcvSubmit();
                return sndList;
            } else {
                return EMPTY;
            }
        } finally {
            ((PipeContextImpl) pipeContext).clearFlash();
        }
    }

    @Override
    public Object[] soError(PipeContext pipeContext, Throwable soError) {
        pipeContext.flash(RCV_ERROR_TAG, soError);
        return this.rcvLayer(pipeContext, null);
    }

    private PipeStatus doLayer(boolean isRcv, PipeContext pipeContext, int i) throws Throwable {
        //                 PipeLayer(0)                    PipeLayer (1)
        //          /------------------------\      /------------------------\
        //          |                        |      |                        |
        //          |             /----------+------+----------\             |
        //  DATA -> | RCV_UP      | RCV_DOWN    ->    RCV_UP   |    RCV_DOWN |  -> ...
        //          |             |                            |             |
        //  ...  <- | SND_DOWN    | SND_UP      <-    SND_DOWN |      SND_UP |  <- DATA
        //          |             \----------+------+----------/             |
        //          |                        |      |                        |
        //          \------------------------/      \------------------------/
        PipeQueue<?> useRcvUp = i == 0 ? this.rootRcvUp : this.layers.get(i - 1).getRcvDown();
        PipeQueue<?> useSndUp = i == (this.layers.size() - 1) ? this.rootSndUp : this.layers.get(i + 1).getSndDown();
        String errorTag = isRcv ? RCV_ERROR_TAG : SND_ERROR_TAG;

        PipeStatus status;
        do {
            PipeLayerInvocation layer = this.layers.get(i);
            Throwable ctxError = pipeContext.flash(errorTag);

            try {
                if (ctxError == null) {
                    status = layer.doLayer(pipeContext, isRcv, useRcvUp, useSndUp);
                } else {
                    status = layer.doError(pipeContext, isRcv, ctxError, layer.createExceptionHandler(errorTag, pipeContext, useRcvUp, useSndUp));
                }
            } catch (Throwable e) {
                String msg = isRcv ? "rcv" : "snd";
                msg += "(" + pipeContext.channel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " an error has occurred " + e.getMessage();
                logger.error(msg, e);

                ctxError = pipeContext.flash(errorTag, e);
                status = layer.doError(pipeContext, isRcv, ctxError, layer.createExceptionHandler(errorTag, pipeContext, useRcvUp, useSndUp));
            }

            if (status == null) {
                throw new IllegalStateException("return status missing.");
            }
            if (status == PipeStatus.Interrupt) {
                throw ctxError != null ? ctxError : new InterruptedException();
            }
        } while (status == PipeStatus.Retry);
        return status;
    }
}