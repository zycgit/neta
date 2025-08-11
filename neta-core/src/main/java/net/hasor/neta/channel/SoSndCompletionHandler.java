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
package net.hasor.neta.channel;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBufAllocator;

import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * send Handler
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class SoSndCompletionHandler implements CompletionHandler<Integer, SoContextService> {
    private static final Logger                           logger = Logger.getLogger(SoSndCompletionHandler.class);
    private final        long                             channelID;
    private final        long                             createdTime;
    private final        AtomicReference<SoHandlerStatus> status;
    private final        AtomicLong                       counterBytes;
    private final        int                              connectTimeoutMs;
    //
    private final        SoAsyncChannel                   channel;
    private final        SoSndContext                     sndContext;
    private final        SoContextService                 context;
    private final        boolean                          usingSndSwapBuffer;
    private final        ByteBuffer                       sndSwapBuf;

    public SoSndCompletionHandler(long channelID, long createdTime, SoAsyncChannel channel, SoSndContext sndContext) {
        this.channelID = channelID;
        this.createdTime = createdTime;
        this.status = new AtomicReference<>(SoHandlerStatus.IDLE);
        this.counterBytes = new AtomicLong();
        this.connectTimeoutMs = Math.max(10, channel.getSoConfig().getConnectTimeoutMs());

        this.channel = channel;
        this.sndContext = sndContext;
        this.context = sndContext.getContext();
        this.usingSndSwapBuffer = this.channel.usingSndSwapBuffer();

        ByteBufAllocator allocator = this.context.getByteBufAllocator();
        this.sndSwapBuf = this.usingSndSwapBuffer ? allocator.jvmBuffer(this.channel.getSoConfig().getSoSndBuf()) : null;
    }

    public boolean tryLock() {
        return this.status.compareAndSet(SoHandlerStatus.IDLE, SoHandlerStatus.PENDING);
    }

    public void freeLock() {
        this.status.set(SoHandlerStatus.IDLE);
    }

    /** Returns this Handler status. */
    public SoHandlerStatus getStatus() {
        return this.status.get();
    }

    /** Gets the number of bytes that have been sent. */
    public long getCounterBytes() {
        return this.counterBytes.get();
    }

    public void doWrite(Runnable finishCallBack) {
        if (this.sndContext.isEmpty()) {
            finishCallBack.run();
            return;
        }

        this.submitTask(new SoDelayTask(0)).onFinal(f -> {
            if (this.usingSndSwapBuffer) {
                // for Stream TCP
                this.copyData();
                this.writeData();
            } else {
                // for Packet UDP
                this.sendData();
            }
        });
    }

    private void copyData() {
        // copy data from sndBuf to swapBuf
        SoSndData sndData = this.sndContext.peekData();
        this.sndSwapBuf.clear();
        sndData.transferTo(this.sndSwapBuf);
        this.sndSwapBuf.flip();

        // when sndData finish, use async task to completed.
        if (!sndData.hasReadable()) {
            this.sndContext.popData();
            this.submitTask(new SoDelayTask(0)).onFinal(f -> {
                sndData.completed();
            });
        }
    }

    private void writeData() {
        try {
            this.status.set(SoHandlerStatus.WAITING);
            if (!this.channel.write(this.sndSwapBuf, this.context, this)) {
                this.status.set(SoHandlerStatus.IDLE);
            }
        } catch (Throwable e) {
            handleException(e);
        }
    }

    @Override
    public void completed(Integer result, SoContextService context) {
        this.status.set(SoHandlerStatus.PENDING);

        if (logger.isDebugEnabled()) {
            logger.debug("snd(" + this.channelID + ") size:" + result);
        }

        this.counterBytes.addAndGet(result);

        if (this.sndSwapBuf.hasRemaining()) {
            this.writeData(); // continue send data.
        } else if (this.sndContext.hasData()) {
            this.copyData();
            this.writeData();
        } else {
            this.status.set(SoHandlerStatus.IDLE);
        }
    }

    private void sendData() {
        if (this.sndContext.hasData()) {
            SoSndData sndData = this.sndContext.peekData();

            try {
                this.status.set(SoHandlerStatus.WAITING);

                ByteBuffer data = sndData.transferPull();
                if (!this.channel.write(data, this.context, this)) {
                    this.status.set(SoHandlerStatus.IDLE);
                }
            } catch (Throwable e) {
                handleException(e);
            }

            if (!sndData.hasReadable()) {
                this.sndContext.popData();
                this.submitTask(new SoDelayTask(0)).onFinal(f -> {
                    sndData.completed();
                });
            }

            //
            this.submitTask(new SoDelayTask(0)).onFinal(f -> {
                this.sendData(); // recursive send.
            });
        } else {
            this.status.set(SoHandlerStatus.IDLE);
        }
    }

    @Override
    public void failed(Throwable e, SoContextService context) {
        this.handleException(e);
    }

    private void handleException(Throwable e) {
        String finalMsg;
        Throwable finalErr;

        if (e instanceof NotYetConnectedException) {
            long costTimeMs = System.currentTimeMillis() - this.createdTime;
            if (costTimeMs < this.connectTimeoutMs) {
                if (logger.isDebugEnabled()) {
                    logger.debug("snd(" + this.channelID + ") NotYetConnected, write try again later.");
                }
                submitTask(new SoDelayTask(this.context)).onCompleted(f -> {
                    writeData();
                });
                return;
            } else {
                finalErr = SoUtils.newTimeout(false, this.channelID, this.context, e);
                finalMsg = finalErr.getMessage();
            }
        } else if (e instanceof InterruptedByTimeoutException) {
            String errorMsg = "send data timeout with " + this.channel.getSoConfig().getSoWriteTimeoutMs() + " milliseconds.";
            String msg = "snd(" + this.channelID + ") " + errorMsg;

            finalErr = new SoWriteTimeoutException(errorMsg);
            finalMsg = msg;
        } else if (e instanceof ClosedChannelException) {
            if (this.channel.isShutdownOutput()) {
                this.context.notifySndChannelError(this.channelID, SoOutputCloseException.INSTANCE);
                this.status.set(SoHandlerStatus.IDLE);
                return;
            } else {
                finalMsg = "snd(" + this.channelID + ") close, msg:" + e.getMessage();
                finalErr = e;
            }
        } else if (e instanceof ShutdownChannelGroupException) {
            finalMsg = "snd(" + this.channelID + ") shutdown, msg:" + e.getMessage();
            finalErr = e;
        } else {
            finalMsg = "snd(" + this.channelID + ") error, msg:" + e.getMessage();
            finalErr = e;
        }

        try {
            this.channel.shutdownOutput();
        } catch (Exception ee) {
            logger.warn("snd(" + this.channelID + ") other errors occur in error handling, " + ee.getMessage());
        }

        this.context.notifySndChannelError(this.channelID, finalErr);
        this.context.asyncUnsafeCloseChannel(this.channelID, finalMsg, finalErr);

        while (!this.sndContext.isEmpty()) {
            SoSndData sndData = this.sndContext.popData();
            this.submitTask(new SoDelayTask(0)).onFinal(f -> {
                sndData.failed(e);
            });
        }

        this.status.set(SoHandlerStatus.IDLE);
    }

    private Future<?> submitTask(DefaultSoTask task) {
        return this.context.submitSoTask(task, this);
    }
}