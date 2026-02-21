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
import java.util.ArrayList;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * root Application stack
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
@SuppressWarnings({ "unchecked" })
class ProtoChainRoot implements ProtoStack<Object> {
    private static final Logger                      logger   = Logger.getLogger(ProtoChainRoot.class);
    private static final ByteBuf[]                   EMPTY    = new ByteBuf[0];
    private final        Object                      pipeLock = new Object();
    private final        ProtoQueue<Object>          tailRcvDown;
    private final        ProtoQueue<Object>          headSndDown;
    private              ProtoInvocation<?, ?, ?, ?> head;
    private              ProtoInvocation<?, ?, ?, ?> tail;
    private              long                        channelID;
    private final        boolean                     branchMode;
    private final        ArrayList<Object[]>         cachedRcvReturnData;
    private final        ArrayList<Object[]>         cachedSndReturnData;

    ProtoChainRoot(SoConfig protoConf) {
        this(protoConf.getRcvSlotSize(), protoConf.getSndSlotSize(), false);
    }

    ProtoChainRoot(int rcvSlotSize, int sndSlotSize, boolean branchMode) {
        this.tailRcvDown = new ProtoQueue<>(rcvSlotSize < 0 ? -1 : rcvSlotSize);
        this.headSndDown = new ProtoQueue<>(sndSlotSize < 0 ? -1 : sndSlotSize);
        this.branchMode = branchMode;
        this.cachedRcvReturnData = new ArrayList<>();
        this.cachedSndReturnData = new ArrayList<>();
    }

    public ProtoQueue<?> getTailRcvDown() {
        return this.tailRcvDown;
    }

    public ProtoQueue<?> getHeadSndDown() {
        return this.headSndDown;
    }

    public void appendProtoStack(ProtoInvocation<?, ?, ?, ?> invocation) {
        if (this.head == null) {
            this.head = this.tail = invocation;
        } else {
            invocation.previous = (ProtoInvocation<Object, Object, Object, Object>) this.tail;
            this.tail.next = (ProtoInvocation<Object, Object, Object, Object>) invocation;
            this.tail = invocation;
        }
    }

    public void insertProtoStack(ProtoInvocation<?, ?, ?, ?> invocation) {
        if (this.head == null) {
            this.head = this.tail = invocation;
        } else {
            this.head.previous = (ProtoInvocation<Object, Object, Object, Object>) invocation;
            invocation.next = (ProtoInvocation<Object, Object, Object, Object>) this.head;
            this.head = invocation;
        }
    }

    @Override
    public int getSndSlotSize() {
        return this.headSndDown.slotSize();
    }

    public String findNextStack(String withName) {
        ProtoInvocation<?, ?, ?, ?> current = this.head;
        while (current != null) {
            if (StringUtils.equals(current.getName(), withName)) {
                if (current.next != null) {
                    return current.next.getName();
                } else {
                    return null; // has no next
                }
            } else {
                current = current.next;
            }
        }
        return null;
    }

    public String findPreviousStack(String withName) {
        ProtoInvocation<?, ?, ?, ?> current = this.tail;
        while (current != null) {
            if (StringUtils.equals(current.getName(), withName)) {
                if (current.previous != null) {
                    return current.previous.getName();
                } else {
                    return null; // has no previous
                }
            } else {
                current = current.previous;
            }
        }
        return null;
    }

    @Override
    public void onInit(ProtoContext protoCtx) throws Throwable {
        try {
            this.channelID = protoCtx.getChannel().getChannelId();

            ProtoInvocation<?, ?, ?, ?> current = this.head;
            while (current != null) {
                try {
                    current.onInit(protoCtx);
                } catch (Throwable e) {
                    logger.error("rcv(" + this.channelID + ") Stack " + current.getName() + " onInit error: " + e.getMessage(), e);
                } finally {
                    current = current.next;
                }
            }
        } finally {
            ((ProtoContextService) protoCtx).clearFlash();
        }
    }

    @Override
    public Object[] onActive(ProtoContext protoCtx) throws Throwable {
        try {
            ProtoInvocation<?, ?, ?, ?> current = this.head;
            while (current != null) {
                try {
                    current.onActive(protoCtx);
                } catch (Throwable e) {
                    logger.error("rcv(" + this.channelID + ") Stack " + current.getName() + " onActive error: " + e.getMessage(), e);
                } finally {
                    current = current.next;
                }
            }

            // Collect headSndDown data produced during onActive
            int queueSize = this.headSndDown.queueSize();
            if (queueSize > 0) {
                Object[] take = this.headSndDown.takeMessageToArray(queueSize);
                this.headSndDown.rcvSubmit();
                return take;
            }
            return EMPTY;
        } finally {
            ((ProtoContextService) protoCtx).clearFlash();
        }
    }

    @Override
    public void onClose(ProtoContext protoCtx) {
        try {
            ProtoInvocation<?, ?, ?, ?> current = this.head;
            while (current != null) {
                try {
                    current.onClose(protoCtx);
                } finally {
                    current = current.next;
                }
            }
        } finally {
            ((ProtoContextService) protoCtx).clearFlash();
        }
    }

    private void offerMessage(boolean isRcv, ProtoQueue<Object> queue, Object[] offerData) throws ProtoFullException {
        if (offerData == null) {
            return;
        }
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

    private void printLog(boolean isRcv, String msg) {
        if (isRcv) {
            logger.info("rcv(" + this.channelID + ") " + msg);
        } else {
            logger.info("snd(" + this.channelID + ") " + msg);
        }
    }

    private int takeSndDownToArray(ProtoInvocation<?, ?, ?, ?> current, List<Object[]> array) {
        if (current.previous == null) {
            int queueSize = this.headSndDown.queueSize();
            if (queueSize == 0) {
                return 0;
            }
            Object[] take = this.headSndDown.takeMessageToArray(queueSize);
            array.add(take);
            this.headSndDown.rcvSubmit();
            return take.length;
        } else {
            return 0;
        }
    }

    // ------------------------------------------------------------
    // RCV
    // ------------------------------------------------------------

    @Override
    public Object[] onRcvMessage(ProtoContext protoCtx, String stackName, Object[] rcvData) throws Throwable {
        ProtoContextService ctx = (ProtoContextService) protoCtx;
        synchronized (this.pipeLock) {
            try {
                ctx.setRcvMode();

                if (this.head == null) {
                    return this.triggerRcvWithEmpty(ctx, rcvData);
                } else {
                    return this.onRcvLife(ctx, stackName, rcvData);
                }
            } finally {
                ctx.clearFlash();
            }
        }
    }

    @Override
    public Object[] onRcvError(ProtoContext protoCtx, String stackName, Throwable rcvError) throws Throwable {
        ProtoContextService ctx = (ProtoContextService) protoCtx;
        synchronized (this.pipeLock) {
            try {
                ctx.setRcvError(rcvError);
                ctx.setRcvMode();

                if (this.head == null) {
                    return this.triggerRcvWithEmpty(ctx, EMPTY);
                } else {
                    return this.onRcvLife(ctx, stackName, null);
                }
            } finally {
                ctx.clearFlash();
            }
        }
    }

    private Object[] triggerRcvWithEmpty(ProtoContextService ctx, Object[] sndData) {
        // 1st onReceive
        if (sndData != null) {
            for (Object obj : sndData) {
                PlayLoad playLoad = PlayLoadObject.of(ctx.getChannel(), obj, true, false);
                ((SoContextService) ctx.getSoContext()).trigger(playLoad);
            }
        }

        // 2st onError
        Throwable ctxError = ctx.getRcvError();
        if (ctxError != null) {
            PlayLoad playLoad = PlayLoadObject.ofError(ctx.getChannel(), ctxError, true, false);
            ((SoContextService) ctx.getSoContext()).trigger(playLoad);
        }
        return EMPTY;
    }

    private Object[] onRcvLife(ProtoContextService ctx, String stackName, Object[] rcvData) throws Throwable {
        ArrayList<Object[]> returnData = this.cachedRcvReturnData;
        returnData.clear();
        int arraySize = 0;

        boolean found = false;
        ProtoInvocation<?, ?, ?, ?> current = this.head;
        while (current != null) {
            try {
                if (!found) {
                    if (stackName == null || StringUtils.equals(current.getName(), stackName)) {
                        found = true;
                        this.offerMessage(true, current.rcvUp, rcvData);
                    } else {
                        continue;
                    }
                }

                ProtoStatus status;
                while (true) {
                    status = current.doLayer(ctx, true);
                    if (status == ProtoStatus.Retry) {
                        if (ctx.getSoContext().getConfig().isPrintLog()) {
                            this.printLog(true, "Stack " + current.getName() + " doRetry");
                        }

                        arraySize += takeSndDownToArray(current, returnData);
                        continue;
                    } else if (status == ProtoStatus.Next) {
                        arraySize += takeSndDownToArray(current, returnData);

                        // when last then snd life result.
                        // skip for main pipeline single handler (doSndLife would be a no-op)
                        if (current.next == null && (this.head != this.tail || this.branchMode)) {
                            Object[] objects = this.doSndLife(ctx, stackName, null);
                            arraySize += objects.length;
                            returnData.add(objects);
                        }
                        break;
                    } else if (status == ProtoStatus.Stop) {
                        // rcv life result.
                        arraySize += takeSndDownToArray(current, returnData);

                        if (ctx.isSkipSndLife()) {
                            break;
                        }

                        // snd life result (skip for main pipeline single handler)
                        if (this.head != this.tail || this.branchMode) {
                            Object[] sndObjects = this.doSndLife(ctx, current.getName(), null);
                            arraySize += sndObjects.length;
                            returnData.add(sndObjects);
                        }
                        break;
                    } else {
                        throw new UnsupportedOperationException("unsupported status = " + status);
                    }
                }

                if (status == ProtoStatus.Stop) {
                    ctx.setRcvError(null);
                    break;
                }
            } finally {
                current = current.next;
            }
        }

        // return data(Will be written to SND)
        try {
            if (arraySize == 0) {
                return EMPTY;
            } else if (returnData.size() == 1) {
                return returnData.get(0);
            } else {
                Object[] result = new Object[arraySize];
                int dstPos = 0;
                for (Object[] objs : returnData) {
                    System.arraycopy(objs, 0, result, dstPos, objs.length);
                    dstPos = dstPos + objs.length;
                }
                return result;
            }
        } finally {
            this.triggerRcv(ctx);
        }
    }

    private void triggerRcv(ProtoContextService ctx) {
        if (this.branchMode) {
            return; // in branch mode, data stays in tailRcvDown for the routing node to collect
        }

        // 1st onReceive
        if (this.tailRcvDown.hasMore()) {
            while (this.tailRcvDown.hasMore()) {
                PlayLoad playLoad = PlayLoadObject.of(ctx.getChannel(), this.tailRcvDown.takeMessage(), true, false);
                ((SoContextService) ctx.getSoContext()).trigger(playLoad);
            }
            this.tailRcvDown.rcvSubmit();
        }

        // 2st onError
        Throwable ctxError = ctx.getRcvError();
        if (ctxError != null) {
            PlayLoad playLoad = PlayLoadObject.ofError(ctx.getChannel(), ctxError, true, false);
            ((SoContextService) ctx.getSoContext()).trigger(playLoad);
        }
    }

    // ------------------------------------------------------------
    // SND
    // ------------------------------------------------------------

    @Override
    public Object[] onSndMessage(ProtoContext protoCtx, String stackName, Object[] sndData) throws Throwable {
        ProtoContextService ctx = (ProtoContextService) protoCtx;
        synchronized (this.pipeLock) {
            try {
                ctx.setSndMode();

                if (this.tail == null) {
                    return sndData;
                } else {
                    return this.doSndLife(ctx, stackName, sndData);
                }
            } finally {
                ctx.clearFlash();
            }
        }
    }

    @Override
    public Object[] onSndError(ProtoContext protoCtx, String stackName, Throwable sndError) throws Throwable {
        ProtoContextService ctx = (ProtoContextService) protoCtx;
        synchronized (this.pipeLock) {
            try {
                ctx.setSndError(sndError);
                ctx.setSndMode();

                if (this.tail == null) {
                    this.triggerSend(ctx);
                    return EMPTY;
                } else {
                    return this.doSndLife(ctx, stackName, null);
                }
            } finally {
                ctx.clearFlash();
            }
        }
    }

    private Object[] doSndLife(ProtoContextService ctx, String stackName, Object[] sndData) throws Throwable {
        ArrayList<Object[]> returnData = this.cachedSndReturnData;
        returnData.clear();
        int arraySize = 0;

        boolean found = false;
        ProtoInvocation<?, ?, ?, ?> current = this.tail;
        while (current != null) {
            try {
                if (!found) {
                    if (stackName == null || StringUtils.equals(current.getName(), stackName)) {
                        found = true;
                        this.offerMessage(false, current.sndUp, sndData);
                    } else {
                        continue;
                    }
                }

                ProtoStatus status;
                while (true) {
                    status = current.doLayer(ctx, false);
                    if (status == ProtoStatus.Retry) {
                        if (ctx.getSoContext().getConfig().isPrintLog()) {
                            this.printLog(false, "Stack " + current.getName() + " doRetry");
                        }

                        arraySize += takeSndDownToArray(current, returnData);
                        continue;
                    } else if (status == ProtoStatus.Next) {
                        arraySize += takeSndDownToArray(current, returnData);
                        break;
                    } else if (status == ProtoStatus.Stop) {
                        // rcv life result.
                        arraySize += takeSndDownToArray(current, returnData);
                        break;
                    } else {
                        throw new UnsupportedOperationException("unsupported status = " + status);
                    }
                }

                if (status == ProtoStatus.Stop) {
                    ctx.setSndError(null);
                    break;
                }
            } finally {
                current = current.previous;
            }
        }

        // return data(Will be written to SND)
        try {
            if (arraySize == 0) {
                return EMPTY;
            } else if (returnData.size() == 1) {
                return returnData.get(0);
            } else {
                Object[] result = new Object[arraySize];
                int dstPos = 0;
                for (Object[] objs : returnData) {
                    System.arraycopy(objs, 0, result, dstPos, objs.length);
                    dstPos = dstPos + objs.length;
                }
                return result;
            }
        } finally {
            this.triggerSend(ctx);
        }
    }

    private void triggerSend(ProtoContextService ctx) {
        if (this.branchMode) {
            return; // in branch mode, errors are handled by the routing node
        }

        Throwable ctxError = ctx.getSndError();
        if (ctxError != null) {
            PlayLoad playLoad = PlayLoadObject.ofError(ctx.getChannel(), ctxError, false, true);
            ((SoContextService) ctx.getSoContext()).trigger(playLoad);
        }
    }

    // ------------------------------------------------------------
    // User Event
    // ------------------------------------------------------------
    @Override
    public void onRcvUserEvent(ProtoContext protoCtx, String stackName, SoUserEvent event) throws Throwable {
        synchronized (this.pipeLock) {
            try {
                if (this.doRcvUserEvent(protoCtx, stackName, event)) {
                    this.doSndUserEvent(protoCtx, null, event);
                }
            } finally {
                ((ProtoContextService) protoCtx).clearFlash();
            }
        }
    }

    @Override
    public void onSndUserEvent(ProtoContext protoCtx, String stackName, SoUserEvent event) throws Throwable {
        synchronized (this.pipeLock) {
            try {
                this.doSndUserEvent(protoCtx, stackName, event);
            } finally {
                ((ProtoContextService) protoCtx).clearFlash();
            }
        }
    }

    private boolean doRcvUserEvent(ProtoContext protoCtx, String stackName, SoUserEvent event) throws Throwable {
        boolean continueStatus = true;
        boolean found = false;
        ProtoInvocation<?, ?, ?, ?> current = this.head;
        while (current != null) {
            try {
                if (!found) {
                    if (stackName == null || StringUtils.equals(current.getName(), stackName)) {
                        found = true;
                    } else {
                        continue;
                    }
                }

                if (continueStatus) {
                    continueStatus = current.onEvent(protoCtx, event, true);
                }
            } finally {
                current = current.next;
            }
        }
        return continueStatus;
    }

    private boolean doSndUserEvent(ProtoContext protoCtx, String stackName, SoUserEvent event) throws Throwable {
        boolean continueStatus = true;
        boolean found = false;
        ProtoInvocation<?, ?, ?, ?> current = this.tail;
        while (current != null) {
            try {
                if (!found) {
                    if (stackName == null || StringUtils.equals(current.getName(), stackName)) {
                        found = true;
                    } else {
                        continue;
                    }
                }

                if (continueStatus) {
                    continueStatus = current.onEvent(protoCtx, event, false);
                }
            } finally {
                current = current.previous;
            }
        }
        return continueStatus;
    }

    // ------------------------------------------------------------
    // Statistical
    // ------------------------------------------------------------

    @Override
    public String toString() {
        List<String> layerNames = new ArrayList<>();
        List<String> monitorRcv = new ArrayList<>();
        List<String> monitorSnd = new ArrayList<>();
        String rootRcv = rootMonitorRcvString() + " (RCV)";
        String rootSnd = rootMonitorSndString() + " (SND)";

        // nameLength
        int maxNameLength = 0;
        int rcvMaxLength = rootRcv.length() + 1;
        int sndMaxLength = rootSnd.length();

        ProtoInvocation<?, ?, ?, ?> layer = this.head;
        int layerCount = 0;
        while (layer != null) {
            String layerName = layer.getName();
            layerName = StringUtils.isBlank(layerName) ? ("Layer@" + Integer.toHexString(layer.hashCode())) : layerName;
            layerNames.add(layerName);
            maxNameLength = Math.max(maxNameLength, layerName.length());

            monitorRcv.add(layer.toMonitorRcvString() + ",");
            monitorSnd.add(layer.toMonitorSndString());
            layerCount++;

            layer = layer.next;
        }

        // bodyLength
        for (int i = 0; i < layerCount; i++) {
            rcvMaxLength = Math.max(rcvMaxLength, monitorRcv.get(i).length());
            sndMaxLength = Math.max(sndMaxLength, monitorSnd.get(i).length());
        }

        // build string
        StringBuilder sb = new StringBuilder();
        String nameBorder = StringUtils.repeat("━", maxNameLength);
        String rcvBorder = StringUtils.repeat("━", rcvMaxLength);
        String sndBorder = StringUtils.repeat("━", sndMaxLength);

        sb.append(String.format("┏━%s━━━━%s ↓ %s ━┓\n", nameBorder, rcvBorder, rootSnd));
        for (int i = 0; i < layerCount; i++) {
            String layerName = StringUtils.rightPad(layerNames.get(i), maxNameLength, " ");
            String rcvPart = StringUtils.rightPad(monitorRcv.get(i), rcvMaxLength, " ");
            String sndPart = StringUtils.rightPad(monitorSnd.get(i), sndMaxLength, " ");
            sb.append(String.format("┃ %s [↑ %s ↓ %s] ┃\n", layerName, rcvPart, sndPart));
        }
        sb.append(String.format("┗━%s━ ↑ %s ━━━%s━━┛", nameBorder, rootRcv, sndBorder));

        return sb.toString();
    }

    private String rootMonitorRcvString() {
        int capacity = this.tailRcvDown.getCapacity();
        if (capacity > 500) {
            return this.tailRcvDown.queueSize() + "/500+";
        } else {
            return this.tailRcvDown.queueSize() + "/" + capacity;
        }
    }

    private String rootMonitorSndString() {
        int capacity = this.headSndDown.getCapacity();
        if (capacity > 500) {
            return this.headSndDown.queueSize() + "/500+";
        } else {
            return this.headSndDown.queueSize() + "/" + capacity;
        }
    }
}