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
import net.hasor.neta.bytebuf.ByteBuf;

import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.List;

/**
 * send Handler
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoSndCompletionHandler implements CompletionHandler<Integer, SoContextImpl> {
    private static final Logger          logger = Logger.getLogger(SoSndCompletionHandler.class);
    private final        long            channelID;
    private final        long            createdTime;
    private volatile     SoHandlerStatus status;
    //
    private final        SoAsyncChannel  channel;
    private final        SoSndContext    wContext;
    private final        SoContextImpl   context;
    private final        ByteBuffer      swapBuffer;
    private final        ByteBuf         sndBuffer;
    //
    private              int             sndSize;
    private              List<SoSndData> afterWorking;

    public SoSndCompletionHandler(long channelID, long createdTime, SoAsyncChannel channel, SoSndContext wContext) {
        this.channelID = channelID;
        this.createdTime = createdTime;
        this.status = SoHandlerStatus.IDLE;

        this.channel = channel;
        this.wContext = wContext;
        this.context = wContext.getContext();

        SoResManager rm = this.context.getResourceManager();
        this.swapBuffer = rm.newSwapSndBuf();
        this.sndBuffer = rm.newLocalSndBuf();
    }

    /**
     * Java AIO cannot use {@link ByteBuffer}, so use {@link ByteBuffer} for swap data.
     */
    public ByteBuffer getSwapBuffer() {
        return this.swapBuffer;
    }

    /**
     * Enhanced {@link ByteBuffer}.
     */
    public ByteBuf getSndBuffer() {
        return this.sndBuffer;
    }

    /** Returns this Handler status. */
    public SoHandlerStatus getStatus() {
        return this.status;
    }

    public void prepare(List<SoSndData> afterWorking) {
        this.status = SoHandlerStatus.PENDING;
        this.sndSize = 0;
        this.afterWorking = afterWorking;
        this.copyData();
        this.status = SoHandlerStatus.WAITING;
    }

    private Future<?> submitTask(DefaultSoTask task) {
        return this.context.submitSoTask(this.channelID, task, this);
    }

    @Override
    public void completed(Integer result, SoContextImpl context) {
        this.status = SoHandlerStatus.PENDING;

        if (logger.isDebugEnabled()) {
            logger.debug("snd(" + this.channelID + ") size:" + result);
        }

        this.sndSize += result;

        if (this.swapBuffer.hasRemaining()) {

            // continue send data.
            this.writeData();

        } else if (this.sndBuffer.hasReadable()) {

            // continue send data.
            this.copyData();
            this.writeData();
        } else {

            SoSndCleanTask cleanTask = new SoSndCleanTask(this.channelID, this.afterWorking, this.sndSize);
            SoSndTask sndTask = new SoSndTask(this.channelID, this.channel, this, this.wContext);

            submitTask(cleanTask).onCompleted(f -> submitTask(sndTask));
            this.status = SoHandlerStatus.IDLE;
        }
    }

    // copy data from sndBuf to swapBuf
    private void copyData() {
        this.swapBuffer.clear();
        this.sndBuffer.read(this.swapBuffer);
        this.sndBuffer.markReader();
        this.swapBuffer.flip();
    }

    private void writeData() {
        if (this.channel.isShutdownOutput()) {
            submitTask(new SoSndCleanTask(this.channelID, this.afterWorking, this.sndSize, SoOutputCloseException.INSTANCE));
            this.status = SoHandlerStatus.IDLE;
            return;
        }

        try {
            this.status = SoHandlerStatus.WAITING;
            boolean res = this.channel.write(this.swapBuffer, this.context, this);
            if (!res) {
                this.status = SoHandlerStatus.IDLE;
            }
        } catch (Throwable e) {
            handleException(e);
        }
    }

    @Override
    public void failed(Throwable e, SoContextImpl context) {
        this.handleException(e);
    }

    private void handleException(Throwable e) {
        String finalMsg;
        Throwable finalErr;

        if (e instanceof NotYetConnectedException) {
            long costTimeMs = System.currentTimeMillis() - this.createdTime;
            if (costTimeMs < this.context.getConnectTimeoutMs()) {
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
            String errorMsg = "send data timeout with " + this.context.getConfig().getSoWriteTimeoutMs() + " milliseconds.";
            String msg = "snd(" + this.channelID + ") " + errorMsg;

            finalErr = new SoWriteTimeoutException(errorMsg);
            finalMsg = msg;
        } else if (e instanceof ClosedChannelException) {
            if (this.channel.isShutdownOutput()) {
                this.context.notifySndChannelError(this.channelID, SoOutputCloseException.INSTANCE);
                this.status = SoHandlerStatus.IDLE;
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

        this.context.notifySndChannelError(this.channelID, e);
        this.context.asyncUnsafeCloseChannel(this.channelID, finalMsg, finalErr);
        submitTask(new SoSndCleanTask(this.channelID, this.afterWorking, this.sndSize, e));
        this.status = SoHandlerStatus.IDLE;
    }

}