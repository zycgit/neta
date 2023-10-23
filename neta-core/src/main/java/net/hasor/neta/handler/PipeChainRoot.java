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
class PipeChainRoot extends PipeStack {
    private static final Logger                                logger        = Logger.getLogger(PipeChainRoot.class);
    private static final String                                RCV_ERROR_TAG = PipeChainRoot.class.getName() + "-rcv-error-tag";
    private static final String                                SND_ERROR_TAG = PipeChainRoot.class.getName() + "-snd-error-tag";
    private static final ByteBuf[]                             EMPTY         = new ByteBuf[0];
    private final        List<PipeLayerInvocation<?, ?, ?, ?>> layers;
    private final        PipeQueue<Object>                     rootRcvUp     = new PipeQueue<>(-1);
    private final        PipeQueue<Object>                     rootSndUp     = new PipeQueue<>(-1);
    private final        List<PipeReceiveListener<?>>          listeners     = new ArrayList<>();

    public PipeChainRoot() {
        this.layers = new ArrayList<>();
    }

    public void addLayer(PipeLayerInvocation<?, ?, ?, ?> pipeLayer) {
        this.layers.add(pipeLayer);
    }

    public <NEXT_RCV_DOWN> void addListener(List<PipeReceiveListener<NEXT_RCV_DOWN>> listeners) {
        for (PipeReceiveListener<NEXT_RCV_DOWN> listener : listeners) {
            if (!listeners.contains(listener)) {
                this.listeners.add(listener);
            }
        }
    }

    public void initLayer(PipeContext pipeContext) throws Exception {
        for (PipeLayerInvocation<?, ?, ?, ?> layer : this.layers) {
            layer.initLayer(pipeContext);
        }
    }

    @Override
    protected ByteBuf[] rcvLayer(PipeContext pipeContext, ByteBuf rcvData) {
        ((PipeContextImpl) pipeContext).clearFlash();
        this.rootRcvUp.offerMessage(rcvData);
        this.rootRcvUp.sndSubmit();

        // doPipeline
        PipeStatus status = null;
        do {
            for (int i = 0; i < this.layers.size(); i++) {
                status = this.doLayer(true, pipeContext, i);
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

        // triggerListener
        PipeQueue<?> rcvDown = this.layers.get(this.layers.size() - 1).getRcvDown();
        if (rcvDown.hasMore()) {
            for (PipeReceiveListener listener : this.listeners) {
                listener.onReceive(pipeContext, rcvDown);
            }
        }

        // result
        PipeQueue<?> sndDown = this.layers.get(0).getSndDown();
        if (sndDown.hasMore()) {
            List<ByteBuf> allBytes = (List<ByteBuf>) sndDown.takeMessage(sndDown.queueSize());
            sndDown.rcvSubmit();
            return allBytes.toArray(new ByteBuf[0]);
        } else {
            return EMPTY;
        }
    }

    @Override
    protected ByteBuf[] sndLayer(PipeContext pipeContext, Object writeData) {
        ((PipeContextImpl) pipeContext).clearFlash();
        if (!this.rootSndUp.offerMessage(writeData)) {
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
            List<ByteBuf> allBytes = (List<ByteBuf>) sndDown.takeMessage(sndDown.queueSize());
            ByteBuf[] res = allBytes.toArray(new ByteBuf[0]);
            sndDown.rcvSubmit();
            return res;
        } else {
            return EMPTY;
        }
    }

    private PipeStatus doLayer(boolean isRcv, PipeContext pipeContext, int i) {
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
                    status = layer.doError(pipeContext, isRcv, useRcvUp, useSndUp, new PipeExceptionHandlerImpl(errorTag, pipeContext, ctxError));
                }
            } catch (Throwable e) {
                String msg = isRcv ? "rcv" : "snd";
                msg += "(" + pipeContext.channel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " an error has occurred " + e.getMessage();
                logger.error(msg, e);

                ctxError = pipeContext.flash(errorTag, e);
                status = layer.doError(pipeContext, isRcv, useRcvUp, useSndUp, new PipeExceptionHandlerImpl(errorTag, pipeContext, ctxError));
            }

            if (status == null) {
                throw new IllegalStateException("return status missing.");
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