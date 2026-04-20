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
package net.hasor.neta.channel.transport.udp;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.channels.ShutdownChannelGroupException;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.*;
/**
 * Abstract skeletal implementation of a UDP send task.
 * <p>This class breaks one send attempt into a consistent sequence of stages: fetch pending data
 * from {@link SoSndContext}, optionally wrap it before transmission, invoke the underlying
 * transport send operation, advance the queue after success, and apply unified failure handling
 * when timeout, channel close, or other transport errors occur.
 * <p>It is not tied to one specific UDP variant. The transport-specific write details are left to
 * subclasses, so plain UDP, UDP-based extension protocols, and send models that require extra
 * framing can all reuse the same scheduling skeleton.
 * <p><b>Send flow:</b>
 * <pre>
 *   doWork(retryCnt)
 *        ▼
 *   wContext.isEmpty() ?
 *    ┌───┴───────────────┐
 *    ▼                   ▼
 *  yes, finishTask()   no, continue
 *                        ▼
 *                 isChannelOpen() ?
 *                  ┌────┴──────────────┐
 *                  ▼                   ▼
 *     no, notify close exception   yes, prepare send data
 *         purge + finishTask()              │
 *                                           ▼
 *                                sendData == null ?
 *                                           ▼
 *                         peekData() -> transferPull() -> wrapSendData(...)
 *                                           ▼
 *                                    doSend(sendData)
 *                    ┌──────────────┼───────────────────────────┐
 *                    ▼              ▼                           ▼
 *                write == 0     write > 0                exception thrown
 *                    │              │                           ▼
 *          delayTask(...) and return update metrics       handleException(...)
 *                                    and clear sendData   ┌────────┴────────┐
 *                                                         ▼                 ▼
 *                                                  return true        return false
 *                                                         │                 │
 *                                                  return immediately       │
 *                                      ┌────────────────────────────────────┘
 *                                      ▼
 *                  has the current send item finished fully?
 *                                      ▼
 *                        popData() + sndData.completed()
 *                                      ▼
 *                                 continueTask()
 * </pre>
 * <p><b>Responsibility boundary:</b>
 * <ul>
 *   <li>The base class owns the main send loop, exception normalization, queue finalization, and delayed retry scheduling.</li>
 *   <li>Subclasses decide whether the underlying channel is still usable and how prepared bytes are actually written to the transport.</li>
 *   <li>Subclasses can override {@link #wrapSendData(byte[])} when raw payload bytes need extra protocol wrapping.</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public abstract class AbstractUdpWriteTask extends DefaultSoTask {
    protected final SoContextService context;
    private final NetChannel         netChannel;
    private final NetMonitor         monitor;
    private final SoSndContext       wContext;
    private byte[]                   sendData;
    private int                      timeoutRetryCnt = 0;

    /**
     * Create a UDP write task.
     * @param netChannel the associated framework channel
     * @param wContext the send context
     * @param context the runtime context service
     */
    public AbstractUdpWriteTask(NetChannel netChannel, SoSndContext wContext, SoContextService context) {
        this.netChannel = netChannel;
        this.monitor = netChannel.getMonitor();
        this.wContext = wContext;
        this.context = context;
    }

    /**
     * Return the {@link NetChannel} associated with the current write task.
     * @return the associated framework channel
     */
    protected NetChannel getNetChannel() {
        return this.netChannel;
    }

    /**
     * Let subclasses decide whether the underlying transport channel is still open.
     * @return true when the channel is still usable
     */
    protected abstract boolean isChannelOpen();

    /**
     * Perform the actual send operation.
     * <p>The input data has already been processed by {@link #wrapSendData(byte[])}.
     * @param data the prepared byte array ready to send
     * @return the number of bytes written; returning 0 means the channel is temporarily not ready
     * and a delayed retry will follow
     * @throws IOException when a transport-level I/O error occurs
     */
    protected abstract int doSend(byte[] data) throws IOException;

    /**
     * Transform raw application bytes before sending.
     * <p>The default implementation performs no transformation and returns the original bytes.
     * Subclasses can override this method to add protocol-specific framing.
     * @param sendData the raw application payload
     * @return the byte array that can be sent directly by {@link #doSend(byte[])}
     */
    protected byte[] wrapSendData(byte[] sendData) {
        return sendData;
    }

    @Override
    protected void doWork(int retryCnt) {
        // Check whether the task can terminate immediately.
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

        // Prepare the current pending send data.
        if (this.sendData == null) {
            SoSndData sndData = this.wContext.peekData();
            byte[] bytes = sndData.transferPull();
            if (bytes != null) {
                this.sendData = wrapSendData(bytes);
            }
        }

        // Perform the send.
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

        // Try to finalize the current send item after its payload has been drained.
        if (this.sendData == null) {
            SoSndData sndData = this.wContext.peekData();
            if (!sndData.hasReadable()) {
                this.wContext.popData();
                this.submitTask(new SoDelayTask(0)).onFinal(f -> {
                    sndData.completed();
                });
            }
        }

        // Continue processing the remaining queue items.
        this.continueTask();
    }

    /**
     * Handle an exception raised during sending.
     * @param e the failure cause
     * @param wContext the send context
     * @return true if doWork should return immediately; false if cleanup and continuation should
     * still run
     */
    private boolean handleException(Throwable e, SoSndContext wContext) {
        long channelId = this.netChannel.getChannelId();

        if (e instanceof InterruptedByTimeoutException) {
            UdpSoConfig cfg = (UdpSoConfig) this.netChannel.getConfig();
            int maxRetry = cfg.getSndWriteRetryCount();
            if (maxRetry > 0 && this.timeoutRetryCnt < maxRetry) {
                this.timeoutRetryCnt++;
                this.delayTask(cfg.getSndWriteRetryIntervalMs(), TimeUnit.MILLISECONDS);
                return true; // Retry later after a delay.
            }
            // Retries are exhausted, or retry is disabled. Notify the timeout and discard the current packet.
            String retryInfo = maxRetry > 0 ? ", tried " + this.timeoutRetryCnt + " time(s)" : "";
            this.timeoutRetryCnt = 0;
            this.sendData = null;
            String errorMsg = "send data timeout with " + this.netChannel.getConfig().getSoWriteTimeoutMs() + " milliseconds" + retryInfo + ".";
            this.context.notifySndChannelException(channelId, false, new SoWriteTimeoutException(errorMsg));
            return false; // Fall through so try-finish can pop the discarded item.
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

    /**
     * Purge remaining queued send data and fail each item.
     * @param e the failure cause
     * @param wContext the send context
     */
    private void purgeSndData(Throwable e, SoSndContext wContext) {
        while (!wContext.isEmpty()) {
            SoSndData sndData = wContext.popData();
            this.submitTask(new SoDelayTask(0)).onFinal(f -> {
                sndData.failed(e);
            });
        }
    }

    /**
     * Submit an internal task to the SoTask scheduler.
     * @param task the task to submit
     * @return the task future
     */
    private Future<?> submitTask(DefaultSoTask task) {
        return this.context.submitSoTask(task, this);
    }
}
