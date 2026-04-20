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
package net.hasor.neta.channel.transport.tcp;
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
 * <p>Only one instance is created per channel. The send model is single-writer: the
 * {@code writing} {@link java.util.concurrent.atomic.AtomicBoolean} guarantees that at most one
 * outstanding {@code channel.write()} call exists at any time.
 * <p><b>Write flow:</b>
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
 *       │        ┌─────────────────┴────────────────┐
 *       │        │ swap buffer still has remaining? │ queue still has more data?
 *       │        ▼ yes → writeData()                ▼ yes → copyData() + writeData()
 *       │                                             no  → set writing=false and re-check
 *       ▼
 *   sndData.completed()  (called after one SoSndData is fully flushed)
 * </pre>
 * <p><b>Partial writes:</b> TCP may write fewer bytes than requested. When {@code sndSwapBuf}
 * still has remaining bytes after {@code completed()}, {@code writeData()} is invoked again
 * without re-reading from the queue.
 * <p><b>Re-check after idle:</b> when the queue appears empty and {@code writing} is reset to
 * {@code false}, a second CAS is executed to handle the race where a producer enqueues data
 * between the {@code isEmpty()} check and the {@code set(false)} call.
 * <p><b>Error handling:</b>
 * <ul>
 *   <li>{@link java.nio.channels.NotYetConnectedException}: retry after a delay if still within
 *       the {@code connectTimeoutMs} window; otherwise raise {@link SoConnectTimeoutException}
 *       and fail all pending {@link SoSndData}.</li>
 *   <li>{@link java.nio.channels.InterruptedByTimeoutException}: write timeout
 *       ({@code soWriteTimeoutMs}) expired, so a {@link SoWriteTimeoutException} is raised and
 *       sending is retried after a short {@link SoDelayTask}.</li>
 *   <li>{@link java.nio.channels.ClosedChannelException} /
 *       {@link java.nio.channels.ShutdownChannelGroupException}: drain all queued
 *       {@link SoSndData} and fail them with {@link SoCloseException}.</li>
 *   <li>Any other {@link Throwable}: wrap it as {@link SoSndException} and apply the same drain
 *       strategy.</li>
 * </ul>
 * <p><b>Buffer management:</b> {@code sndSwapBuf} is a JVM direct
 * {@link java.nio.ByteBuffer} allocated once at construction time and released through
 * {@link net.hasor.neta.bytebuf.ByteBufUtils#CLEANER} in {@link #close()}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see TcpAsyncChannel
 * @see SoSndContext
 * @see SoSndData
 * @see SoWriteTimeoutException
 * @see SoUnfinishedSndException
 */
class TcpSndCompletionHandler implements CompletionHandler<Integer, SoSndContext>, Closeable {
    private static final Logger    logger = Logger.getLogger(TcpSndCompletionHandler.class);
    private final long             channelId;
    private final TcpAsyncChannel  channel;
    private final SoContextService context;
    private final NetMonitor       monitor;
    //
    private final AtomicBoolean    writing;
    private final Integer          wTimeoutMs;
    private final ByteBufAllocator allocator;
    private final ByteBuffer       sndSwapBuf;
    private final int              connectTimeoutMs;

    /**
     * Create a TCP write-completion handler.
     * @param channel the underlying asynchronous channel
     * @param context the runtime context
     * @param monitor the channel metrics monitor
     */
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

    /**
     * Trigger one send flow.
     * @param wContext the send context
     */
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
        // Copy the data from the send buffer into the swap buffer.
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

    /**
     * Continue the write flow after the underlying write succeeds.
     * @param result the number of bytes actually written this time
     * @param wContext the send context
     */
    @Override
    public void completed(Integer result, SoSndContext wContext) {
        if (this.context.getConfig().isPrintLog()) {
            logger.info("snd(" + this.channelId + ") [TCP-WRITE] bytes=" + result);
        }

        // Complete the current sndData inline after it has been fully sent.
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
            // Re-check: data may have arrived between isEmpty() and set(false).
            if (!wContext.isEmpty() && this.writing.compareAndSet(false, true)) {
                this.copyData(wContext);
                this.writeData(wContext);
            }
        }
    }

    /**
     * Enter the exception-handling flow when the underlying write fails.
     * @param e the failure cause
     * @param context the send context
     */
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

    /**
     * Close the write handler and release the swap buffer.
     * @throws IOException if an I/O error occurs while closing
     */
    @Override
    public void close() throws IOException {
        ByteBufUtils.CLEANER.freeDirectBuffer(this.sndSwapBuf);
    }
}