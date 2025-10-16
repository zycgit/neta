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
import java.util.LinkedList;
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
    private static final Logger                      logger = Logger.getLogger(ProtoChainRoot.class);
    private static final ByteBuf[]                   EMPTY  = new ByteBuf[0];
    private final        ProtoQueue<Object>          tailRcvDown;
    private final        ProtoQueue<Object>          headSndDown;
    private              ProtoInvocation<?, ?, ?, ?> head;
    private              ProtoInvocation<?, ?, ?, ?> tail;
    private              long                        channelID;

    ProtoChainRoot(SoConfig protoConf) {
        int rcvSize = protoConf.getRcvSlotSize();
        int sndSize = protoConf.getSndSlotSize();
        this.tailRcvDown = new ProtoQueue<>(rcvSize < 0 ? -1 : rcvSize);
        this.headSndDown = new ProtoQueue<>(sndSize < 0 ? -1 : sndSize);
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
                } finally {
                    current = current.next;
                }
            }
        } finally {
            ((ProtoContextService) protoCtx).clearFlash();
        }
    }

    @Override
    public void onActive(ProtoContext protoCtx) throws Throwable {
        try {
            ProtoInvocation<?, ?, ?, ?> current = this.head;
            while (current != null) {
                try {
                    current.onActive(protoCtx);
                } finally {
                    current = current.next;
                }
            }
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

    private int takeSndDownToArray(ProtoInvocation<?, ?, ?, ?> current, LinkedList<Object[]> array) {
        if (current.previous == null) {
            Object[] take = this.headSndDown.takeMessage(this.headSndDown.queueSize()).toArray();
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
    public synchronized Object[] onRcvMessage(ProtoContext protoCtx, String stackName, Object[] rcvData) throws Throwable {
        try {
            protoCtx.flash(ProtoContext.CURRENT_PROTO_IN_RCV, true);
            protoCtx.flash(ProtoContext.CURRENT_PROTO_IN_SND, false);

            if (this.head == null) {
                return this.triggerRcvWithEmpty(protoCtx, rcvData);
            } else {
                return this.onRcvLife(protoCtx, stackName, rcvData);
            }
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

            if (this.head == null) {
                return this.triggerRcvWithEmpty(protoCtx, EMPTY);
            } else {
                return this.onRcvLife(protoCtx, stackName, null);
            }
        } finally {
            ((ProtoContextService) protoCtx).clearFlash();
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

    private Object[] onRcvLife(ProtoContext protoCtx, String stackName, Object[] rcvData) throws Throwable {
        LinkedList<Object[]> returnData = new LinkedList<>();
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
                    status = current.doLayer(protoCtx, true);
                    if (status == ProtoStatus.Retry) {
                        if (protoCtx.getSoContext().getConfig().isPrintLog()) {
                            this.printLog(true, "Stack " + current.getName() + " doRetry");
                        }

                        arraySize += takeSndDownToArray(current, returnData);
                        continue;
                    } else if (status == ProtoStatus.Next) {
                        arraySize += takeSndDownToArray(current, returnData);

                        // when last then snd life result.
                        if (current.next == null) {
                            Object[] objects = this.doSndLife(protoCtx, stackName, null);
                            arraySize += objects.length;
                            returnData.add(objects);
                        }
                        break;
                    } else if (status == ProtoStatus.Stop) {
                        // rcv life result.
                        arraySize += takeSndDownToArray(current, returnData);

                        // snd life result.
                        Object[] objects = this.doSndLife(protoCtx, current.getName(), null);
                        arraySize += objects.length;
                        returnData.add(objects);
                        break;
                    } else {
                        throw new UnsupportedOperationException("unsupported status = " + status);
                    }
                }

                if (status == ProtoStatus.Stop) {
                    protoCtx.flash(ProtoInvocation.RCV_ERROR_TAG, null);
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
            this.triggerRcv(protoCtx);
        }
    }

    private void triggerRcv(ProtoContext protoCtx) {
        // 1st onReceive
        if (this.tailRcvDown.hasMore()) {
            while (this.tailRcvDown.hasMore()) {
                PlayLoad playLoad = PlayLoadObject.of(protoCtx.getChannel(), this.tailRcvDown.takeMessage(), true, false);
                ((SoContextService) protoCtx.getSoContext()).trigger(playLoad);
            }
            this.tailRcvDown.rcvSubmit();
        }

        // 2st onError
        Throwable ctxError = protoCtx.flash(ProtoInvocation.RCV_ERROR_TAG);
        if (ctxError != null) {
            PlayLoad playLoad = PlayLoadObject.ofError(protoCtx.getChannel(), ctxError, true, false);
            ((SoContextService) protoCtx.getSoContext()).trigger(playLoad);
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

            if (this.tail == null) {
                return sndData;
            } else {
                return this.doSndLife(protoCtx, stackName, sndData);
            }
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

            if (this.tail == null) {
                this.triggerSend(protoCtx);
                return EMPTY;
            } else {
                return this.doSndLife(protoCtx, stackName, null);
            }
        } finally {
            ((ProtoContextService) protoCtx).clearFlash();
        }
    }

    private Object[] doSndLife(ProtoContext protoCtx, String stackName, Object[] sndData) throws Throwable {
        LinkedList<Object[]> returnData = new LinkedList<>();
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
                    status = current.doLayer(protoCtx, false);
                    if (status == ProtoStatus.Retry) {
                        if (protoCtx.getSoContext().getConfig().isPrintLog()) {
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
                    protoCtx.flash(ProtoInvocation.SND_ERROR_TAG, null);
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
            this.triggerSend(protoCtx);
        }
    }

    private void triggerSend(ProtoContext protoCtx) {
        Throwable ctxError = protoCtx.flash(ProtoInvocation.SND_ERROR_TAG);
        if (ctxError != null) {
            PlayLoad playLoad = PlayLoadObject.ofError(protoCtx.getChannel(), ctxError, false, true);
            ((SoContextService) protoCtx.getSoContext()).trigger(playLoad);
        }
    }

    // ------------------------------------------------------------
    // User Event
    // ------------------------------------------------------------
    @Override
    public void onRcvUserEvent(ProtoContext protoCtx, String stackName, SoUserEvent event) throws Throwable {
        try {
            if (this.doRcvUserEvent(protoCtx, stackName, event)) {
                this.doSndUserEvent(protoCtx, null, event);
            }
        } finally {
            ((ProtoContextService) protoCtx).clearFlash();
        }
    }

    @Override
    public void onSndUserEvent(ProtoContext protoCtx, String stackName, SoUserEvent event) throws Throwable {
        try {
            this.doSndUserEvent(protoCtx, stackName, event);
        } finally {
            ((ProtoContextService) protoCtx).clearFlash();
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