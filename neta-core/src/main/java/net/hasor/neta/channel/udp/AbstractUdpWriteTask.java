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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.channels.ShutdownChannelGroupException;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.*;

/**
 * Abstract base class for UDP-style write tasks that provides the complete
 * doWork loop (prepare → send → finish → continue), retry on timeout, and
 * exception handling. Subclasses only need to implement:
 * <ul>
 *   <li>{@link #isChannelOpen()} — whether the underlying transport is still usable</li>
 *   <li>{@link #doSend(byte[])} — the actual send operation, returning bytes written</li>
 * </ul>
 * And may optionally override:
 * <ul>
 *   <li>{@link #wrapSendData(byte[])} — to transform raw bytes before sending (e.g. framing)</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public abstract class AbstractUdpWriteTask extends DefaultSoTask {
    protected final SoContextService context;
    private final   NetChannel       netChannel;
    private final   NetMonitor       monitor;
    private final   SoSndContext     wContext;
    private         byte[]           sendData;
    private         int              timeoutRetryCnt = 0;

    public AbstractUdpWriteTask(NetChannel netChannel, SoSndContext wContext, SoContextService context) {
        this.netChannel = netChannel;
        this.monitor = netChannel.getMonitor();
        this.wContext = wContext;
        this.context = context;
    }

    /** Returns the {@link NetChannel} associated with this write task. */
    protected NetChannel getNetChannel() {
        return this.netChannel;
    }

    /** Subclass must report whether the underlying transport channel is open. */
    protected abstract boolean isChannelOpen();

    /**
     * Performs the actual send. The data has already been processed by {@link #wrapSendData}.
     * @param data the ready-to-send buffer
     * @return number of bytes written; 0 means the channel was not ready (will retry after delay)
     * @throws IOException on transport-level errors
     */
    protected abstract int doSend(byte[] data) throws IOException;

    /**
     * Hook to transform raw application bytes before sending. Default implementation
     * wraps them into a {@link ByteBuffer} with no transformation.
     * <p>
     * Subclasses can override this to add framing (e.g. QUIC STREAM / DATAGRAM frames).
     * @param sendData the raw application bytes
     * @return a {@link ByteBuffer} ready for {@link #doSend}
     */
    protected byte[] wrapSendData(byte[] sendData) {
        return sendData;
    }

    @Override
    protected void doWork(int retryCnt) {
        // test exit
        if (this.wContext.isEmpty()) {
            this.finishTask();
            return;
        }
        if (!this.isChannelOpen()) {
            SoUnfinishedSndException err = new SoUnfinishedSndException("channel is closed.");
            context.notifySndChannelException(this.netChannel.getChannelId(), true, err);
            this.wContext.purge(err);
            this.finishTask();
            return;
        }

        // prepare
        if (this.sendData == null) {
            SoSndData sndData = this.wContext.peekData();
            byte[] bytes = sndData.transferPull();
            if (bytes != null) {
                this.sendData = wrapSendData(bytes);
            }
        }

        // send
        if (this.sendData != null) {
            try {
                int write = this.doSend(this.sendData);
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
            UdpSoConfig cfg = (UdpSoConfig) this.netChannel.getConfig();
            int maxRetry = cfg.getSndWriteRetryCount();
            if (maxRetry > 0 && this.timeoutRetryCnt < maxRetry) {
                this.timeoutRetryCnt++;
                this.delayTask(cfg.getSndWriteRetryIntervalMs(), TimeUnit.MILLISECONDS);
                return true; // retry after delay
            }
            // retries exhausted (or maxRetry = 0): notify and discard this packet
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
