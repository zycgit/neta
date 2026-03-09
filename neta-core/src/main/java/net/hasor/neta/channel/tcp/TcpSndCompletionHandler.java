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
import java.io.Closeable;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;

/**
 * AIO {@link java.nio.channels.CompletionHandler} that drives the TCP send loop.
 * <p>An instance is created once per channel.  The send model is
 * <em>single-writer</em>: the {@code writing} {@link java.util.concurrent.atomic.AtomicBoolean}
 * ensures that at most one outstanding {@code channel.write()} call exists at any time.
 * <p><b>Write pipeline:</b>
 * <pre>
 *   SoSndContext (queue)
 *       │  peekData()
 *       ▼
 *   SoSndData  ──transferTo──▶ sndSwapBuf (direct ByteBuffer)
 *       │                          │
 *       │              channel.write(sndSwapBuf, ...)
 *       │                          │
 *       │                 completed(bytesWritten, ctx)
 *       │                          │
 *       │        ┌─────────────────┴───────────────┐
 *       │        │ swapBuf still has remaining?     │ queue has more data?
 *       │        ▼ yes → writeData()                ▼ yes → copyData() + writeData()
 *       │                                          else → set writing=false, re-check
 *       ▼
 *   sndData.completed()  (when all bytes of one SoSndData are flushed)
 * </pre>
 * <p><b>Partial writes:</b> TCP may write fewer bytes than requested.  When {@code sndSwapBuf}
 * still has remaining bytes after {@code completed()}, {@code writeData()} is called again
 * without re-fetching from the queue (no re-copy needed).
 * <p><b>Re-check after idle:</b> When the queue appears empty and {@code writing} is set back
 * to {@code false}, a second CAS is performed to handle the race where a producer enqueued
 * data between the {@code isEmpty()} check and the {@code set(false)} call.
 * <p><b>Error cases:</b>
 * <ul>
 *   <li>{@link java.nio.channels.NotYetConnectedException}: retried with a delay if within
 *       {@code connectTimeoutMs}; otherwise a {@link SoConnectTimeoutException} is raised
 *       and all pending {@link SoSndData} are failed.</li>
 *   <li>{@link java.nio.channels.InterruptedByTimeoutException}: write timeout
 *       ({@code soWriteTimeoutMs}) expired — a {@link SoWriteTimeoutException} is
 *       raised but the write is retried after a short {@link SoDelayTask}.</li>
 *   <li>{@link java.nio.channels.ClosedChannelException} /
 *       {@link java.nio.channels.ShutdownChannelGroupException}: all queued
 *       {@link SoSndData} are drained and completed with a {@link SoCloseException}.</li>
 *   <li>Any other {@link Throwable}: wrapped in {@link SoSndException}; same drain.
 * </ul>
 * <p><b>Buffer management:</b> {@code sndSwapBuf} is a JVM direct {@link java.nio.ByteBuffer}
 * allocated once at construction. It is released via
 * {@link net.hasor.neta.bytebuf.ByteBufUtils#CLEANER} on {@link #close()}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see TcpAsyncChannel
 * @see SoSndContext
 * @see SoSndData
 * @see SoWriteTimeoutException
 * @see SoUnfinishedSndException
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
            this.copyData(wContext);
            this.writeData(wContext);
        }
    }

    private void copyData(SoSndContext wContext) {
        // copy data from sndBuf to swapBuf
        SoSndData sndData = wContext.peekData();
        ((Buffer) this.sndSwapBuf).clear();
        sndData.transferTo(this.sndSwapBuf);
        ((Buffer) this.sndSwapBuf).flip();
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
        if (this.context.getConfig().isPrintLog()) {
            logger.info("snd(" + this.channelId + ") [TCP-WRITE] bytes=" + result);
        }

        // when sndData finish, complete inline (no thread dispatch needed)
        SoSndData sndData = wContext.peekData();
        if (!sndData.hasReadable()) {
            wContext.popData();
            sndData.completed();
        }

        this.monitor.updateSndCounter(result);

        if (this.sndSwapBuf.hasRemaining()) {
            this.writeData(wContext);
        } else if (!wContext.isEmpty()) {
            this.copyData(wContext);
            this.writeData(wContext);
        } else {
            this.writing.set(false);
            // re-check: data may have arrived between isEmpty() and set(false)
            if (!wContext.isEmpty() && this.writing.compareAndSet(false, true)) {
                this.copyData(wContext);
                this.writeData(wContext);
            }
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
            long costTimeMs = System.currentTimeMillis() - this.monitor.getCreatedTime();
            if (costTimeMs < this.connectTimeoutMs) {
                if (this.context.getConfig().isPrintLog()) {
                    logger.info("snd(" + this.channelId + ") NotYetConnected, write try again later.");
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
            sndData.failed(e);
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