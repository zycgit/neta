/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
/**
 * Connection-level send task used when QUIC runs in {@link QuicChannelMode#CHANNEL}.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicMessageWriteTask extends DefaultSoTask {
    private final NetChannel       netChannel;
    private final QuicChannelAsync quicChannel;
    private final SoSndContext     wContext;
    private final SoContextService context;
    private SoSndData              currentData;
    private QuicMessage            currentMessage;

    QuicMessageWriteTask(NetChannel netChannel, QuicChannelAsync quicChannel, SoSndContext wContext, SoContextService context) {
        this.netChannel = netChannel;
        this.quicChannel = quicChannel;
        this.wContext = wContext;
        this.context = context;
    }

    @Override
    protected void doWork(int retryCnt) {
        if (this.wContext.isEmpty()) {
            this.finishTask();
            return;
        }
        if (!this.quicChannel.isOpen()) {
            SoUnfinishedSndException err = new SoUnfinishedSndException("channel is closed.");
            this.context.notifySndChannelException(this.netChannel.getChannelId(), true, err);
            this.wContext.purge(err);
            this.finishTask();
            return;
        }

        if (this.currentMessage == null) {
            this.currentData = this.wContext.peekData();
            Object rawData = this.currentData.transferTake();
            if (!(rawData instanceof QuicMessage)) {
                SoSndException err = new SoSndException("QUIC message mux mode requires QuicMessage outbound data, but got " + (rawData == null ? "null" : rawData.getClass().getName()));
                this.context.notifySndChannelException(this.netChannel.getChannelId(), false, err);
                this.wContext.popData();
                SoSndData failedData = this.currentData;
                this.currentData = null;
                this.submitTask(new SoDelayTask(0)).onFinal(f -> failedData.failed(err));
                this.continueTask();
                return;
            }
            this.currentMessage = (QuicMessage) rawData;
        }

        try {
            QuicChannelAsync.PreparedQuicMessage prepared = this.quicChannel.prepareMessageWrite(this.currentMessage);
            int write = this.quicChannel.sendDataFrame(ByteBuf.wrap(prepared.getFrame()), null);
            if (write <= 0) {
                SoSndException err = new SoSndException("Failed to send QUIC message on stream " + this.currentMessage.streamId());
                this.context.notifySndChannelException(this.netChannel.getChannelId(), false, err);
                SoSndData failedData = this.wContext.popData();
                this.currentData = null;
                this.currentMessage = null;
                this.submitTask(new SoDelayTask(0)).onFinal(f -> failedData.failed(err));
                this.finishTask();
                return;
            }

            this.netChannel.getMonitor().updateSndCounter(write);
            prepared.onSent();

            SoSndData completedData = this.wContext.popData();
            this.currentData = null;
            this.currentMessage = null;
            this.submitTask(new SoDelayTask(0)).onFinal(f -> completedData.completed());
            this.continueTask();
        } catch (Throwable e) {
            SoSndException err = e instanceof SoSndException ? (SoSndException) e : new SoSndException(e.getMessage(), e);
            this.context.notifySndChannelException(this.netChannel.getChannelId(), false, err);
            SoSndData failedData = this.wContext.popData();
            this.currentData = null;
            this.currentMessage = null;
            this.submitTask(new SoDelayTask(0)).onFinal(f -> failedData.failed(err));
            this.finishTask();
        }
    }

    private Future<?> submitTask(DefaultSoTask task) {
        return this.context.submitSoTask(task, this);
    }
}
