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
import java.util.concurrent.TimeUnit;

/**
 * send Handler
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoSndCompletionHandler implements CompletionHandler<Integer, SoContextImpl> {
    private static final Logger                    logger = Logger.getLogger(SoSndCompletionHandler.class);
    private final        long                      channelID;
    private final        long                      createdTime;
    private final        AsynchronousSocketChannel channel;
    private final        SoContextImpl             context;
    private final        ByteBuffer                swapBuffer;
    private final        ByteBuf                   sndBuffer;
    //
    private              int                       sndSize;
    private volatile     boolean                   sndWorking;
    private              List<SoSndData>           afterWorking1;

    public SoSndCompletionHandler(long channelID, long createdTime, AsynchronousSocketChannel channel, SoContextImpl context) {
        this.channelID = channelID;
        this.createdTime = createdTime;
        this.channel = channel;
        this.context = context;

        SoResManager rm = context.getResourceManager();
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

    /**
     * The data in ByteBuf is sent in batches, before it is completed {@link #isSndWorking()} Always true.
     */
    public boolean isSndWorking() {
        return this.sndWorking;
    }

    public void prepareWrite(List<SoSndData> afterWorking1) {
        this.sndSize = 0;
        this.sndWorking = true;
        this.afterWorking1 = afterWorking1;
    }

    private Future<?> submitTask(DefaultSoTask task) {
        return this.context.submitSoTask(this.channelID, task, this);
    }

    @Override
    public void completed(Integer result, SoContextImpl context) {
        if (logger.isDebugEnabled()) {
            logger.debug("snd(" + this.channelID + ") size:" + result);
        }

        this.sndSize += result;

        if (this.swapBuffer.hasRemaining()) {

            // continue send data.
            this.writeData();

        } else if (this.sndBuffer.hasReadable()) {

            // reset swap, and copy sndData to swap
            this.swapBuffer.clear();
            this.sndBuffer.read(this.swapBuffer);
            this.sndBuffer.markReader();
            this.swapBuffer.flip();

            // continue send data.
            this.writeData();
        } else {
            submitTask(new SoSndCleanTask(this.channelID, this.afterWorking1, this.sndSize));
            this.sndWorking = false;
        }
    }

    private void writeData() {
        if (this.context.isClose(this.channelID)) {
            submitTask(new SoSndCleanTask(this.channelID, this.afterWorking1, this.sndSize, new ClosedChannelException()));
            return;
        }

        try {
            Integer wTimeoutMs = this.context.getConfig().getSoWriteTimeoutMs();
            if (wTimeoutMs != null && wTimeoutMs > 0) {
                this.channel.write(this.swapBuffer, wTimeoutMs, TimeUnit.MILLISECONDS, this.context, this);
            } else {
                this.channel.write(this.swapBuffer, this.context, this);
            }
        } catch (Throwable e) {
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
                    SoConnectTimeoutException cause = SoUtils.newTimeout(false, this.channelID, this.context, e);

                    this.context.notifyChannelError(this.channelID, cause);
                    this.context.unsafeCloseChannel(this.channelID, cause.getMessage(), cause);
                }
            } else {
                String msg = "snd(" + this.channelID + ") " + e.getMessage();

                this.context.notifyChannelError(this.channelID, e);
                this.context.unsafeCloseChannel(this.channelID, msg, e);
            }

            submitTask(new SoSndCleanTask(this.channelID, this.afterWorking1, this.sndSize, e));
        }
    }

    @Override
    public void failed(Throwable e, SoContextImpl context) {
        String errorMsg;
        Throwable cause = e;

        if (e instanceof InterruptedByTimeoutException) {
            // snd Close
            errorMsg = "snd(" + this.channelID + ") writeTimeout, msg:" + e.getMessage();
            cause = new SoWriteTimeoutException(errorMsg);
        } else if (e instanceof ShutdownChannelGroupException) {
            // snd Close
            errorMsg = "snd(" + this.channelID + ") shutdown, msg:" + e.getMessage();
        } else if (e instanceof AsynchronousCloseException) {
            // snd Close
            errorMsg = "snd(" + this.channelID + ") close, msg:" + e.getMessage();
        } else {
            // snd Exception
            errorMsg = "snd(" + this.channelID + ") error, msg:" + e.getMessage();
        }

        context.notifyChannelError(this.channelID, cause);
        context.unsafeCloseChannel(this.channelID, errorMsg, cause);

        submitTask(new SoSndCleanTask(this.channelID, this.afterWorking1, this.sndSize, e));
    }
}