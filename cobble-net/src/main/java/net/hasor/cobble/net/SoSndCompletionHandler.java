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
package net.hasor.cobble.net;
import net.hasor.cobble.bytebuf.ByteBuf;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;

import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 负责发送数据
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoSndCompletionHandler implements CompletionHandler<Integer, SoContextImpl> {
    private static final Logger                    logger = Logger.getLogger(SoSndCompletionHandler.class);
    private final        long                      channelID;
    private final        long                      createdTime;
    private final        AsynchronousSocketChannel channel;
    private final        SoContextImpl             context;
    private final        SoResManager              rm;
    private final        ByteBuffer                swapBuffer;
    private final        ByteBuf                   sndBuffer;
    //
    private              int                       sndSize;
    private              boolean                   sndWorking;
    private              List<SoSndData>           afterWorking1;

    public SoSndCompletionHandler(long channelID, long createdTime, AsynchronousSocketChannel channel, SoContextImpl context, SoResManager rm) {
        this.channelID = channelID;
        this.createdTime = createdTime;
        this.channel = channel;
        this.context = context;
        this.rm = rm;
        this.swapBuffer = rm.newSwapSndBuf();
        this.sndBuffer = rm.newLocalSndBuf();
    }

    public ByteBuffer getSwapBuffer() {
        return this.swapBuffer;
    }

    public ByteBuf getSndBuffer() {
        return this.sndBuffer;
    }

    public boolean isSndWorking() {
        return this.sndWorking;
    }

    public void prepareWrite(List<SoSndData> afterWorking1) {
        this.sndSize = 0;
        this.sndWorking = true;
        this.afterWorking1 = afterWorking1;
    }

    private Future<?> submitTask(AbstractSoTask task) {
        return this.context.submitSoTask(this.rm, task, this);
    }

    @Override
    public void completed(Integer result, SoContextImpl context) {
        logger.debug("snd(" + this.channelID + ") size:" + result);

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
                        logger.debug("snd(" + this.channelID + ") NotYetConnected, read try again later.");
                    }
                    submitTask(new SoDelayTask(this.context)).onCompleted(f -> {
                        writeData();
                    });
                    return;
                } else {
                    logger.warn("snd(" + this.channelID + ") Connection timeout.");
                    this.context.closeChannel(this.channelID, e.getMessage());
                }
            } else {
                logger.error("snd(" + this.channelID + ") " + e.getMessage(), e);
                this.context.closeChannel(this.channelID, e.getMessage());
            }

            submitTask(new SoSndCleanTask(this.channelID, this.afterWorking1, this.sndSize, e));
        }
    }

    @Override
    public void failed(Throwable e, SoContextImpl context) {
        if (e instanceof InterruptedByTimeoutException) {
            // rcv Close
            logger.error("snd(" + this.channelID + ") writeTimeout, msg:" + e.getMessage());
            context.closeChannel(this.channelID, e.getMessage());

        } else if (e instanceof ShutdownChannelGroupException) {

            // rcv Close
            logger.error("snd(" + this.channelID + ") shutdown, msg:" + e.getMessage());
            context.closeChannel(this.channelID, e.getMessage());
        } else if (e instanceof AsynchronousCloseException) {

            // rcv Close
            logger.error("snd(" + this.channelID + ") close, msg:" + e.getMessage());
            context.closeChannel(this.channelID, e.getMessage());
        } else {

            // rcv Exception
            logger.error("snd(" + this.channelID + ") error, msg:" + e.getMessage(), e);
            context.closeChannel(this.channelID, e.getMessage());
        }

        submitTask(new SoSndCleanTask(this.channelID, this.afterWorking1, this.sndSize, e));
    }
}
