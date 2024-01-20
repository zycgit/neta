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
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.channel.PipeContextImpl;
import net.hasor.neta.channel.Pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * root Application stack
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
@SuppressWarnings({ "unchecked" })
class PipeChainRoot implements Pipeline<Object>, PipeStatistical {
    private static final Logger                                logger = Logger.getLogger(PipeChainRoot.class);
    private static final ByteBuf[]                             EMPTY  = new ByteBuf[0];
    private final        List<PipeLayerInvocation<?, ?, ?, ?>> layers;
    private final        PipeQueue<Object>                     rootRcvUp;
    private final        PipeQueue<Object>                     rootSndUp;
    private              PipeListener<Object>                  listener;

    public PipeChainRoot(PipeConfig rootConfig) {
        int rcvSize = rootConfig.getPipeRcvDownStackSize();
        int sndSize = rootConfig.getPipeSndUpStackSize();

        this.layers = new ArrayList<>();
        this.rootRcvUp = rcvSize < 0 ? new PipeQueue<>(-1) : new PipeQueue<>(rcvSize);
        this.rootSndUp = sndSize < 0 ? new PipeQueue<>(-1) : new PipeQueue<>(sndSize);
    }

    public void addLayer(PipeLayerInvocation<?, ?, ?, ?> pipeLayer) {
        this.layers.add(pipeLayer);
    }

    public <NEXT_RCV_DOWN> void bindListener(PipeListener<NEXT_RCV_DOWN> listener) {
        this.listener = (PipeListener<Object>) listener;
    }

    public PipeListener<Object> getListener() {
        return this.listener;
    }

    @Override
    public void init(PipeContext pipeContext) throws Throwable {
        for (PipeLayerInvocation<?, ?, ?, ?> layer : this.layers) {
            layer.initLayer(pipeContext);
        }
    }

    @Override
    public boolean rcvSlotIsFull() {
        if (this.layers.isEmpty()) {
            return true;
        } else {
            return this.rootRcvUp.slotSize() > 0;
        }
    }

    @Override
    public boolean sndSlotIsFull() {
        if (this.layers.isEmpty()) {
            return true;
        } else {
            return this.layers.get(this.findDepth(false, null)).getSndDown().slotSize() > 0;
        }
    }

    @Override
    public void release(PipeContext pipeContext) {
        for (PipeLayerInvocation<?, ?, ?, ?> layer : this.layers) {
            layer.releaseLayer(pipeContext);
        }
    }

    private void offerMessage(boolean isRcv, PipeContext pipeContext, PipeQueue<Object> queue, Object[] offerData) throws IllegalStateException {
        if (queue.offerMessage(offerData) == offerData.length) {
            queue.sndSubmit();
        } else {
            queue.sndReset();

            String msgTag = isRcv ? "rcv" : "snd";
            long channelID = pipeContext.getChannel().getChannelID();
            int slotSize = queue.slotSize();
            int require = offerData.length;

            String msg = String.format("%s(%s) %sQueue is full, available slot is %s, require %s.", msgTag, channelID, msgTag, slotSize, require);
            IllegalStateException e = new IllegalStateException(msg);
            logger.error(msg, e);
            throw e;
        }
    }

    private int findDepth(boolean isRcv, String pipeName) {
        if (StringUtils.isNotBlank(pipeName)) {
            for (int i = 0; i < this.layers.size(); i++) {
                PipeLayerInvocation pipeLayer = this.layers.get(i);
                if (StringUtils.equals(pipeLayer.getName(), pipeName)) {
                    return i;
                }
            }
        }
        return isRcv ? 0 : (this.layers.size() - 1);
    }

    private PipeStatus doLayer(boolean isRcv, PipeContext pipeContext, int i) throws Throwable {
        //                 PipeLayer(0)                    PipeLayer (1)
        //          ┏━━━━━━━━━━━━━━━━━━━━━━━━┓       ┏━━━━━━━━━━━━━━━━━━━━━━━━┓
        //          ┃                        ┃       ┃                        ┃
        //          ┃             ╭┄┄┄┄┄┄┄┄┄┄┸┄┄┄┄┄┄┄┸┄┄┄┄┄┄┄┄┄┄╮             ┃
        //  DATA -> ┃ RCV_UP      ┆ RCV_DOWN    <=>    RCV_UP   ┆    RCV_DOWN ┃  -> ...
        //          ┃             ┆                             ┆             ┃
        //  ...  <- ┃ SND_DOWN    ┆ SND_UP      <=>    SND_DOWN ┆      SND_UP ┃  <- DATA
        //          ┃             ╰┄┄┄┄┄┄┄┄┄┄┰┄┄┄┄┄┄┄┰┄┄┄┄┄┄┄┄┄┄╯             ┃
        //          ┃                        ┃       ┃                        ┃
        //          ┗━━━━━━━━━━━━━━━━━━━━━━━━┛       ┗━━━━━━━━━━━━━━━━━━━━━━━━┛
        boolean netLog = pipeContext.getConfig().isNetlog();
        PipeQueue<?> useRcvUp = i == 0 ? this.rootRcvUp : this.layers.get(i - 1).getRcvDown();
        PipeQueue<?> useSndUp = i == (this.layers.size() - 1) ? this.rootSndUp : this.layers.get(i + 1).getSndDown();

        PipeStatus status;
        do {
            PipeLayerInvocation layer = this.layers.get(i);

            try {
                pipeContext.flash(PipeContext.CURRENT_PIPE_STACK_NAME, layer.getName());
                pipeContext.flash(PipeContext.CURRENT_PIPE_STACK_DEPTH, i);
                status = layer.doLayer(pipeContext, isRcv, useRcvUp, useSndUp);
            } finally {
                pipeContext.flash(PipeContext.CURRENT_PIPE_STACK_NAME, null);
                pipeContext.flash(PipeContext.CURRENT_PIPE_STACK_DEPTH, null);
            }

            if (status == null) {
                throw new IllegalStateException("return status missing.");
            }

            if (status == PipeStatus.Interrupt) {
                String errorTag = isRcv ? PipeLayerInvocation.RCV_ERROR_TAG : PipeLayerInvocation.SND_ERROR_TAG;
                Throwable ctxError = pipeContext.flash(errorTag);
                throw ctxError != null ? ctxError : new InterruptedException("Interrupted by " + layer.getName());
            }

            if (status == PipeStatus.Retry && netLog) {
                String msgTag = isRcv ? "rcv" : "snd";
                logger.info(msgTag + "(" + pipeContext.getChannel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " doRetry");
            }
        } while (status == PipeStatus.Retry);
        return status;
    }

    // ------------------------------------------------------------
    // RCV
    // ------------------------------------------------------------

    @Override
    public synchronized Object[] rcvLayer(PipeContext pipeContext, String pipeName, Object[] rcvData) throws Throwable {
        try {
            if (this.layers.isEmpty()) {
                return this.triggerReceiveByEmptyLayers(pipeContext, rcvData);
            }

            int depth = this.findDepth(true, pipeName);
            PipeQueue useRcvUp = depth == 0 ? this.rootRcvUp : this.layers.get(depth - 1).getRcvDown();
            this.offerMessage(true, pipeContext, useRcvUp, rcvData);
            return this.doRcvLife(pipeContext, depth);
        } finally {
            ((PipeContextImpl) pipeContext).clearFlash();
        }
    }

    @Override
    public synchronized Object[] rcvError(PipeContext pipeContext, String pipeName, Throwable rcvError) throws Throwable {
        try {
            pipeContext.flash(PipeLayerInvocation.RCV_ERROR_TAG, rcvError);
            if (this.layers.isEmpty()) {
                return this.triggerReceiveByEmptyLayers(pipeContext, EMPTY);
            }

            int depth = this.findDepth(true, pipeName);
            return this.doRcvLife(pipeContext, depth);
        } finally {
            ((PipeContextImpl) pipeContext).clearFlash();
        }
    }

    private Object[] doRcvLife(PipeContext pipeContext, int depth) throws Throwable {
        // do RCV
        PipeResult rcvResult = this.doRcvPipe(pipeContext, depth);
        if (rcvResult.pipeFinish) {
            this.triggerReceive(pipeContext);
        }
        PipeResult sndResult = this.doSndPipe(pipeContext, rcvResult.layerDepth);
        if (sndResult.pipeFinish) {
            this.triggerSend(pipeContext);
        }

        // return data(Will be written to SND)
        Object[] dat1 = rcvResult.result;
        Object[] dat2 = sndResult.result;
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
    }

    private void triggerReceive(PipeContext pipeContext) {
        // find RCV_DOWN form last layer
        PipeQueue<?> rcvDown = this.layers.get(this.layers.size() - 1).getRcvDown();

        // 1st onReceive
        if (rcvDown.hasMore()) {
            if (this.listener == null) {
                // trigger tail. print event data to sto
                while (rcvDown.hasMore()) {
                    Object msg = rcvDown.takeMessage();
                    logger.warn("rcv(" + pipeContext.getChannel().getChannelID() + ") There are no program listeners, Skipping event: " + msg);
                }
            } else {
                // trigger the listener event.
                while (rcvDown.hasMore()) {
                    Object msg = rcvDown.takeMessage();
                    this.listener.onReceive(pipeContext.getChannel(), msg);
                }
            }
            rcvDown.rcvSubmit();
        }

        // 2st onError
        Throwable ctxError = pipeContext.flash(PipeLayerInvocation.RCV_ERROR_TAG);
        if (ctxError != null) {
            if (this.listener == null) {
                logger.error("rcv(" + pipeContext.getChannel().getChannelID() + ") There are no program listeners, Skipping exception: " + ctxError.getMessage(), ctxError);
            } else {
                this.listener.onError(pipeContext.getChannel(), ctxError, true);
            }
        }
    }

    private Object[] triggerReceiveByEmptyLayers(PipeContext pipeContext, Object[] sndData) {
        // 1st onReceive
        if (this.listener == null) {
            // trigger tail. print event data to sto
            logger.warn("rcv(" + pipeContext.getChannel().getChannelID() + ") There are no program listeners, Skipping eventSize: " + sndData.length);
        } else {
            // trigger the listener event.
            if (sndData != null) {
                for (Object obj : sndData) {
                    this.listener.onReceive(pipeContext.getChannel(), obj);
                }
            }
        }

        // 2st onError
        Throwable ctxError = pipeContext.flash(PipeLayerInvocation.RCV_ERROR_TAG);
        if (ctxError != null) {
            if (this.listener == null) {
                logger.error("rcv(" + pipeContext.getChannel().getChannelID() + ") There are no program listeners, Skipping exception: " + ctxError.getMessage(), ctxError);
            } else {
                this.listener.onError(pipeContext.getChannel(), ctxError, true);
            }
        }

        return EMPTY;
    }

    private PipeResult doRcvPipe(final PipeContext pipeContext, int depth) throws Throwable {
        boolean netLog = pipeContext.getConfig().isNetlog();
        boolean needRestartLater;
        boolean pipeFinish;
        int i;

        do {
            needRestartLater = false;
            pipeFinish = false;
            i = depth;

            while (i < this.layers.size()) {
                PipeStatus status = this.doLayer(true, pipeContext, i);
                switch (status) {
                    case Retry: // <-- can't happen, The Retry has been processed at doLayer
                    case Next:
                        // only the complete pipeline will fire listeners
                        pipeFinish = (i == this.layers.size() - 1);
                        i++;
                        continue;
                    case Again:
                        needRestartLater = true;// restart when finished
                        if (netLog) {
                            logger.info("rcv(" + pipeContext.getChannel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " require Again");
                        }
                        i++;
                        continue;
                    case Restart:
                        needRestartLater = true;
                        if (netLog) {
                            logger.info("rcv(" + pipeContext.getChannel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " require Restart");
                        }
                        i++;
                        break;
                    case Exit:
                        pipeFinish = false;
                        if (netLog) {
                            logger.info("rcv(" + pipeContext.getChannel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " require Exit");
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
            List<?> sndList = sndDown.takeMessage(sndDown.queueSize());
            sndDown.rcvSubmit();
            return new PipeResult(sndList.toArray(), i - 1, pipeFinish);
        } else {
            return new PipeResult(EMPTY, i - 1, pipeFinish);
        }
    }

    // ------------------------------------------------------------
    // SND
    // ------------------------------------------------------------

    @Override
    public synchronized Object[] sndLayer(PipeContext pipeContext, String pipeName, Object[] sndData) throws Throwable {
        try {
            if (this.layers.isEmpty()) {
                return this.triggerSendByEmptyLayers(pipeContext, sndData);
            }

            int depth = this.findDepth(false, pipeName);
            PipeQueue useSndUp = depth == (this.layers.size() - 1) ? this.rootSndUp : this.layers.get(depth + 1).getSndDown();
            this.offerMessage(false, pipeContext, useSndUp, sndData);
            return this.doSndLife(pipeContext, depth);
        } finally {
            ((PipeContextImpl) pipeContext).clearFlash();
        }
    }

    @Override
    public synchronized Object[] sndError(PipeContext pipeContext, String pipeName, Throwable sndError) throws Throwable {
        try {
            pipeContext.flash(PipeLayerInvocation.SND_ERROR_TAG, sndError);

            if (this.layers.isEmpty()) {
                return this.triggerSendByEmptyLayers(pipeContext, EMPTY);
            }

            int depth = this.findDepth(false, pipeName);
            return this.doSndLife(pipeContext, depth);
        } finally {
            ((PipeContextImpl) pipeContext).clearFlash();
        }
    }

    private Object[] doSndLife(PipeContext pipeContext, int depth) throws Throwable {
        PipeResult sndResult = this.doSndPipe(pipeContext, depth);
        if (sndResult.pipeFinish) {
            this.triggerSend(pipeContext);
        }
        return sndResult.result;
    }

    private void triggerSend(PipeContext pipeContext) {
        // 2st onError
        Throwable ctxError = pipeContext.flash(PipeLayerInvocation.SND_ERROR_TAG);
        if (ctxError != null) {
            if (this.listener == null) {
                logger.error("rcv(" + pipeContext.getChannel().getChannelID() + ") There are no program listeners, Skipping exception: " + ctxError.getMessage(), ctxError);
            } else {
                this.listener.onError(pipeContext.getChannel(), ctxError, false);
            }
        } else {
            if (this.listener == null) {
                logger.warn("rcv(" + pipeContext.getChannel().getChannelID() + ") There are no program listeners, Skipping event send.");
            } else {
                //this.listener.onSend(pipeContext.channel());
            }

        }
    }

    private Object[] triggerSendByEmptyLayers(PipeContext pipeContext, Object[] sndData) {
        this.triggerSend(pipeContext);
        return sndData;
    }

    private PipeResult doSndPipe(PipeContext pipeContext, int depth) throws Throwable {
        boolean netLog = pipeContext.getConfig().isNetlog();
        boolean needRestartLater;
        int i;

        do {
            needRestartLater = false;
            boolean breakFor = false;
            i = depth;

            for (; i >= 0; i--) {
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
                            logger.info("snd(" + pipeContext.getChannel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " require Again");
                        }
                        break;
                    case Restart:
                        needRestartLater = true;
                        breakFor = true;
                        if (netLog) {
                            logger.info("snd(" + pipeContext.getChannel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " require Restart");
                        }
                        break;
                    case Exit:
                        breakFor = true;
                        if (netLog) {
                            logger.info("snd(" + pipeContext.getChannel().getChannelID() + ") PipeLayer " + i + "/" + this.layers.size() + " require Exit");
                        }
                        break;
                }
            }
        } while (needRestartLater);

        // result
        PipeQueue<?> sndDown = this.layers.get(0).getSndDown();
        if (sndDown.hasMore()) {
            List<?> sndList = sndDown.takeMessage(sndDown.queueSize());
            sndDown.rcvSubmit();
            return new PipeResult(sndList.toArray(), i, i == -1);
        } else {
            return new PipeResult(EMPTY, i, i == -1);
        }
    }

    // ------------------------------------------------------------
    // PipeStatistical
    // ------------------------------------------------------------

    @Override
    public int heapUpOfRcv() {
        int heapUpOfRcv = this.rootRcvUp.queueSize();
        for (PipeLayerInvocation<?, ?, ?, ?> layer : this.layers) {
            heapUpOfRcv += layer.getRcvDown().queueSize();
        }
        return heapUpOfRcv;
    }

    @Override
    public int heapUpOfRcv(String layerName) {
        for (PipeLayerInvocation<?, ?, ?, ?> layer : this.layers) {
            if (StringUtils.equals(layerName, layer.getName())) {
                return layer.getRcvDown().queueSize();
            }
        }
        return -1;
    }

    @Override
    public int heapUpOfRcvRoot() {
        return this.rootRcvUp.queueSize();
    }

    @Override
    public int heapUpOfSnd() {
        int heapUpOfSnd = this.rootSndUp.queueSize();
        for (PipeLayerInvocation<?, ?, ?, ?> layer : this.layers) {
            heapUpOfSnd += layer.getSndDown().queueSize();
        }
        return heapUpOfSnd;
    }

    @Override
    public int heapUpOfSnd(String layerName) {
        for (PipeLayerInvocation<?, ?, ?, ?> layer : this.layers) {
            if (StringUtils.equals(layerName, layer.getName())) {
                return layer.getSndDown().queueSize();
            }
        }
        return -1;
    }

    @Override
    public int heapUpOfSndRoot() {
        return this.rootSndUp.queueSize();
    }

    private static final class PipeResult {
        public final Object[] result;
        public final int      layerDepth;
        public final boolean  pipeFinish;

        public PipeResult(Object[] result, int layerDepth, boolean pipeFinish) {
            this.result = result;
            this.layerDepth = layerDepth;
            this.pipeFinish = pipeFinish;
        }
    }

    @Override
    public String toString() {
        List<String> layerNames = new ArrayList<>();
        List<String> monitorRcv = new ArrayList<>();
        List<String> monitorSnd = new ArrayList<>();
        String rootRcv = rootMonitorRcvString() + " (RCV)";
        String rootSnd = rootMonitorSndString() + " (SND)";
        int layerSize = this.layers.size();

        // nameLength
        int maxNameLength = 0;
        for (int i = layerSize - 1; i >= 0; i--) {
            PipeLayerInvocation<?, ?, ?, ?> layer = this.layers.get(i);
            String layerName = layer.getName();
            layerName = StringUtils.isBlank(layerName) ? ("Layer@" + Integer.toHexString(layer.hashCode())) : layerName;
            layerNames.add(layerName);
            maxNameLength = Math.max(maxNameLength, layerName.length());

            monitorRcv.add(layer.toMonitorRcvString() + ",");
            monitorSnd.add(layer.toMonitorSndString());
        }

        // bodyLength
        int rcvMaxLength = rootRcv.length() + 1;
        int sndMaxLength = rootSnd.length();
        for (int i = 0; i < this.layers.size(); i++) {
            rcvMaxLength = Math.max(rcvMaxLength, monitorRcv.get(i).length());
            sndMaxLength = Math.max(sndMaxLength, monitorSnd.get(i).length());
        }

        // build string
        StringBuilder sb = new StringBuilder();
        String nameBorder = StringUtils.repeat("━", maxNameLength);
        String rcvBorder = StringUtils.repeat("━", rcvMaxLength);
        String sndBorder = StringUtils.repeat("━", sndMaxLength);

        sb.append(String.format("┏━%s━━━━%s ↓ %s ━┓\n", nameBorder, rcvBorder, rootSnd));
        for (int i = 0; i < layerSize; i++) {
            String layerName = StringUtils.rightPad(layerNames.get(i), maxNameLength, " ");
            String rcvPart = StringUtils.rightPad(monitorRcv.get(i), rcvMaxLength, " ");
            String sndPart = StringUtils.rightPad(monitorSnd.get(i), sndMaxLength, " ");
            sb.append(String.format("┃ %s [↑ %s ↓ %s] ┃\n", layerName, rcvPart, sndPart));
        }
        sb.append(String.format("┗━%s━ ↑ %s ━━━%s━━┛", nameBorder, rootRcv, sndBorder));

        return sb.toString();
    }

    private String rootMonitorRcvString() {
        int capacity = this.rootRcvUp.getCapacity();
        if (capacity > 500) {
            return this.rootRcvUp.queueSize() + "/500+";
        } else {
            return this.rootRcvUp.queueSize() + "/" + capacity;
        }
    }

    private String rootMonitorSndString() {
        int capacity = this.rootSndUp.getCapacity();
        if (capacity > 500) {
            return this.rootSndUp.queueSize() + "/500+";
        } else {
            return this.rootSndUp.queueSize() + "/" + capacity;
        }
    }
}