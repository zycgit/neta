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
package net.hasor.neta.channel.quic;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.channels.ShutdownChannelGroupException;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.udp.UdpSoConfig;

/**
 * Task for writing data to a QUIC stream.
 * <p>
 * Mirrors the behavior of {@link net.hasor.neta.channel.udp.UdpWriteTask}:
 * task-driven, handles write=0 back-pressure via {@code delayTask}, retries
 * on {@link InterruptedByTimeoutException}, and routes all exceptions through
 * the framework's {@code notifySndChannelException} notification chain.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicWriteTask extends DefaultSoTask {
    protected final SoContextService context;
    private final   NetChannel       netChannel;
    private final   NetMonitor       monitor;
    private final   long             streamId;
    private final   QuicChannel      quicChannel;
    private final   SoSndContext     wContext;
    private         byte[]           sendData;
    private         int              timeoutRetryCnt = 0;

    QuicWriteTask(NetChannel netChannel, long streamId, QuicChannel quicChannel, SoSndContext wContext, SoContextService context) {
        this.netChannel = netChannel;
        this.monitor = netChannel.getMonitor();
        this.streamId = streamId;
        this.quicChannel = quicChannel;
        this.wContext = wContext;
        this.context = context;
    }

    @Override
    protected void doWork(int retryCnt) {
        // exit if nothing to send
        if (this.wContext.isEmpty()) {
            this.finishTask();
            return;
        }
        if (!this.quicChannel.isConnectionOpen()) {
            SoUnfinishedSndException err = new SoUnfinishedSndException("QUIC connection is closed.");
            this.context.notifySndChannelException(this.netChannel.getChannelId(), true, err);
            this.wContext.purge(err);
            this.finishTask();
            return;
        }

        // prepare next chunk
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
                int written = this.quicChannel.sendStreamData(this.streamId, this.sendData, false);
                if (written == 0) {
                    // send buffer full — back off and retry
                    this.delayTask(50, TimeUnit.MILLISECONDS);
                    return;
                } else {
                    this.monitor.updateSndCounter(written);
                    this.sendData = null;
                }
            } catch (Exception e) {
                if (this.handleException(e, this.wContext)) {
                    return;
                }
            }
        }

        // advance to next SoSndData if current one is fully consumed
        if (this.sendData == null) {
            SoSndData sndData = this.wContext.peekData();
            if (!sndData.hasReadable()) {
                this.wContext.popData();
                // complete asynchronously (not on the caller's thread)
                this.submitTask(new SoDelayTask(0)).onFinal(f -> {
                    sndData.completed();
                });
            }
        }

        // loop to process next data item
        this.continueTask();
    }

    private boolean handleException(Throwable e, SoSndContext wContext) {
        long channelId = this.netChannel.getChannelId();

        if (e instanceof InterruptedByTimeoutException) {
            UdpSoConfig cfg = (UdpSoConfig) this.netChannel.getConfig();
            int maxRetry = cfg.getSndWriteRetryCount();
            if (maxRetry > 0 && this.timeoutRetryCnt < maxRetry) {
                this.timeoutRetryCnt++;
                this.delayTask(cfg.getSndWriteRetryIntervalMs(), TimeUnit.MILLISECONDS);
                return true;
            }
            String retryInfo = maxRetry > 0 ? ", tried " + this.timeoutRetryCnt + " time(s)" : "";
            this.timeoutRetryCnt = 0;
            this.sendData = null;
            String errorMsg = "QUIC send data timeout with " + this.netChannel.getConfig().getSoWriteTimeoutMs() + " milliseconds" + retryInfo + ".";
            this.context.notifySndChannelException(channelId, false, new SoWriteTimeoutException(errorMsg));
            return false;
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

    private void purgeSndData(Throwable cause, SoSndContext wContext) {
        while (!wContext.isEmpty()) {
            SoSndData sndData = wContext.popData();
            this.submitTask(new SoDelayTask(0)).onFinal(f -> {
                sndData.failed(cause);
            });
        }
    }

    private Future<?> submitTask(DefaultSoTask task) {
        return this.context.submitSoTask(task, this);
    }
}
