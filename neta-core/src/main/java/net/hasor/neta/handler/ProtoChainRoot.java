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
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoContextImpl;
import net.hasor.neta.channel.ProtoStack;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * root Application stack
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
@SuppressWarnings({ "unchecked" })
class ProtoChainRoot implements ProtoStack<Object>, ProtoStatistical {
    private static final Logger                            logger = Logger.getLogger(ProtoChainRoot.class);
    private static final ByteBuf[]                         EMPTY  = new ByteBuf[0];
    private final        List<ProtoInvocation<?, ?, ?, ?>> layers;
    private final        ProtoQueue<Object>                headRcvUp;
    private final        ProtoQueue<Object>                headSndUp;
    private              ProtoListener                     listener;
    private              long                              channelID;
    private              boolean                           netLog;

    public ProtoChainRoot(ProtoConfig rootConfig) {
        int rcvSize = rootConfig.getRcvDownSlotSize();
        int sndSize = rootConfig.getSndUpSlotSize();

        this.layers = new ArrayList<>();
        this.headRcvUp = new ProtoQueue<>(rcvSize < 0 ? -1 : rcvSize);
        this.headSndUp = new ProtoQueue<>(sndSize < 0 ? -1 : sndSize);
        this.netLog = false;
    }

    public void addProtoStack(ProtoInvocation<?, ?, ?, ?> invocation) {
        this.layers.add(invocation);
    }

    public void bindListener(ProtoListener listener) {
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
    public void onInit(ProtoContext protoCtx) throws Throwable {
        this.netLog = protoCtx.getConfig().isNetlog();
        this.channelID = protoCtx.getChannel().getChannelID();

        for (ProtoInvocation<?, ?, ?, ?> layer : this.layers) {
            layer.onInit(protoCtx);
        }
    }

    @Override
    public void onActive(ProtoContext protoCtx) throws Throwable {
        for (ProtoInvocation<?, ?, ?, ?> layer : this.layers) {
            layer.onActive(protoCtx);
        }
    }

    @Override
    public void onClose(ProtoContext protoCtx) {
        for (ProtoInvocation<?, ?, ?, ?> layer : this.layers) {
            layer.onClose(protoCtx);
        }
    }

    private void offerMessage(boolean isRcv, ProtoQueue<Object> queue, Object[] offerData) throws IllegalStateException {
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

    private int findDepth(boolean isRcv, String stackName) {
        if (StringUtils.isNotBlank(stackName)) {
            for (int i = 0; i < this.layers.size(); i++) {
                ProtoInvocation<?, ?, ?, ?> layer = this.layers.get(i);
                if (StringUtils.equals(layer.getName(), stackName)) {
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

    private ProtoStatus doLayer(boolean isRcv, ProtoContext protoCtx, int i) throws Throwable {
        //               Protocol Layer(0)               Protocol Layer(1)
        //          ┏━━━━━━━━━━━━━━━━━━━━━━━━┓       ┏━━━━━━━━━━━━━━━━━━━━━━━━┓
        //          ┃             ╭┄┄┄┄┄┄┄┄┄┄┸┄┄┄┄┄┄┄┸┄┄┄┄┄┄┄┄┄┄╮             ┃
        //  DATA -> ┃ RCV_UP      ┆ RCV_DOWN    <=>    RCV_UP   ┆    RCV_DOWN ┃  -> ...
        //          ┃             ┆                             ┆             ┃
        //  ...  <- ┃ SND_DOWN    ┆ SND_UP      <=>    SND_DOWN ┆      SND_UP ┃  <- DATA
        //          ┃             ╰┄┄┄┄┄┄┄┄┄┄┰┄┄┄┄┄┄┄┰┄┄┄┄┄┄┄┄┄┄╯             ┃
        //          ┗━━━━━━━━━━━━━━━━━━━━━━━━┛       ┗━━━━━━━━━━━━━━━━━━━━━━━━┛
        boolean netLog = protoCtx.getConfig().isNetlog();
        ProtoQueue<?> useRcvUp = i == 0 ? this.headRcvUp : this.layers.get(i - 1).getRcvDown();
        ProtoQueue<?> useSndUp = i == (this.layers.size() - 1) ? this.headSndUp : this.layers.get(i + 1).getSndDown();

        ProtoStatus status;
        do {
            ProtoInvocation layer = this.layers.get(i);

            try {
                protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_NAME, layer.getName());
                protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_DEPTH, i);
                status = layer.doLayer(protoCtx, isRcv, useRcvUp, useSndUp);
            } finally {
                protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_NAME, null);
                protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_DEPTH, null);
            }

            if (status == null) {
                throw new IllegalStateException("return status missing.");
            }

            if (status == ProtoStatus.Interrupt) {
                String errorTag = isRcv ? ProtoInvocation.RCV_ERROR_TAG : ProtoInvocation.SND_ERROR_TAG;
                Throwable ctxError = protoCtx.flash(errorTag);
                throw ctxError != null ? ctxError : new InterruptedException("Interrupted by " + layer.getName());
            }

            if (status == ProtoStatus.Retry && netLog) {
                String msgTag = isRcv ? "rcv" : "snd";
                logger.info(msgTag + "(" + this.channelID + ") Stack " + i + "/" + this.layers.size() + " doRetry");
            }
        } while (status == ProtoStatus.Retry);
        return status;
    }

    // ------------------------------------------------------------
    // RCV
    // ------------------------------------------------------------

    @Override
    public synchronized Object[] onRcvMessage(ProtoContext protoCtx, String stackName, Object[] rcvData) throws Throwable {
        try {
            protoCtx.flash(ProtoContext.CURRENT_PROTO_IN_RCV, true);
            protoCtx.flash(ProtoContext.CURRENT_PROTO_IN_SND, false);

            if (this.layers.isEmpty()) {
                return this.triggerRcvWithEmpty(protoCtx, rcvData);
            }

            int depth = this.findDepth(true, stackName);
            ProtoQueue useRcvUp = depth == 0 ? this.headRcvUp : this.layers.get(depth - 1).getRcvDown();
            this.offerMessage(true, useRcvUp, rcvData);
            return this.onRcvLife(protoCtx, depth);
        } finally {
            ((ProtoContextImpl) protoCtx).clearFlash();
        }
    }

    @Override
    public synchronized Object[] onRcvError(ProtoContext protoCtx, String stackName, Throwable rcvError) throws Throwable {
        try {
            protoCtx.flash(ProtoInvocation.RCV_ERROR_TAG, rcvError);
            protoCtx.flash(ProtoContext.CURRENT_PROTO_IN_RCV, true);
            protoCtx.flash(ProtoContext.CURRENT_PROTO_IN_SND, false);

            if (this.layers.isEmpty()) {
                return this.triggerRcvWithEmpty(protoCtx, EMPTY);
            }

            int depth = this.findDepth(true, stackName);
            return this.onRcvLife(protoCtx, depth);
        } finally {
            ((ProtoContextImpl) protoCtx).clearFlash();
        }
    }

    private Object[] onRcvLife(ProtoContext protoCtx, int depth) throws Throwable {
        LinkedList<Object[]> returnData = new LinkedList<>();
        int arraySize = 0;

        // do RCV, process ProtoResult.Back
        int usingRcvDepth = depth;
        ProtoResult rcvResult;
        do {
            rcvResult = this.doRcvStack(protoCtx, usingRcvDepth);
            if (rcvResult.finish) {
                this.triggerRcv(protoCtx);
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

        // do SND, process ProtoResult.Back
        int usingSndDepth = rcvResult.layerDepth;
        ProtoResult sndResult;
        do {
            sndResult = this.doSndStack(protoCtx, usingSndDepth);
            if (sndResult.finish) {
                this.triggerSend(protoCtx);
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

    private void triggerRcv(ProtoContext protoCtx) {
        // 1st onReceive
        ProtoQueue<?> rcvDown = this.layers.get(this.layers.size() - 1).getRcvDown();
        if (rcvDown.hasMore()) {
            if (this.listener == null) {
                // trigger tail. print event data to log
                while (rcvDown.hasMore()) {
                    Object data = rcvDown.takeMessage();
                    String msg = "rcv(" + this.channelID + ") There are no program at the tail of the ProtoStack, Skipping event: ";
                    logger.warn(msg + data);
                }
            } else {
                // trigger the listener event.
                while (rcvDown.hasMore()) {
                    Object msg = rcvDown.takeMessage();
                    this.listener.onReceive(protoCtx.getChannel(), msg);
                }
            }
            rcvDown.rcvSubmit();
        }

        // 2st onError
        Throwable ctxError = protoCtx.flash(ProtoInvocation.RCV_ERROR_TAG);
        if (ctxError != null) {
            if (this.listener == null) {
                String msg = "rcv(" + this.channelID + ") rcv Exception was fired, and it reached at the tail of the ProtoStack." //
                        + " It usually means the last handler in the ProtoStack did not handle the rcv exception.";
                logger.warn(msg, ctxError);
            } else {
                this.listener.onError(protoCtx.getChannel(), ctxError, true);
            }
        }
    }

    private Object[] triggerRcvWithEmpty(ProtoContext protoCtx, Object[] sndData) {
        // 1st onReceive
        if (sndData != null) {
            if (this.listener == null) {
                // trigger tail. print event data to log
                for (Object obj : sndData) {
                    String msg = "rcv(" + this.channelID + ") There are no program at the tail of the ProtoStack, Skipping event: ";
                    logger.warn(msg + obj);
                }
            } else {
                // trigger the listener event.
                for (Object obj : sndData) {
                    this.listener.onReceive(protoCtx.getChannel(), obj);
                }
            }
        }

        // 2st onError
        Throwable ctxError = protoCtx.flash(ProtoInvocation.RCV_ERROR_TAG);
        if (ctxError != null) {
            if (this.listener == null) {
                String msg = "rcv(" + this.channelID + ") rcv Exception was fired, and it reached at the tail of the ProtoStack." //
                        + " It usually means the last handler in the ProtoStack did not handle the rcv exception.";
                logger.warn(msg, ctxError);
            } else {
                this.listener.onError(protoCtx.getChannel(), ctxError, true);
            }
        }
        return EMPTY;
    }

    private ProtoResult doRcvStack(final ProtoContext protoCtx, int depth) throws Throwable {
        boolean needRestartLater;
        boolean callFinish;
        int i;
        int backTo = -1;

        do {
            needRestartLater = false;
            callFinish = false;
            i = depth;

            while (i < this.layers.size()) {
                String stackName = this.layers.get(i).getName();
                ProtoStatus status = this.doLayer(true, protoCtx, i);
                switch (status) {
                    case Retry: // <-- can't happen, The Retry has been processed at doLayer
                    case Next:
                    case Back:
                        // only the complete ProtoStack will fire triggerRcv
                        callFinish = (i == this.layers.size() - 1);

                        if (status == ProtoStatus.Back) {
                            if (backTo == -1) {
                                backTo = i;
                                this.printLog(true, "stack '" + stackName + "' request Back.");
                            } else {
                                String backToName = this.layers.get(backTo).getName();
                                this.printLog(true, "stack '" + stackName + "' request Back, has been set to '" + backToName + "'");
                            }
                        }

                        i++;
                        continue;
                    case Again:
                        needRestartLater = true;// restart when finished
                        this.printLog(true, "stack '" + stackName + "' require Again");
                        i++;
                        continue;
                    case Restart:
                        needRestartLater = true;
                        this.printLog(true, "stack '" + stackName + "' require Restart");
                        i++;
                        break;
                    case Skip:
                        callFinish = false;
                        this.printLog(true, "stack '" + stackName + "' require Exit");
                        i++;
                        break;
                }

                break;
            }
        } while (needRestartLater);

        // result
        ProtoQueue<?> sndDown = this.layers.get(0).getSndDown();
        if (sndDown.hasMore()) {
            List<?> sndList = sndDown.takeMessage(sndDown.queueSize());
            sndDown.rcvSubmit();
            return new ProtoResult(sndList.toArray(), backTo, i - 1, callFinish);
        } else {
            return new ProtoResult(EMPTY, backTo, i - 1, callFinish);
        }
    }

    // ------------------------------------------------------------
    // SND
    // ------------------------------------------------------------

    @Override
    public synchronized Object[] onSndMessage(ProtoContext protoCtx, String stackName, Object[] sndData) throws Throwable {
        try {
            protoCtx.flash(ProtoContext.CURRENT_PROTO_IN_RCV, false);
            protoCtx.flash(ProtoContext.CURRENT_PROTO_IN_SND, true);

            if (this.layers.isEmpty()) {
                return sndData;
            }

            int depth = this.findDepth(false, stackName);
            ProtoQueue useSndUp = depth == (this.layers.size() - 1) ? this.headSndUp : this.layers.get(depth + 1).getSndDown();
            this.offerMessage(false, useSndUp, sndData);
            return this.doSndLife(protoCtx, depth);
        } finally {
            ((ProtoContextImpl) protoCtx).clearFlash();
        }
    }

    @Override
    public synchronized Object[] onSndError(ProtoContext protoCtx, String stackName, Throwable sndError) throws Throwable {
        try {
            protoCtx.flash(ProtoInvocation.SND_ERROR_TAG, sndError);
            protoCtx.flash(ProtoContext.CURRENT_PROTO_IN_RCV, false);
            protoCtx.flash(ProtoContext.CURRENT_PROTO_IN_SND, true);

            if (this.layers.isEmpty()) {
                this.triggerSend(protoCtx);
                return EMPTY;
            }

            int depth = this.findDepth(false, stackName);
            return this.doSndLife(protoCtx, depth);
        } finally {
            ((ProtoContextImpl) protoCtx).clearFlash();
        }
    }

    private Object[] doSndLife(ProtoContext protoCtx, int depth) throws Throwable {
        LinkedList<Object[]> returnData = new LinkedList<>();
        int arraySize = 0;

        int usingSndDepth = depth;
        ProtoResult sndResult;
        do {
            sndResult = this.doSndStack(protoCtx, usingSndDepth);
            if (sndResult.finish) {
                this.triggerSend(protoCtx);
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

    private void triggerSend(ProtoContext protoCtx) {
        Throwable ctxError = protoCtx.flash(ProtoInvocation.SND_ERROR_TAG);
        if (ctxError != null) {
            if (this.listener == null) {
                String msg = "snd(" + this.channelID + ") snd Exception was fired, and it reached at the head of the ProtoStack." //
                        + " It usually means the first handler in the ProtoStack did not handle the snd exception.";
                logger.warn(msg, ctxError);
            } else {
                this.listener.onError(protoCtx.getChannel(), ctxError, false);
            }
        }
    }

    private ProtoResult doSndStack(ProtoContext protoCtx, int depth) throws Throwable {
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

                String stackName = this.layers.get(i).getName();
                ProtoStatus status = this.doLayer(false, protoCtx, i);
                switch (status) {
                    case Retry: // <-- can't happen, The Retry has been processed at doLayer
                    case Next:
                    case Back:
                        if (status == ProtoStatus.Back) {
                            if (backTo == -1) {
                                backTo = i;
                                this.printLog(false, "stack '" + stackName + "' require Back to '" + backTo + "'");
                            } else {
                                this.printLog(false, "stack '" + stackName + "' Back has been set to '" + backTo + "'");
                            }
                        }
                        break;
                    case Again:
                        needRestartLater = true;// restart when finished
                        this.printLog(false, "stack '" + stackName + "' require Again");
                        break;
                    case Restart:
                        needRestartLater = true;
                        breakFor = true;
                        this.printLog(false, "stack '" + stackName + "' require Restart");
                        break;
                    case Skip:
                        breakFor = true;
                        this.printLog(false, "stack '" + stackName + "' require Exit");
                        break;
                }
            }
        } while (needRestartLater);

        // result
        ProtoQueue<?> sndDown = this.layers.get(0).getSndDown();
        if (sndDown.hasMore()) {
            List<?> sndList = sndDown.takeMessage(sndDown.queueSize());
            sndDown.rcvSubmit();
            return new ProtoResult(sndList.toArray(), backTo, i, i == -1);
        } else {
            return new ProtoResult(EMPTY, backTo, i, i == -1);
        }
    }

    // ------------------------------------------------------------
    // Statistical
    // ------------------------------------------------------------

    @Override
    public int heapUpOfRcv() {
        int heapUpOfRcv = this.headRcvUp.queueSize();
        for (ProtoInvocation<?, ?, ?, ?> layer : this.layers) {
            heapUpOfRcv += layer.getRcvDown().queueSize();
        }
        return heapUpOfRcv;
    }

    @Override
    public int heapUpOfRcv(String layerName) {
        for (ProtoInvocation<?, ?, ?, ?> layer : this.layers) {
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
        for (ProtoInvocation<?, ?, ?, ?> layer : this.layers) {
            heapUpOfSnd += layer.getSndDown().queueSize();
        }
        return heapUpOfSnd;
    }

    @Override
    public int heapUpOfSnd(String layerName) {
        for (ProtoInvocation<?, ?, ?, ?> layer : this.layers) {
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

    private static final class ProtoResult {
        public final Object[] result;
        public final int      backTo;
        public final int      layerDepth;
        public final boolean  finish;

        public ProtoResult(Object[] result, int backTo, int layerDepth, boolean finish) {
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
            ProtoInvocation<?, ?, ?, ?> layer = this.layers.get(i);
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