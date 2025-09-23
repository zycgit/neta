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
package net.hasor.neta.channel.udp;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.*;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.channels.ShutdownChannelGroupException;
import java.util.concurrent.TimeUnit;

class UdpWriteTask extends DefaultSoTask {
    protected final SoContextService context;
    private final   NetChannel       netChannel;
    private final   NetMonitor       monitor;
    private final   DatagramChannel  udpChannel;
    private final   SoSndContext     wContext;
    private         byte[]           sendData;

    public UdpWriteTask(NetChannel netChannel, DatagramChannel channel, SoSndContext wContext, SoContextService context) {
        this.netChannel = netChannel;
        this.monitor = netChannel.getMonitor();
        this.udpChannel = channel;
        this.wContext = wContext;
        this.context = context;
    }

    @Override
    protected void doWork(int retryCnt) {
        // test exit
        if (this.wContext.isEmpty()) {
            this.finishTask();
            return;
        }
        if (!this.udpChannel.isOpen()) {
            this.wContext.purge(SoCloseException.INSTANCE);
            this.finishTask();
            return;
        }

        // prepare
        if (this.sendData == null) {
            SoSndData sndData = this.wContext.peekData();
            byte[] bytes = sndData.transferPull();
            if (bytes != null) {
                this.sendData = bytes;
            }
        }

        // send
        if (this.sendData != null) {
            try {
                int write;
                if (this.netChannel.isServer()) {
                    write = this.udpChannel.send(ByteBuffer.wrap(this.sendData), this.netChannel.getRemoteAddr());
                } else {
                    write = this.udpChannel.write(ByteBuffer.wrap(this.sendData));
                }
                if (write == 0) {
                    this.delayTask(50, TimeUnit.MILLISECONDS);
                    return;
                } else {
                    this.monitor.updateSndCounter(write);
                    this.sendData = null;
                }
            } catch (Exception e) {
                this.handleException(e, this.wContext);
            }
        }

        // try finish
        if (this.sendData == null) {
            SoSndData sndData = this.wContext.peekData();
            if (!sndData.hasReadable()) {
                this.wContext.popData();
                this.submitTask(new SoDelayTask(0)).onFinal(f -> {
                    sndData.completed();
                });
            }
        }

        // loop
        this.continueTask();
    }

    private void handleException(Throwable e, SoSndContext wContext) {
        String finalMsg;
        Throwable finalErr;
        long channelId = this.netChannel.getChannelId();

        if (e instanceof InterruptedByTimeoutException) {
            String errorMsg = "send data timeout with " + this.netChannel.getConfig().getSoWriteTimeoutMs() + " milliseconds.";
            String msg = "snd(" + channelId + ") " + errorMsg;

            finalErr = new SoWriteTimeoutException(errorMsg);
            finalMsg = msg;
        } else if (e instanceof ClosedChannelException) {
            finalMsg = "snd(" + channelId + ") close, msg:" + e.getMessage();
            finalErr = e;
        } else if (e instanceof ShutdownChannelGroupException) {
            finalMsg = "snd(" + channelId + ") shutdown, msg:" + e.getMessage();
            finalErr = e;
        } else {
            finalMsg = "snd(" + channelId + ") error, msg:" + e.getMessage();
            finalErr = e;
        }

        this.context.notifySndChannelError(channelId, finalErr);
        this.context.asyncUnsafeCloseChannel(channelId, finalMsg, finalErr);

        while (!wContext.isEmpty()) {
            SoSndData sndData = wContext.popData();
            this.submitTask(new SoDelayTask(0)).onFinal(f -> {
                sndData.failed(e);
            });
        }
    }

    private Future<?> submitTask(DefaultSoTask task) {
        return this.context.submitSoTask(task, this);
    }
}