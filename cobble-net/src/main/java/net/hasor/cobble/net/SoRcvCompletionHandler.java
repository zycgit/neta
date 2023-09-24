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
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;

/**
 * socket -> swapBuffer
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoRcvCompletionHandler implements CompletionHandler<Integer, SocketContext> {
    private static final Logger                    logger = Logger.getLogger(SoRcvCompletionHandler.class);
    private final        long                      channelID;
    private final        AsynchronousSocketChannel channel;
    private final        ByteBuffer                swapBuffer;
    private final        ByteBuf                   rcvBuffer;

    public SoRcvCompletionHandler(long channelID, AsynchronousSocketChannel channel, SocketContext context) {
        this.channelID = channelID;
        this.channel = channel;
        this.swapBuffer = context.newSwapBuf();
        this.rcvBuffer = context.newRcvBuf();
    }

    public long getChannelID() {
        return this.channelID;
    }

    public ByteBuffer getSwapBuffer() {
        return this.swapBuffer;
    }

    public ByteBuf getRcvBuffer() {
        return this.rcvBuffer;
    }

    @Override
    public void completed(Integer result, SocketContext context) {
        if (result > 0) {
            logger.debug("rcvChannel(" + this.channelID + ") size:" + result);
            this.swapBuffer.flip();

            // swapBuffer to rcvBuffer
            SoRcvTask task = new SoRcvTask(this.channelID, context, getSwapBuffer(), getRcvBuffer());

            // rcv continue
            context.submitSoTask(task, this).onCompleted(f -> {
                this.swapBuffer.position(0);
                this.swapBuffer.limit(this.swapBuffer.capacity());
                this.channel.read(getSwapBuffer(), context, this);
            });

        } else if (result == -1) {
            logger.debug("rcv(" + this.channelID + ") end");

            // rcv close
            context.closeChannel(this.channelID, true, "remote close.");
        } else {
            logger.debug("rcv(" + this.channelID + ") empty");

            // rcv continue
            this.channel.read(this.getSwapBuffer(), context, this);
        }
    }

    @Override
    public void failed(Throwable e, SocketContext context) {
        logger.error("rcv(" + this.channelID + ") failed, msg:" + e.getMessage(), e);

        // rcv close
        context.readFailed(this.channelID, e);
    }
}
