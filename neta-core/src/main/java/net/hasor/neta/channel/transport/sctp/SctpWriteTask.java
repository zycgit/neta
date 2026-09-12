/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.sctp;
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
 * Task that synchronously sends SCTP messages on a framework task thread and drains the
 * {@link SoSndContext} queue for a single channel.
 * <p>Unlike TCP, every SCTP message carries its own {@link com.sun.nio.sctp.MessageInfo} metadata,
 * including stream ID, sequence information, PPID, and unordered-delivery flags. The framework
 * packages that metadata together with the payload as an {@link SctpMessage}.
 * <p><b>Send loop (following the {@link #doWork(int)} contract inherited from
 * {@link DefaultSoTask}):</b>
 * <pre>
 *   SoSndContext (queue)
 *       │  peekData()
 *       ▼
 *   SoSndData.transferTake() ──▶ SctpMessage (MessageInfo + ByteBuf)
 *       │
 *       ▼
 *   ByteBuf → sndSwapBuf (heap ByteBuffer that expands when needed)
 *       │
 *       ▼
 *   SctpChannel.send(sndSwapBuf, messageInfo)
 *       ├──▶ write > 0: advance the queue and continue if more data exists
 *       ├──▶ write == 0: send buffer is full, retry after delayTask(50ms)
 *       └──▶ exception: handleException() performs retry or cleanup
 * </pre>
 * <p><b>Type handling:</b> when the application writes a raw
 * {@link net.hasor.neta.bytebuf.ByteBuf} instead of an {@link SctpMessage}, the framework wraps it
 * automatically as an {@code SctpMessage} that uses the default outgoing {@code MessageInfo}
 * (stream 0).
 * <p><b>Exception strategy:</b>
 * a send timeout ({@link java.nio.channels.InterruptedByTimeoutException}) triggers retries when {@code sndWriteRetryCount > 0};
 * a closed channel ({@link java.nio.channels.ClosedChannelException}) clears the remaining send queue through {@link net.hasor.neta.channel.SoUnfinishedSndException}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SctpMessage
 * @see SoSndContext
 * @see net.hasor.neta.channel.DefaultSoTask
 */
class SctpWriteTask extends DefaultSoTask {
    protected final SoContextService context;
    private final NetChannel         netChannel;
    private final NetMonitor         monitor;
    private final SctpChannel        channel;
    private final SoSndContext       wContext;
    //
    private SctpMessage sendData;
    private ByteBuffer  sndSwapBuf;
    private int         timeoutRetryCnt = 0;

    /**
     * Create an SCTP send task.
     * @param netChannel the framework channel
     * @param channel the underlying SCTP channel
     * @param wContext the send context
     * @param context the runtime context service
     */
    public SctpWriteTask(NetChannel netChannel, SctpChannel channel, SoSndContext wContext, SoContextService context) {
        this.netChannel = netChannel;
        this.monitor = netChannel.getMonitor();
        this.channel = channel;
        this.wContext = wContext;
        this.context = context;
        this.sndSwapBuf = ByteBuffer.allocate(SctpSoConfigUtils.getSndPacketSize((SctpSoConfig) netChannel.getConfig()));
    }

    /**
     * Execute one send step.
     * <p>This method prepares the current outbound message, writes it to the underlying channel,
     * and finishes or reschedules the task when appropriate.
     * @param retryCnt the current retry counter of this task
     */
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
     * Handle an exception thrown during the send phase.
     * @param e the captured exception
     * @param wContext the send context
     * @return true if the current doWork call should stop immediately, or false to continue with
     * the remaining cleanup logic
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
