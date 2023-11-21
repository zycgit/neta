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
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;

import java.nio.ByteBuffer;
import java.nio.channels.*;

/**
 * received Handler
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoRcvCompletionHandler implements CompletionHandler<Integer, SoContextImpl> {
    private static final Logger                    logger = Logger.getLogger(SoRcvCompletionHandler.class);
    private final        long                      channelID;
    private final        long                      createdTime;
    private final        AsynchronousSocketChannel channel;
    private final        SoContextImpl             context;
    private final        ByteBuffer                swapBuffer;
    private final        ByteBuf                   rcvBuffer;

    public SoRcvCompletionHandler(long channelID, long createdTime, AsynchronousSocketChannel channel, SoContextImpl context) {
        this.channelID = channelID;
        this.createdTime = createdTime;
        this.channel = channel;
        this.context = context;

        SoResManager rm = context.getResourceManager();
        this.swapBuffer = rm.newSwapRcvBuf();
        this.rcvBuffer = rm.newLocalRcvBuf();
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
    public ByteBuf getRcvBuffer() {
        return this.rcvBuffer;
    }

    /**
     * reset swap {@link ByteBuffer} for next receive.
     */
    public void resetSwapBuffer() {
        this.swapBuffer.clear();
    }

    @Override
    public void completed(Integer result, SoContextImpl context) {
        if (result > 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("rcv(" + this.channelID + ") size:" + result);
            }

            // copy buffer form swap to rcv
            this.swapBuffer.flip();
            SoRcvCopyTask copyTask = new SoRcvCopyTask(this.channelID, context, getSwapBuffer(), getRcvBuffer());

            this.context.submitSoTask(this.channelID, copyTask, this).onCompleted(f -> {
                this.continueRcv(0);
            }).onFailed(f -> {
                this.failed(f.getCause(), context);
            });

        } else if (result == 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("rcv(" + this.channelID + ") empty");
            }

            // rcv continue
            this.continueRcv(0);

        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("rcv(" + this.channelID + ") end");
            }

            // rcv close
            String msg = "rcv(" + channelID + ") close form remote.";
            context.unsafeCloseChannel(this.channelID, msg, SoCloseException.INSTANCE);
        }
    }

    private void continueRcv(int delayInterval) {
        // It is async to avoid recursion.
        this.context.submitSoTask(this.channelID, new SoDelayTask(delayInterval), this).onCompleted(f -> {
            try {
                this.resetSwapBuffer();
                this.channel.read(this.getSwapBuffer(), this.context, this);
            } catch (Exception e) {
                this.failed(e, this.context);
            }
        });
    }

    @Override
    public void failed(Throwable e, SoContextImpl context) {
        if (e instanceof NotYetConnectedException) {
            long costTimeMs = System.currentTimeMillis() - this.createdTime;
            if (costTimeMs < context.getConnectTimeoutMs()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("rcv(" + this.channelID + ") NotYetConnected, read try again later.");
                }
                continueRcv(context.getConfig().getRetryIntervalMs());
            } else {
                SoConnectTimeoutException cause = SoUtils.newTimeout(false, this.channelID, this.context, e);

                context.notifyChannelError(this.channelID, cause);
                context.unsafeCloseChannel(this.channelID, cause.getMessage(), cause);
            }
            return;
        }

        String errorMsg = "";
        if (e instanceof ShutdownChannelGroupException || e instanceof AsynchronousCloseException) {
            // rcv Close
            errorMsg = "rcv(" + this.channelID + ") channel is closed " + e.getMessage();
        } else {
            // rcv Exception
            errorMsg = "rcv(" + this.channelID + ") " + e.getMessage();
        }

        context.notifyChannelError(this.channelID, e);
        context.unsafeCloseChannel(this.channelID, errorMsg, e);
    }
}