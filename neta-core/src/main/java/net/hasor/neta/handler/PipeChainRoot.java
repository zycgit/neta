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
    public Object[] rcvLayer(PipeContext pipeContext, Object[] rcvData) throws Throwable {
        try {
            if (rcvData != null && rcvData.length > 0) {
                this.rootRcvUp.offerMessage(rcvData);
                this.rootRcvUp.sndSubmit();
            }

            RcvResult rcvResult = this.doRcvPipe(pipeContext);
            SndResult sndResult = this.doSndPipe(pipeContext, rcvResult.layerDepth);
            if (rcvResult.triggerListener) {
                this.triggerListener(pipeContext);
            }

            Object[] dat1 = rcvResult.rcvData;
            Object[] dat2 = sndResult.sndData;
            if (dat1 == EMPTY) {
                return dat2;
            } else if (dat2 == EMPTY) {
                return dat1;
            } else {
                Object[] result = new Object[dat1.length + dat2.length];
                System.arraycopy(dat1, 0, result, 0, dat1.length);
                System.arraycopy(dat2, 0, result, dat1.length, dat2.length);
                return result;
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
    public Object[] sndLayer(PipeContext pipeContext, Object[] sndData) throws Throwable {
        try {
            if (this.rootSndUp.offerMessage(sndData) != sndData.length) {
                long channelID = pipeContext.channel().getChannelID();
                String message = "snd(" + channelID + ") sndQueue[" + this.rootSndUp.slotSize() + "/" + this.rootSndUp.getCapacity() + "] is full.";
                IllegalStateException e = new IllegalStateException(message);
                logger.error(message, e);
                throw e;
            }
            this.rootSndUp.sndSubmit();

            SndResult sndResult = this.doSndPipe(pipeContext, this.layers.size() - 1);
            return sndResult.sndData;
        } finally {
            ((PipeContextImpl) pipeContext).clearFlash();
        }
    }

    @Override
    public Object[] soError(PipeContext pipeContext, Throwable soError) throws Throwable {
        try {
            pipeContext.flash(RCV_ERROR_TAG, soError);
            RcvResult result = this.doRcvPipe(pipeContext);

            if (result.triggerListener) {
                this.triggerListener(pipeContext);
            }

            return result.rcvData;
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

    private RcvResult doRcvPipe(PipeContext pipeContext) throws Throwable {
        boolean netLog = pipeContext.getConfig().isNetlog();
        boolean needRestartLater;
        boolean triggerListener;
        int i;

        do {
            needRestartLater = false;
            triggerListener = false;
            i = 0;

            while (i < this.layers.size()) {
                PipeStatus status = this.doLayer(true, pipeContext, i);
                switch (status) {
                    case Retry: // <-- can't happen, The Retry has been processed at doLayer
                    case Next:
                        // only the complete pipeline will fire listeners
                        triggerListener = (i == this.layers.size() - 1);
                        i++;
                        continue;
                    case Again:
                        needRestartLater = true;// restart when finished
                        if (netLog) {
                            logger.info("rcv(" + pipeContext.channel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " require Again");
                        }
                        i++;
                        continue;
                    case Restart:
                        needRestartLater = true;
                        if (netLog) {
                            logger.info("rcv(" + pipeContext.channel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " require Restart");
                        }
                        i++;
                        break;
                    case Exit:
                        triggerListener = false;
                        if (netLog) {
                            logger.info("rcv(" + pipeContext.channel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " require Exit");
                        }
                        i++;
                        break;
                }

                break;
            }
        } while (needRestartLater);

        // result
        PipeQueue<?> sndDown = this.layers.get(0).getSndDown();
        if (sndDown.hasMore()) {
            Object[] sndList = sndDown.takeMessage(sndDown.queueSize());
            sndDown.rcvSubmit();
            return new RcvResult(sndList, i - 1, triggerListener);
        } else {
            return new RcvResult(EMPTY, i - 1, triggerListener);
        }
    }

    private SndResult doSndPipe(PipeContext pipeContext, int depth) throws Throwable {
        boolean netLog = pipeContext.getConfig().isNetlog();
        boolean needRestartLater;
        do {
            needRestartLater = false;
            boolean breakFor = false;
            for (int i = depth; i >= 0; i--) {
                if (breakFor) {
                    break;
                }

                PipeStatus status = this.doLayer(false, pipeContext, i);
                switch (status) {
                    case Retry: // <-- can't happen, The Retry has been processed at doLayer
                    case Next:
                        break;
                    case Again:
                        needRestartLater = true;// restart when finished
                        if (netLog) {
                            logger.info("snd(" + pipeContext.channel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " require Again");
                        }
                        break;
                    case Restart:
                        needRestartLater = true;
                        breakFor = true;
                        if (netLog) {
                            logger.info("snd(" + pipeContext.channel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " require Restart");
                        }
                        break;
                    case Exit:
                        breakFor = true;
                        if (netLog) {
                            logger.info("snd(" + pipeContext.channel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " require Exit");
                        }
                        break;
                }
            }
        } while (needRestartLater);

        // result
        PipeQueue<?> sndDown = this.layers.get(0).getSndDown();
        if (sndDown.hasMore()) {
            Object[] sndList = sndDown.takeMessage(sndDown.queueSize());
            sndDown.rcvSubmit();
            return new SndResult(sndList);
        } else {
            return new SndResult(EMPTY);
        }
    }

    private void triggerListener(PipeContext pipeContext) {
        // find RCV_DOWN form last layer
        PipeQueue<?> rcvDown = this.layers.get(this.layers.size() - 1).getRcvDown();

        // 1st onReceive
        if (rcvDown.hasMore()) {
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
        boolean netLog = pipeContext.getConfig().isNetlog();
        PipeQueue<?> useRcvUp = i == 0 ? this.rootRcvUp : this.layers.get(i - 1).getRcvDown();
        PipeQueue<?> useSndUp = i == (this.layers.size() - 1) ? this.rootSndUp : this.layers.get(i + 1).getSndDown();
        String errorTag = isRcv ? RCV_ERROR_TAG : SND_ERROR_TAG;
        String msgTag = isRcv ? "rcv" : "snd";

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
                msgTag = msgTag + "(" + pipeContext.channel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " an error has occurred " + e.getMessage();
                logger.error(msgTag, e);

                ctxError = pipeContext.flash(errorTag, e);
                status = layer.doError(pipeContext, isRcv, ctxError, layer.createExceptionHandler(errorTag, pipeContext, useRcvUp, useSndUp));
            }

            if (status == null) {
                throw new IllegalStateException("return status missing.");
            }
            if (status == PipeStatus.Interrupt) {
                throw ctxError != null ? ctxError : new InterruptedException();
            }
            if (status == PipeStatus.Retry && netLog) {
                logger.info(msgTag + "(" + pipeContext.channel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " doRetry");
            }
        } while (status == PipeStatus.Retry);
        return status;
    }

    private static final class RcvResult {
        public final Object[] rcvData;
        public final int      layerDepth;
        public final boolean  triggerListener;

        public RcvResult(Object[] rcvData, int layerDepth, boolean triggerListener) {
            this.rcvData = rcvData;
            this.layerDepth = layerDepth;
            this.triggerListener = triggerListener;
        }
    }

    private static final class SndResult {
        public final Object[] sndData;

        public SndResult(Object[] sndData) {
            this.sndData = sndData;
        }
    }

}