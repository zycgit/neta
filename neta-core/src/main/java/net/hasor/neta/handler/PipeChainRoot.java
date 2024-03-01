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
import java.util.LinkedList;
import java.util.List;

/**
 * root Application stack
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
@SuppressWarnings({ "unchecked" })
class PipeChainRoot implements Pipeline<Object>, PipeStatistical {
    private static final Logger                           logger = Logger.getLogger(PipeChainRoot.class);
    private static final ByteBuf[]                        EMPTY  = new ByteBuf[0];
    private final        List<PipeInvocation<?, ?, ?, ?>> layers;
    private final        PipeQueue<Object>                headRcvUp;
    private final        PipeQueue<Object>                headSndUp;
    private              PipeListener                     listener;
    private              long                             channelID;
    private              boolean                          netLog;

    public PipeChainRoot(PipeConfig rootConfig) {
        int rcvSize = rootConfig.getPipeRcvDownStackSize();
        int sndSize = rootConfig.getPipeSndUpStackSize();

        this.layers = new ArrayList<>();
        this.headRcvUp = new PipeQueue<>(rcvSize < 0 ? -1 : rcvSize);
        this.headSndUp = new PipeQueue<>(sndSize < 0 ? -1 : sndSize);
        this.netLog = false;
    }

    public void addLayer(PipeInvocation<?, ?, ?, ?> pipeLayer) {
        this.layers.add(pipeLayer);
    }

    public void bindListener(PipeListener listener) {
        this.listener = listener;
    }

    @Override
    public int getRcvSlotSize() {
        if (this.layers.isEmpty()) {
            return Integer.MAX_VALUE;
        } else {
            return this.headRcvUp.slotSize();
        }
    }

    @Override
    public int getSndSlotSize() {
        if (this.layers.isEmpty()) {
            return Integer.MAX_VALUE;
        } else {
            return this.layers.get(this.findDepth(false, null)).getSndDown().slotSize();
        }
    }

    @Override
    public void onInit(PipeContext pipeContext) throws Throwable {
        this.netLog = pipeContext.getConfig().isNetlog();
        this.channelID = pipeContext.getChannel().getChannelID();

        for (PipeInvocation<?, ?, ?, ?> layer : this.layers) {
            layer.onInit(pipeContext);
        }
    }

    @Override
    public void onActive(PipeContext pipeContext) throws Throwable {
        for (PipeInvocation<?, ?, ?, ?> layer : this.layers) {
            layer.onActive(pipeContext);
        }
    }

    @Override
    public void onClose(PipeContext pipeContext) {
        for (PipeInvocation<?, ?, ?, ?> layer : this.layers) {
            layer.onClose(pipeContext);
        }
    }

    private void offerMessage(boolean isRcv, PipeQueue<Object> queue, Object[] offerData) throws IllegalStateException {
        if (queue.offerMessage(offerData) == offerData.length) {
            queue.sndSubmit();
        } else {
            queue.sndReset();
            String msgTag = isRcv ? "rcv" : "snd";
            int slotSize = queue.slotSize();
            int require = offerData.length;

            String msg = String.format("%s(%s) %sQueue is full, available slot is %s, require %s.", msgTag, this.channelID, msgTag, slotSize, require);
            IllegalStateException e = new IllegalStateException(msg);
            logger.error(msg, e);
            throw e;
        }
    }

    private int findDepth(boolean isRcv, String pipeName) {
        if (StringUtils.isNotBlank(pipeName)) {
            for (int i = 0; i < this.layers.size(); i++) {
                PipeInvocation<?, ?, ?, ?> pipeLayer = this.layers.get(i);
                if (StringUtils.equals(pipeLayer.getName(), pipeName)) {
                    return i;
                }
            }
        }
        return isRcv ? 0 : (this.layers.size() - 1);
    }

    private void printLog(boolean isRcv, String msg) {
        if (!this.netLog) {
            return;
        }

        if (isRcv) {
            logger.info("rcv(" + this.channelID + ") " + msg);
        } else {
            logger.info("snd(" + this.channelID + ") " + msg);
        }
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
        PipeQueue<?> useRcvUp = i == 0 ? this.headRcvUp : this.layers.get(i - 1).getRcvDown();
        PipeQueue<?> useSndUp = i == (this.layers.size() - 1) ? this.headSndUp : this.layers.get(i + 1).getSndDown();

        PipeStatus status;
        do {
            PipeInvocation layer = this.layers.get(i);

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
                String errorTag = isRcv ? PipeInvocation.RCV_ERROR_TAG : PipeInvocation.SND_ERROR_TAG;
                Throwable ctxError = pipeContext.flash(errorTag);
                throw ctxError != null ? ctxError : new InterruptedException("Interrupted by " + layer.getName());
            }

            if (status == PipeStatus.Retry && netLog) {
                String msgTag = isRcv ? "rcv" : "snd";
                logger.info(msgTag + "(" + this.channelID + ") PipeLayer " + i + "/" + this.layers.size() + " doRetry");
            }
        } while (status == PipeStatus.Retry);
        return status;
    }

    // ------------------------------------------------------------
    // RCV
    // ------------------------------------------------------------

    @Override
    public synchronized Object[] onRcvMessage(PipeContext pipeContext, String pipeName, Object[] rcvData) throws Throwable {
        try {
            if (this.layers.isEmpty()) {
                return this.triggerRcvWithEmptyPipe(pipeContext, rcvData);
            }

            int depth = this.findDepth(true, pipeName);
            PipeQueue useRcvUp = depth == 0 ? this.headRcvUp : this.layers.get(depth - 1).getRcvDown();
            this.offerMessage(true, useRcvUp, rcvData);
            return this.onRcvLife(pipeContext, depth);
        } finally {
            ((PipeContextImpl) pipeContext).clearFlash();
        }
    }

    @Override
    public synchronized Object[] onRcvError(PipeContext pipeContext, String pipeName, Throwable rcvError) throws Throwable {
        try {
            pipeContext.flash(PipeInvocation.RCV_ERROR_TAG, rcvError);
            if (this.layers.isEmpty()) {
                return this.triggerRcvWithEmptyPipe(pipeContext, EMPTY);
            }

            int depth = this.findDepth(true, pipeName);
            return this.onRcvLife(pipeContext, depth);
        } finally {
            ((PipeContextImpl) pipeContext).clearFlash();
        }
    }

    private Object[] onRcvLife(PipeContext pipeContext, int depth) throws Throwable {
        LinkedList<Object[]> returnData = new LinkedList<>();
        int arraySize = 0;

        // do RCV, process PipeStatus.Back
        int usingRcvDepth = depth;
        PipeResult rcvResult;
        do {
            rcvResult = this.doRcvPipe(pipeContext, usingRcvDepth);
            if (rcvResult.finish) {
                this.triggerRcv(pipeContext);
            }

            if (rcvResult.result != EMPTY && rcvResult.result.length > 0) {
                returnData.add(rcvResult.result);
                arraySize = arraySize + rcvResult.result.length;
            }

            if (rcvResult.backTo == -1) {
                break;
            }

            usingRcvDepth = rcvResult.backTo;
        } while (true);

        //
        // do SND, process PipeStatus.Back
        int usingSndDepth = rcvResult.layerDepth;
        PipeResult sndResult;
        do {
            sndResult = this.doSndPipe(pipeContext, usingSndDepth);
            if (sndResult.finish) {
                this.triggerSend(pipeContext);
            }

            if (sndResult.result != EMPTY && sndResult.result.length > 0) {
                returnData.add(sndResult.result);
                arraySize = arraySize + sndResult.result.length;
            }

            if (sndResult.backTo == -1) {
                break;
            }

            usingSndDepth = sndResult.backTo;
        } while (true);

        // return data(Will be written to SND)
        if (arraySize == 0) {
            return EMPTY;
        } else {
            Object[] result = new Object[arraySize];
            int dstPos = 0;
            for (Object[] objs : returnData) {
                System.arraycopy(objs, 0, result, dstPos, objs.length);
                dstPos = dstPos + objs.length;
            }
            return result;
        }
    }

    private void triggerRcv(PipeContext pipeContext) {
        // 1st onReceive
        PipeQueue<?> rcvDown = this.layers.get(this.layers.size() - 1).getRcvDown();
        if (rcvDown.hasMore()) {
            if (this.listener == null) {
                // trigger tail. print event data to log
                while (rcvDown.hasMore()) {
                    Object data = rcvDown.takeMessage();
                    String msg = "rcv(" + this.channelID + ") There are no program at the tail of the pipeline, Skipping event: ";
                    logger.warn(msg + data);
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
        Throwable ctxError = pipeContext.flash(PipeInvocation.RCV_ERROR_TAG);
        if (ctxError != null) {
            if (this.listener == null) {
                String msg = "rcv(" + this.channelID + ") rcv Exception was fired, and it reached at the tail of the pipeline." //
                        + " It usually means the last handler in the pipeline did not handle the rcv exception.";
                logger.warn(msg, ctxError);
            } else {
                this.listener.onError(pipeContext.getChannel(), ctxError, true);
            }
        }
    }

    private Object[] triggerRcvWithEmptyPipe(PipeContext pipeContext, Object[] sndData) {
        // 1st onReceive
        if (sndData != null) {
            if (this.listener == null) {
                // trigger tail. print event data to log
                for (Object obj : sndData) {
                    String msg = "rcv(" + this.channelID + ") There are no program at the tail of the pipeline, Skipping event: ";
                    logger.warn(msg + obj);
                }
            } else {
                // trigger the listener event.
                for (Object obj : sndData) {
                    this.listener.onReceive(pipeContext.getChannel(), obj);
                }
            }
        }

        // 2st onError
        Throwable ctxError = pipeContext.flash(PipeInvocation.RCV_ERROR_TAG);
        if (ctxError != null) {
            if (this.listener == null) {
                String msg = "rcv(" + this.channelID + ") rcv Exception was fired, and it reached at the tail of the pipeline." //
                        + " It usually means the last handler in the pipeline did not handle the rcv exception.";
                logger.warn(msg, ctxError);
            } else {
                this.listener.onError(pipeContext.getChannel(), ctxError, true);
            }
        }
        return EMPTY;
    }

    private PipeResult doRcvPipe(final PipeContext pipeContext, int depth) throws Throwable {
        boolean needRestartLater;
        boolean pipeFinish;
        int i;
        int backTo = -1;

        do {
            needRestartLater = false;
            pipeFinish = false;
            i = depth;

            while (i < this.layers.size()) {
                String pipName = this.layers.get(i).getName();
                PipeStatus status = this.doLayer(true, pipeContext, i);
                switch (status) {
                    case Retry: // <-- can't happen, The Retry has been processed at doLayer
                    case Next:
                    case Skip:
                    case Back:
                        // only the complete pipeline will fire triggerRcv
                        pipeFinish = (i == this.layers.size() - 1);

                        if (status == PipeStatus.Back) {
                            if (backTo == -1) {
                                backTo = i;
                                this.printLog(true, "pipeline " + pipName + " request Back.");
                            } else {
                                String backToName = this.layers.get(backTo).getName();
                                this.printLog(true, "pipeline " + pipName + " request Back, has been set to " + backToName);
                            }
                        } else if (status == PipeStatus.Skip) {
                            i++; // if skip then skip next to next.
                            String skipName = (this.layers.size() >= i) ? this.layers.get(i).getName() : ", the last node to ignore Skip.";
                            this.printLog(true, "pipeline " + pipName + " request Skip " + skipName);
                        }

                        i++;
                        continue;
                    case Again:
                        needRestartLater = true;// restart when finished
                        this.printLog(true, "pipeline " + pipName + " require Again");
                        i++;
                        continue;
                    case Restart:
                        needRestartLater = true;
                        this.printLog(true, "pipeline " + pipName + " require Restart");
                        i++;
                        break;
                    case Exit:
                        pipeFinish = false;
                        this.printLog(true, "pipeline " + pipName + " require Exit");
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
            return new PipeResult(sndList.toArray(), backTo, i - 1, pipeFinish);
        } else {
            return new PipeResult(EMPTY, backTo, i - 1, pipeFinish);
        }
    }

    // ------------------------------------------------------------
    // SND
    // ------------------------------------------------------------

    @Override
    public synchronized Object[] onSndMessage(PipeContext pipeContext, String pipeName, Object[] sndData) throws Throwable {
        try {
            if (this.layers.isEmpty()) {
                return sndData;
            }

            int depth = this.findDepth(false, pipeName);
            PipeQueue useSndUp = depth == (this.layers.size() - 1) ? this.headSndUp : this.layers.get(depth + 1).getSndDown();
            this.offerMessage(false, useSndUp, sndData);
            return this.doSndLife(pipeContext, depth);
        } finally {
            ((PipeContextImpl) pipeContext).clearFlash();
        }
    }

    @Override
    public synchronized Object[] onSndError(PipeContext pipeContext, String pipeName, Throwable sndError) throws Throwable {
        try {
            pipeContext.flash(PipeInvocation.SND_ERROR_TAG, sndError);

            if (this.layers.isEmpty()) {
                this.triggerSend(pipeContext);
                return EMPTY;
            }

            int depth = this.findDepth(false, pipeName);
            return this.doSndLife(pipeContext, depth);
        } finally {
            ((PipeContextImpl) pipeContext).clearFlash();
        }
    }

    private Object[] doSndLife(PipeContext pipeContext, int depth) throws Throwable {
        LinkedList<Object[]> returnData = new LinkedList<>();
        int arraySize = 0;

        int usingSndDepth = depth;
        PipeResult sndResult;
        do {
            sndResult = this.doSndPipe(pipeContext, usingSndDepth);
            if (sndResult.finish) {
                this.triggerSend(pipeContext);
            }

            if (sndResult.result != EMPTY && sndResult.result.length > 0) {
                returnData.add(sndResult.result);
                arraySize = arraySize + sndResult.result.length;
            }

            if (sndResult.backTo == -1) {
                break;
            }

            usingSndDepth = sndResult.backTo;
        } while (true);

        // return data(Will be written to SND)
        if (arraySize == 0) {
            return EMPTY;
        } else {
            Object[] result = new Object[arraySize];
            int dstPos = 0;
            for (Object[] objs : returnData) {
                System.arraycopy(objs, 0, result, dstPos, objs.length);
                dstPos = dstPos + objs.length;
            }
            return result;
        }
    }

    private void triggerSend(PipeContext pipeContext) {
        Throwable ctxError = pipeContext.flash(PipeInvocation.SND_ERROR_TAG);
        if (ctxError != null) {
            if (this.listener == null) {
                String msg = "snd(" + this.channelID + ") snd Exception was fired, and it reached at the head of the pipeline." //
                        + " It usually means the first handler in the pipeline did not handle the snd exception.";
                logger.warn(msg, ctxError);
            } else {
                this.listener.onError(pipeContext.getChannel(), ctxError, false);
            }
        }
    }

    private PipeResult doSndPipe(PipeContext pipeContext, int depth) throws Throwable {
        boolean needRestartLater;
        int i;
        int backTo = -1;

        do {
            needRestartLater = false;
            boolean breakFor = false;
            i = depth;

            for (; i >= 0; i--) {
                if (breakFor) {
                    break;
                }

                String pipName = this.layers.get(i).getName();
                PipeStatus status = this.doLayer(false, pipeContext, i);
                switch (status) {
                    case Retry: // <-- can't happen, The Retry has been processed at doLayer
                    case Next:
                    case Skip:
                    case Back:
                        if (status == PipeStatus.Skip) {
                            this.printLog(false, "PipeLayer " + pipName + " require Skip");
                            i--; // if skip then skip next to next.
                        } else if (status == PipeStatus.Back) {
                            if (backTo == -1) {
                                backTo = i;
                                this.printLog(false, "PipeLayer " + pipName + " require Back to " + backTo);
                            } else {
                                this.printLog(false, "PipeLayer " + pipName + " Back has been set to " + backTo);
                            }
                        }
                        break;
                    case Again:
                        needRestartLater = true;// restart when finished
                        this.printLog(false, "PipeLayer " + pipName + " require Again");
                        break;
                    case Restart:
                        needRestartLater = true;
                        breakFor = true;
                        this.printLog(false, "PipeLayer " + pipName + " require Restart");
                        break;
                    case Exit:
                        breakFor = true;
                        this.printLog(false, "PipeLayer " + pipName + " require Exit");
                        break;
                }
            }
        } while (needRestartLater);

        // result
        PipeQueue<?> sndDown = this.layers.get(0).getSndDown();
        if (sndDown.hasMore()) {
            List<?> sndList = sndDown.takeMessage(sndDown.queueSize());
            sndDown.rcvSubmit();
            return new PipeResult(sndList.toArray(), backTo, i, i == -1);
        } else {
            return new PipeResult(EMPTY, backTo, i, i == -1);
        }
    }

    // ------------------------------------------------------------
    // PipeStatistical
    // ------------------------------------------------------------

    @Override
    public int heapUpOfRcv() {
        int heapUpOfRcv = this.headRcvUp.queueSize();
        for (PipeInvocation<?, ?, ?, ?> layer : this.layers) {
            heapUpOfRcv += layer.getRcvDown().queueSize();
        }
        return heapUpOfRcv;
    }

    @Override
    public int heapUpOfRcv(String layerName) {
        for (PipeInvocation<?, ?, ?, ?> layer : this.layers) {
            if (StringUtils.equals(layerName, layer.getName())) {
                return layer.getRcvDown().queueSize();
            }
        }
        return -1;
    }

    @Override
    public int heapUpOfRcvRoot() {
        return this.headRcvUp.queueSize();
    }

    @Override
    public int heapUpOfSnd() {
        int heapUpOfSnd = this.headSndUp.queueSize();
        for (PipeInvocation<?, ?, ?, ?> layer : this.layers) {
            heapUpOfSnd += layer.getSndDown().queueSize();
        }
        return heapUpOfSnd;
    }

    @Override
    public int heapUpOfSnd(String layerName) {
        for (PipeInvocation<?, ?, ?, ?> layer : this.layers) {
            if (StringUtils.equals(layerName, layer.getName())) {
                return layer.getSndDown().queueSize();
            }
        }
        return -1;
    }

    @Override
    public int heapUpOfSndRoot() {
        return this.headSndUp.queueSize();
    }

    private static final class PipeResult {
        public final Object[] result;
        public final int      backTo;
        public final int      layerDepth;
        public final boolean  finish;

        public PipeResult(Object[] result, int backTo, int layerDepth, boolean finish) {
            this.result = result;
            this.backTo = backTo;
            this.layerDepth = layerDepth;
            this.finish = finish;
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
            PipeInvocation<?, ?, ?, ?> layer = this.layers.get(i);
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
        int capacity = this.headRcvUp.getCapacity();
        if (capacity > 500) {
            return this.headRcvUp.queueSize() + "/500+";
        } else {
            return this.headRcvUp.queueSize() + "/" + capacity;
        }
    }

    private String rootMonitorSndString() {
        int capacity = this.headSndUp.getCapacity();
        if (capacity > 500) {
            return this.headSndUp.queueSize() + "/500+";
        } else {
            return this.headSndUp.queueSize() + "/" + capacity;
        }
    }
}