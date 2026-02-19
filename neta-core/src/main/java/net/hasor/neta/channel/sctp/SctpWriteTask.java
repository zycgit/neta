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
package net.hasor.neta.channel.sctp;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.channels.ShutdownChannelGroupException;
import java.util.concurrent.TimeUnit;
import com.sun.nio.sctp.MessageInfo;
import com.sun.nio.sctp.SctpChannel;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.*;

/**
 * Asynchronous write task for SCTP channels.
 * Handles the actual writing of data to the underlying SCTP channel.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class SctpWriteTask extends DefaultSoTask {
    protected final SoContextService context;
    private final   NetChannel       netChannel;
    private final   NetMonitor       monitor;
    private final   SctpChannel      channel;
    private final   SoSndContext     wContext;
    //
    private         SctpMessage      sendData;
    private         ByteBuffer       sndSwapBuf;
    private         int              timeoutRetryCnt = 0;

    public SctpWriteTask(NetChannel netChannel, SctpChannel channel, SoSndContext wContext, SoContextService context) {
        this.netChannel = netChannel;
        this.monitor = netChannel.getMonitor();
        this.channel = channel;
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
        if (!this.channel.isOpen()) {
            SoUnfinishedSndException err = new SoUnfinishedSndException("channel is closed.");
            context.notifySndChannelException(this.netChannel.getChannelId(), true, err);
            this.wContext.purge(err);
            this.finishTask();
            return;
        }

        // prepare
        if (this.sendData == null) {
            SoSndData sndData = this.wContext.peekData();
            Object rawData = sndData.transferTake();
            if (rawData instanceof SctpMessage) {
                this.sendData = (SctpMessage) rawData;
            } else if (rawData instanceof net.hasor.neta.bytebuf.ByteBuf) {
                this.sendData = SctpMessage.of(MessageInfo.createOutgoing(null, 0), (net.hasor.neta.bytebuf.ByteBuf) rawData);
            }
        }

        // send
        if (this.sendData != null) {
            try {
                int readable = this.sendData.getByteBuf().readableBytes();
                if (this.sndSwapBuf == null || this.sndSwapBuf.capacity() < readable) {
                    this.sndSwapBuf = ByteBuffer.allocate(readable);
                } else {
                    this.sndSwapBuf.clear();
                    this.sndSwapBuf.limit(readable);
                }
                this.sendData.getByteBuf().readBuffer(this.sndSwapBuf);
                this.sndSwapBuf.flip();

                int write = this.channel.send(this.sndSwapBuf, this.sendData.getInfo());
                if (write == 0) {
                    this.delayTask(50, TimeUnit.MILLISECONDS);
                    return;
                } else {
                    this.monitor.updateSndCounter(write);
                    this.sendData = null;
                }
            } catch (Exception e) {
                if (this.handleException(e, this.wContext)) {
                    return;
                }
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

    /**
     * Handle send exception.
     * @return true if doWork should return immediately (retry scheduled or fatal),
     * false to fall through to try-finish and continueTask.
     */
    private boolean handleException(Throwable e, SoSndContext wContext) {
        long channelId = this.netChannel.getChannelId();

        if (e instanceof InterruptedByTimeoutException) {
            SctpSoConfig cfg = (SctpSoConfig) this.netChannel.getConfig();
            int maxRetry = cfg.getSndWriteRetryCount();
            if (maxRetry > 0 && this.timeoutRetryCnt < maxRetry) {
                this.timeoutRetryCnt++;
                this.delayTask(cfg.getSndWriteRetryIntervalMs(), TimeUnit.MILLISECONDS);
                return true; // retry after delay
            }
            // retries exhausted (or maxRetry = 0): notify and discard this message
            String retryInfo = maxRetry > 0 ? ", tried " + this.timeoutRetryCnt + " time(s)" : "";
            this.timeoutRetryCnt = 0;
            this.sendData = null;
            String errorMsg = "send data timeout with " + this.netChannel.getConfig().getSoWriteTimeoutMs() + " milliseconds" + retryInfo + ".";
            this.context.notifySndChannelException(channelId, false, new SoWriteTimeoutException(errorMsg));
            return false; // fall through: try-finish will pop the discarded item
        }

        SoException finalErr;
        if (e instanceof ClosedChannelException || e instanceof ShutdownChannelGroupException) {
            finalErr = new SoCloseException(e.getMessage(), e);
            this.context.notifySndChannelException(channelId, true, finalErr);
            this.purgeSndData(e, wContext);
        } else {
            finalErr = new SoSndException(e.getMessage(), e);
            this.context.notifySndChannelException(channelId, false, finalErr);
        }
        return false;
    }

    private void purgeSndData(Throwable e, SoSndContext wContext) {
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