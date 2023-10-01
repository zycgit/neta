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
import net.hasor.cobble.logging.Logger;

import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * swapBuffer -> socket
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoSndCompletionHandler implements CompletionHandler<Integer, SocketContext> {
    private static final Logger                    logger = Logger.getLogger(SoSndCompletionHandler.class);
    private final        long                      channelID;
    private final        long                      beginTime;
    private final        AsynchronousSocketChannel channel;
    private final        SocketContext             context;
    private final        ByteBuffer                swapBuffer;
    private final        ByteBuf                   sndBuffer;
    //
    private              int                       sndSize;
    private              boolean                   sndWorking;
    private              List<SoSndData>           afterWorking1;
    private              Runnable                  afterWorking2;

    public SoSndCompletionHandler(long channelID, long beginTime, AsynchronousSocketChannel channel, SocketContext context) {
        this.channelID = channelID;
        this.beginTime = beginTime;
        this.channel = channel;
        this.context = context;
        this.swapBuffer = context.newSwapSndBuf();
        this.sndBuffer = context.newLocalSndBuf();
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

    public void prepareWrite(List<SoSndData> afterWorking1, Runnable afterWorking2) {
        this.sndSize = 0;
        this.sndWorking = true;
        this.afterWorking1 = afterWorking1;
        this.afterWorking2 = afterWorking2;
    }

    @Override
    public void completed(Integer result, SocketContext context) {
        logger.debug("sndChannel(" + this.channelID + ") size:" + result);

        this.sndSize += result;

        if (this.swapBuffer.hasRemaining()) {

            // continue send data.
            this.writeData();

        } else if (this.sndBuffer.hasReadable()) {

            // reset swap, and copy sndData to swap
            this.swapBuffer.clear();
            this.sndBuffer.read(this.swapBuffer);
            this.swapBuffer.flip();

            // continue send data.
            this.writeData();

        } else {
            this.context.submitSoTask(new SoSndCleanTask(this.channelID, this.afterWorking1, this.afterWorking2, this.sndSize), this);
            this.sndWorking = false;
        }
    }

    private void writeData() {
        try {
            Integer wTimeoutMs = this.context.getConfig().getSoWriteTimeoutMs();
            if (wTimeoutMs != null && wTimeoutMs > 0) {
                this.channel.write(this.swapBuffer, wTimeoutMs, TimeUnit.MILLISECONDS, this.context, this);
            } else {
                this.channel.write(this.swapBuffer, this.context, this);
            }
        } catch (Throwable e) {
            if (e instanceof NotYetConnectedException) {
                long costTimeMs = System.currentTimeMillis() - this.beginTime;
                if (costTimeMs < this.context.getConnectTimeoutMs()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("snd(" + this.channelID + ") NotYetConnected, read try again later.");
                    }
                    this.context.submitSoTask(new SoDelayTask(this.context), this).onCompleted(f -> {
                        writeData();
                    });
                    return;
                } else {
                    logger.warn("snd(" + this.channelID + ") Connection timeout.");
                    this.context.closeChannel(this.channelID, false, e.getMessage());
                }
            } else {
                logger.error("snd(" + this.channelID + ") " + e.getMessage(), e);
                this.context.closeChannel(this.channelID, false, e.getMessage());
            }

            this.context.submitSoTask(new SoSndCleanTask(this.channelID, this.afterWorking1, this.afterWorking2, this.sndSize, e), this);
        }
    }

    @Override
    public void failed(Throwable e, SocketContext context) {
        if (e instanceof InterruptedByTimeoutException) {
            // rcv Close
            logger.error("snd(" + this.channelID + ") writeTimeout, msg:" + e.getMessage());
            context.closeChannel(this.channelID, false, e.getMessage());

        } else if (e instanceof ShutdownChannelGroupException) {

            // rcv Close
            logger.error("snd(" + this.channelID + ") shutdown, msg:" + e.getMessage());
            context.closeChannel(this.channelID, false, e.getMessage());
        } else if (e instanceof AsynchronousCloseException) {

            // rcv Close
            logger.error("snd(" + this.channelID + ") close, msg:" + e.getMessage());
            context.closeChannel(this.channelID, true, e.getMessage());
        } else {

            // rcv Exception
            logger.error("snd(" + this.channelID + ") error, msg:" + e.getMessage(), e);
            context.closeChannel(this.channelID, false, e.getMessage());
        }

        this.context.submitSoTask(new SoSndCleanTask(this.channelID, this.afterWorking1, this.afterWorking2, this.sndSize, e), this);
    }
}
