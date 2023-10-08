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

/**
 * socket -> swapBuffer
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoRcvCompletionHandler implements CompletionHandler<Integer, SoContextImpl> {
    private static final Logger                    logger = Logger.getLogger(SoRcvCompletionHandler.class);
    private final        long                      channelID;
    private final        long                      beginTime;
    private final        AsynchronousSocketChannel channel;
    private final        ByteBuffer                swapBuffer;
    private final        ByteBuf                   rcvBuffer;

    public SoRcvCompletionHandler(long channelID, long beginTime, AsynchronousSocketChannel channel, SoContextImpl context) {
        this.channelID = channelID;
        this.beginTime = beginTime;

        this.channel = channel;
        this.swapBuffer = context.newSwapRcvBuf();
        this.rcvBuffer = context.newLocalRcvBuf();
    }

    public ByteBuffer getSwapBuffer() {
        return this.swapBuffer;
    }

    public ByteBuf getRcvBuffer() {
        return this.rcvBuffer;
    }

    public void reset() {
        this.swapBuffer.clear();
    }

    @Override
    public void completed(Integer result, SoContextImpl context) {
        if (result > 0) {
            logger.debug("rcvChannel(" + this.channelID + ") size:" + result);
            this.swapBuffer.flip();

            // copy buffer form swap to rcv
            SoRcvCopyTask copyTask = new SoRcvCopyTask(this.channelID, context, getSwapBuffer(), getRcvBuffer());

            context.submitSoTask(copyTask, this).onCompleted(f -> {
                // rcv continue
                SoRcvTask rcvTask = new SoRcvTask(this.channelID, this.beginTime, this.channel, this, context);
                context.submitSoTask(rcvTask, this);
            }).onFailed(f -> {
                this.failed(f.getCause(), context);
            });

        } else if (result == 0) {
            logger.debug("rcv(" + this.channelID + ") empty");

            // rcv continue
            SoRcvTask rcvTask = new SoRcvTask(this.channelID, this.beginTime, this.channel, this, context);

            context.submitSoTask(rcvTask, this).onFailed(f -> {
                this.failed(f.getCause(), context);
            });
        } else {
            logger.debug("rcv(" + this.channelID + ") end");

            // rcv close
            context.closeChannel(this.channelID, "remote close.");
        }
    }

    @Override
    public void failed(Throwable e, SoContextImpl context) {
        if (e instanceof InterruptedByTimeoutException) {
            // rcv Close
            logger.error("rcv(" + this.channelID + ") readTimeout, msg:" + e.getMessage());
            context.closeChannel(this.channelID, e.getMessage());

        } else if (e instanceof ShutdownChannelGroupException) {

            // rcv Close
            logger.error("rcv(" + this.channelID + ") shutdown, msg:" + e.getMessage());
            context.closeChannel(this.channelID, e.getMessage());
        } else if (e instanceof AsynchronousCloseException) {

            // rcv Close
            logger.error("rcv(" + this.channelID + ") close, msg:" + e.getMessage());
            context.closeChannel(this.channelID, e.getMessage());
        } else {

            // rcv Exception
            logger.error("rcv(" + this.channelID + ") error, msg:" + e.getMessage(), e);
            context.closeChannel(this.channelID, e.getMessage());
        }
    }
}
