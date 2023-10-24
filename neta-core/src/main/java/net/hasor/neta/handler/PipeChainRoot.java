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
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.channel.PipeContextImpl;
import net.hasor.neta.channel.PipeStack;

import java.util.ArrayList;
import java.util.List;

/**
 * root Application stack
 * @version : 2023-10-20
 * @author 赵永春 (zyc@hasor.net)
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
    public void init(PipeContext pipeContext) throws Exception {
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
    public Object[] rcvLayer(PipeContext pipeContext, Object rcvData) throws Exception {
        ((PipeContextImpl) pipeContext).clearFlash();
        this.rootRcvUp.offerMessage(rcvData);
        this.rootRcvUp.sndSubmit();

        // doPipeline
        PipeStatus status = null;
        boolean triggerListener = false;
        do {
            for (int i = 0; i < this.layers.size(); i++) {
                status = this.doLayer(true, pipeContext, i);
                switch (status) {
                    case Next:
                    case Again:
                        triggerListener = (i == this.layers.size() - 1); // only the complete pipeline will fire listeners
                        continue;
                    case Exit:
                    case StartOver:
                        triggerListener = false;
                        break;
                }
            }
        } while (status == PipeStatus.StartOver);

        // triggerListener
        PipeQueue<?> rcvDown = this.layers.get(this.layers.size() - 1).getRcvDown();
        if (triggerListener && rcvDown.hasMore()) {
            if (this.listener == null) {
                // trigger tail. print event data to sto
                while (rcvDown.hasMore()) {
                    Object msg = rcvDown.takeMessage();
                    logger.warn("rcv(" + pipeContext.channel().getChannelID() + ") There are no program listeners, Skipping event : " + msg);
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

        // result
        PipeQueue<?> sndDown = this.layers.get(0).getSndDown();
        if (sndDown.hasMore()) {
            List<?> allBytes = sndDown.takeMessage(sndDown.queueSize());
            sndDown.rcvSubmit();
            return allBytes.toArray();
        } else {
            return EMPTY;
        }
    }

    @Override
    public Object[] sndLayer(PipeContext pipeContext, Object sndData) throws Exception {
        ((PipeContextImpl) pipeContext).clearFlash();
        if (!this.rootSndUp.offerMessage(sndData)) {
            long channelID = pipeContext.channel().getChannelID();
            String message = "snd(" + channelID + ") sndQueue[" + this.rootSndUp.slotSize() + "/" + this.rootSndUp.getCapacity() + "] is full.";
            IllegalStateException e = new IllegalStateException(message);
            logger.error(message, e);
            throw e;
        }
        this.rootSndUp.sndSubmit();

        // doPipeline
        PipeStatus status = null;
        do {
            for (int i = this.layers.size() - 1; i >= 0; i--) {
                status = this.doLayer(false, pipeContext, i);
                switch (status) {
                    case Next:
                    case Again:
                        continue;
                    case Exit:
                    case StartOver:
                        break;
                }
            }
        } while (status == PipeStatus.StartOver);

        // result
        PipeQueue<?> sndDown = this.layers.get(0).getSndDown();
        if (sndDown.hasMore()) {
            List<?> allBytes = sndDown.takeMessage(sndDown.queueSize());
            Object[] res = allBytes.toArray();
            sndDown.rcvSubmit();
            return res;
        } else {
            return EMPTY;
        }
    }

    private PipeStatus doLayer(boolean isRcv, PipeContext pipeContext, int i) throws Exception {
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
            Exception ctxError = pipeContext.flash(errorTag);

            try {
                if (ctxError == null) {
                    status = layer.doLayer(pipeContext, isRcv, useRcvUp, useSndUp);
                } else {
                    status = layer.doError(pipeContext, isRcv, useRcvUp, useSndUp, new PipeExceptionHandlerImpl(errorTag, pipeContext, ctxError));
                }
            } catch (Exception e) {
                String msg = isRcv ? "rcv" : "snd";
                msg += "(" + pipeContext.channel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " an error has occurred " + e.getMessage();
                logger.error(msg, e);

                ctxError = pipeContext.flash(errorTag, e);
                status = layer.doError(pipeContext, isRcv, useRcvUp, useSndUp, new PipeExceptionHandlerImpl(errorTag, pipeContext, ctxError));
            }

            if (status == null) {
                throw new IllegalStateException("return status missing.");
            }
            if (status == PipeStatus.Interrupt) {
                throw ctxError != null ? ctxError : new InterruptedException();
            }
        } while (status == PipeStatus.Again);

        return status;
    }

    private static class PipeExceptionHandlerImpl implements PipeExceptionHandler {
        private final String      errorTag;
        private final PipeContext context;
        private final Throwable   ctxError;

        public PipeExceptionHandlerImpl(String errorTag, PipeContext context, Throwable ctxError) {
            this.errorTag = errorTag;
            this.context = context;
            this.ctxError = ctxError;
        }

        @Override
        public void clear() {
            this.context.flash(this.errorTag, null);
        }

        @Override
        public Throwable getException() {
            return this.ctxError;
        }
    }
}