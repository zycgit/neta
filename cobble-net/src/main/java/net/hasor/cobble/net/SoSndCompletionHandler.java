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
import java.util.List;

/**
 * swapBuffer -> socket
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoSndCompletionHandler implements CompletionHandler<Integer, SocketContext> {
    private static final Logger                    logger = Logger.getLogger(SoSndCompletionHandler.class);
    private final        long                      channelID;
    private final        AsynchronousSocketChannel channel;
    private final        SocketContext             context;
    private final        ByteBuffer                swapBuffer;
    private final        ByteBuf                   sndBuffer;
    //
    private              int                       sndSize;
    private              boolean                   sndWorking;
    private              List<SoSndData>           afterWorking;

    public SoSndCompletionHandler(long channelID, AsynchronousSocketChannel channel, SocketContext context) {
        this.channelID = channelID;
        this.channel = channel;
        this.context = context;
        this.swapBuffer = context.newSwapBuf();
        this.sndBuffer = context.newSndBuf();
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

    public void prepareWrite(List<SoSndData> afterWorking) {
        this.sndSize = 0;
        this.sndWorking = true;
        this.sndBuffer.read(this.swapBuffer);
        this.swapBuffer.flip();
        this.afterWorking = afterWorking;
    }

    @Override
    public void completed(Integer result, SocketContext context) {
        logger.debug("sndChannel(" + this.channelID + ") size:" + result);

        this.sndSize += result;

        if (this.swapBuffer.hasRemaining()) {

            // continue send data.
            this.writeData();

        } else if (this.sndBuffer.hasReadable()) {

            // reset swap
            this.swapBuffer.position(0);
            this.swapBuffer.limit(this.swapBuffer.capacity());

            // copy data snd to swap
            this.sndBuffer.read(this.swapBuffer);
            this.swapBuffer.flip();

            // continue send data.
            this.writeData();

        } else {
            this.context.submitSoTask(new SoSndCleanTask(this.afterWorking), this);
            this.sndWorking = false;
        }
    }

    private void writeData() {
        try {
            this.channel.write(this.swapBuffer, context, this);
        } catch (Throwable e) {
            this.writeFailed(e);
        }
    }

    @Override
    public void failed(Throwable e, SocketContext context) {
        logger.error("snd(" + this.channelID + ") failed, msg:" + e.getMessage(), e);
        this.writeFailed(e);
    }

    private void writeFailed(Throwable e) {

        // snd close
        //        context.writeFailed(this.channelID, e);
    }
}
