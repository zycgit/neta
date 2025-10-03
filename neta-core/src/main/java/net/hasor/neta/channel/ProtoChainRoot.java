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
package net.hasor.neta.channel;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;

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
    private              long                              channelID;

    ProtoChainRoot(ProtoConfig protoConf) {
        this.layers = new ArrayList<>();

        int rcvSize = protoConf.getRcvDownSlotSize();
        int sndSize = protoConf.getSndUpSlotSize();
        this.headRcvUp = new ProtoQueue<>(rcvSize < 0 ? -1 : rcvSize);
        this.headSndUp = new ProtoQueue<>(sndSize < 0 ? -1 : sndSize);
    }

    public void addProtoStack(ProtoInvocation<?, ?, ?, ?> invocation) {
        this.layers.add(invocation);
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
    public ProtoStatistical getStatistical() {
        return this;
    }

    @Override
    public void onInit(ProtoContext protoCtx) throws Throwable {
        this.channelID = protoCtx.getChannel().getChannelId();

        for (int i = 0; i < this.layers.size(); i++) {
            try {
                ProtoInvocation<?, ?, ?, ?> layer = this.layers.get(i);
                protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_NAME, layer.getName());
                layer.onInit(protoCtx);
            } finally {
                protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_NAME, null);
            }
        }
    }

    @Override
    public void onActive(ProtoContext protoCtx) throws Throwable {
        for (int i = 0; i < this.layers.size(); i++) {
            try {
                ProtoInvocation<?, ?, ?, ?> layer = this.layers.get(i);
                protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_NAME, layer.getName());
                layer.onActive(protoCtx);
            } finally {
                protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_NAME, null);
            }
        }
    }

    @Override
    public void onClose(ProtoContext protoCtx) {
        for (ProtoInvocation<?, ?, ?, ?> layer : this.layers) {
            layer.onClose(protoCtx);
        }
    }

    private void offerMessage(boolean isRcv, ProtoQueue<Object> queue, Object[] offerData) throws ProtoFullException {
        if (queue.offerMessage(offerData) == offerData.length) {
            queue.sndSubmit();
        } else {
            queue.sndReset();
            String msgTag = isRcv ? "rcv" : "snd";
            int slotSize = queue.slotSize();
            int require = offerData.length;

            String msg = String.format("%s(%s) ProtoStack slot is full, available slot is %s, require %s.", msgTag, this.channelID, slotSize, require);
            logger.error(msg);
            throw new ProtoFullException(msg);
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
        boolean netLog = protoCtx.getConfig().isPrintLog();
        ProtoQueue<?> useRcvUp = i == 0 ? this.headRcvUp : this.layers.get(i - 1).getRcvDown();
        ProtoQueue<?> useSndUp = i == (this.layers.size() - 1) ? this.headSndUp : this.layers.get(i + 1).getSndDown();

        ProtoStatus status;
        do {
            ProtoInvocation layer = this.layers.get(i);

            try {
                protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_NAME, layer.getName());
                status = layer.doLayer(protoCtx, isRcv, useRcvUp, useSndUp);
            } finally {
                protoCtx.flash(ProtoContext.CURRENT_PROTO_STACK_NAME, null);
            }

            if (status == null) {
                throw new IllegalStateException("return status missing.");
            }

            if (status == ProtoStatus.Retry && netLog) {
                this.printLog(isRcv, "Stack " + i + "/" + this.layers.size() + " doRetry");
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
            ((ProtoContextService) protoCtx).clearFlash();
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
            ((ProtoContextService) protoCtx).clearFlash();
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
            while (rcvDown.hasMore()) {
                PlayLoad playLoad = PlayLoadObject.of(protoCtx.getChannel(), rcvDown.takeMessage(), true, false);
                ((SoContextService) protoCtx.getSoContext()).trigger(playLoad);
            }
            rcvDown.rcvSubmit();
        }

        // 2st onError
        Throwable ctxError = protoCtx.flash(ProtoInvocation.RCV_ERROR_TAG);
        if (ctxError != null) {
            PlayLoad playLoad = PlayLoadObject.ofError(protoCtx.getChannel(), ctxError, true, false);
            ((SoContextService) protoCtx.getSoContext()).trigger(playLoad);
        }
    }

    private Object[] triggerRcvWithEmpty(ProtoContext protoCtx, Object[] sndData) {
        // 1st onReceive
        if (sndData != null) {
            for (Object obj : sndData) {
                PlayLoad playLoad = PlayLoadObject.of(protoCtx.getChannel(), obj, true, false);
                ((SoContextService) protoCtx.getSoContext()).trigger(playLoad);
            }
        }

        // 2st onError
        Throwable ctxError = protoCtx.flash(ProtoInvocation.RCV_ERROR_TAG);
        if (ctxError != null) {
            PlayLoad playLoad = PlayLoadObject.ofError(protoCtx.getChannel(), ctxError, true, false);
            ((SoContextService) protoCtx.getSoContext()).trigger(playLoad);
        }
        return EMPTY;
    }

    private ProtoResult doRcvStack(final ProtoContext protoCtx, int depth) throws Throwable {
        boolean netLog = protoCtx.getConfig().isPrintLog();
        boolean callFinish;
        int i;
        int backTo = -1;

        callFinish = false;
        i = depth;

        while (i < this.layers.size()) {
            String stackName = this.layers.get(i).getName();
            ProtoStatus status = this.doLayer(true, protoCtx, i);
            switch (status) {
                case Retry: // <-- can't happen, The Retry has been processed at doLayer
                case Next:
                    // only the complete ProtoStack will fire triggerRcv
                    callFinish = (i == this.layers.size() - 1);
                    i++;
                    continue;
                case Stop:
                    callFinish = false;
                    if (netLog) {
                        this.printLog(true, "stack '" + stackName + "' require Exit");
                    }
                    i++;
                    break;
            }

            break;
        }

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
            ((ProtoContextService) protoCtx).clearFlash();
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
            ((ProtoContextService) protoCtx).clearFlash();
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
            PlayLoad playLoad = PlayLoadObject.ofError(protoCtx.getChannel(), ctxError, false, true);
            ((SoContextService) protoCtx.getSoContext()).trigger(playLoad);
        }
    }

    private ProtoResult doSndStack(ProtoContext protoCtx, int depth) throws Throwable {
        boolean netLog = protoCtx.getConfig().isPrintLog();
        int i;
        int backTo = -1;

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
                    break;
                case Stop:
                    breakFor = true;
                    if (netLog) {
                        this.printLog(false, "stack '" + stackName + "' require Exit");
                    }
                    break;
            }
        }

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
}