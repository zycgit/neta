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
package net.hasor.neta.channel.tcp;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * send Handler
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class TcpSndCompletionHandler implements CompletionHandler<Integer, SoSndContext>, Closeable {
    private static final Logger           logger = Logger.getLogger(TcpSndCompletionHandler.class);
    private final        long             channelId;
    private final        TcpAsyncChannel  channel;
    private final        SoContextService context;
    private final        NetMonitor       monitor;
    //
    private final        AtomicBoolean    writing;
    private final        Integer          wTimeoutMs;
    private final        ByteBufAllocator allocator;
    private final        ByteBuffer       sndSwapBuf;
    private final        int              connectTimeoutMs;

    public TcpSndCompletionHandler(TcpAsyncChannel channel, SoContext context, NetMonitor monitor) {
        this.channelId = channel.getChannelId();
        this.channel = channel;
        this.context = (SoContextService) context;
        this.monitor = monitor;

        this.writing = new AtomicBoolean(false);
        this.wTimeoutMs = channel.getSoConfig().getSoWriteTimeoutMs();
        this.allocator = context.getByteBufAllocator();
        this.sndSwapBuf = this.allocator.jvmBuffer(channel.getSoConfig().getSwapSndBuf());
        this.connectTimeoutMs = Math.max(10, channel.getSoConfig().getConnectTimeoutMs());
    }

    public void doWrite(SoSndContext wContext) {
        if (wContext.isEmpty()) {
            return;
        }

        if (this.writing.compareAndSet(false, true)) {
            this.submitTask(new SoDelayTask(0)).onFinal(f -> {
                this.copyData(wContext);
                this.writeData(wContext);
            });
        }
    }

    private void copyData(SoSndContext wContext) {
        // copy data from sndBuf to swapBuf
        SoSndData sndData = wContext.peekData();
        this.sndSwapBuf.clear();
        sndData.transferTo(this.sndSwapBuf);
        this.sndSwapBuf.flip();
    }

    private void writeData(SoSndContext wContext) {
        try {
            long timeout = this.wTimeoutMs != null && this.wTimeoutMs > 0 ? this.wTimeoutMs : 0L;
            this.channel.write(this.sndSwapBuf, wContext, this, timeout, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            handleException(e, wContext);
        }
    }

    @Override
    public void completed(Integer result, SoSndContext wContext) {
        if (logger.isDebugEnabled()) {
            logger.debug("snd(" + this.channelId + ") size:" + result);
        }

        // when sndData finish, use async task to completed.
        SoSndData sndData = wContext.peekData();
        if (!sndData.hasReadable()) {
            wContext.popData();
            this.submitTask(new SoDelayTask(0)).onFinal(f -> {
                sndData.completed();
            });
        }

        this.monitor.updateSndCounter(result);

        if (this.sndSwapBuf.hasRemaining()) {
            this.writeData(wContext);
        } else if (!wContext.isEmpty()) {
            this.copyData(wContext);
            this.writeData(wContext);
        } else {
            this.writing.set(false);
        }
    }

    @Override
    public void failed(Throwable e, SoSndContext context) {
        this.handleException(e, context);
    }

    private void doSendAgain(SoSndContext context) {
        if (!this.channel.isOpen()) {
            SoUnfinishedSndException finalErr = new SoUnfinishedSndException("channel is closed.");
            this.context.notifySndChannelException(this.channel.getChannelId(), true, finalErr);
            this.purgeSndData(finalErr, context);
        } else {
            submitTask(new SoDelayTask(this.context)).onCompleted(f -> {
                writeData(context);
            });
        }
    }

    private void handleException(Throwable e, SoSndContext context) {
        if (e instanceof NotYetConnectedException) {
            long costTimeMs = System.currentTimeMillis() - -this.monitor.getCreatedTime();
            if (costTimeMs < this.connectTimeoutMs) {
                if (logger.isDebugEnabled()) {
                    logger.debug("snd(" + this.channelId + ") NotYetConnected, write try again later.");
                }
                doSendAgain(context);
            } else {
                SoConnectTimeoutException finalErr = SoUtils.newConnectTimeout(false, this.channelId, this.context, e);
                this.context.notifySndChannelException(this.channel.getChannelId(), true, finalErr);
                this.purgeSndData(finalErr, context);
            }
            return;
        }

        if (e instanceof InterruptedByTimeoutException) {
            String errorMsg = "send data timeout with " + this.channel.getSoConfig().getSoWriteTimeoutMs() + " milliseconds.";
            SoException finalErr = new SoWriteTimeoutException(errorMsg);
            this.context.notifySndChannelException(this.channel.getChannelId(), false, finalErr);
            doSendAgain(context);
            return;
        }

        SoException finalErr;
        if (e instanceof ClosedChannelException || e instanceof ShutdownChannelGroupException) {
            finalErr = new SoCloseException(e.getMessage(), e);
        } else {
            finalErr = new SoSndException(e.getMessage(), e);
        }

        this.context.notifySndChannelException(this.channel.getChannelId(), true, finalErr);
        this.purgeSndData(finalErr, context);
    }

    private void purgeSndData(Throwable e, SoSndContext context) {
        while (!context.isEmpty()) {
            SoSndData sndData = context.popData();
            this.submitTask(new SoDelayTask(0)).onFinal(f -> {
                sndData.failed(e);
            });
        }
    }

    private Future<?> submitTask(DefaultSoTask task) {
        return this.context.submitSoTask(task, this);
    }

    @Override
    public void close() throws IOException {
        ByteBufUtils.CLEANER.freeDirectBuffer(this.sndSwapBuf);
    }
}