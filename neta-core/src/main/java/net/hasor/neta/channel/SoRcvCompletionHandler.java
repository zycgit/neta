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
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.ShutdownChannelGroupException;

/**
 * received Handler
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class SoRcvCompletionHandler implements CompletionHandler<Integer, SoContextImpl> {
    private static final Logger          logger = Logger.getLogger(SoRcvCompletionHandler.class);
    private final        long            channelID;
    private final        long            createdTime;
    private volatile     SoHandlerStatus status;
    //
    private final        SoAsyncChannel  channel;
    private final        SoContextImpl   context;
    private final        ByteBuf         rcvBuffer;

    public SoRcvCompletionHandler(long channelID, long createdTime, SoAsyncChannel channel, SoContextImpl context) {
        this.channelID = channelID;
        this.createdTime = createdTime;
        this.status = SoHandlerStatus.IDLE;

        this.channel = channel;
        this.context = context;
        this.rcvBuffer = context.getResourceManager().newLocalRcvBuf();
    }

    /** Enhanced {@link ByteBuffer}. */
    public ByteBuf getRcvBuffer() {
        return this.rcvBuffer;
    }

    /** Returns this Handler status. */
    public SoHandlerStatus getStatus() {
        return this.status;
    }

    public void read(SoContextImpl context) {
        this.status = SoHandlerStatus.WAITING;
        this.channel.read(context, this);
    }

    @Override
    public void completed(Integer result, SoContextImpl context) {
        this.status = SoHandlerStatus.PENDING;

        if (result > 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("rcv(" + this.channelID + ") size:" + result);
            }

            // copy buffer form swap to rcv
            SoRcvCopyTask copyTask = new SoRcvCopyTask(this.channelID, this.channel, context, getRcvBuffer());
            this.context.submitSoTask(this.channelID, copyTask, this).onCompleted(f -> {
                this.continueRcv();
            }).onFailed(f -> {
                Throwable e = f.getCause();
                String errorMsg = "rcv(" + this.channelID + ") " + e.getMessage();

                this.status = SoHandlerStatus.IDLE;
                context.notifyRcvChannelError(this.channelID, e);
                context.asyncUnsafeCloseChannel(this.channelID, errorMsg, e);
            });

        } else if (result == 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("rcv(" + this.channelID + ") empty");
            }

            // rcv continue
            this.continueRcv();

        } else {

            this.status = SoHandlerStatus.IDLE;
            if (this.channel.isShutdownInput()) {
                // for ShutdownInput
                String msg = "rcv(" + channelID + ") close form shutdownInput.";
                if (logger.isDebugEnabled()) {
                    logger.debug(msg);
                }
                this.context.notifyRcvChannelError(this.channelID, SoInputCloseException.INSTANCE);
            } else {
                // for Remote
                String msg = "rcv(" + channelID + ") close form remote.";
                if (logger.isDebugEnabled()) {
                    logger.debug(msg);
                }
                context.asyncUnsafeCloseChannel(this.channelID, msg, SoCloseException.INSTANCE);
            }
        }
    }

    private void continueRcv() {
        this.status = SoHandlerStatus.WAITING;
        if (!this.channel.read(this.context, this)) {
            this.status = SoHandlerStatus.IDLE;
        }
    }

    @Override
    public void failed(Throwable e, SoContextImpl context) {
        this.status = SoHandlerStatus.PENDING;

        if (e instanceof NotYetConnectedException) {
            long costTimeMs = System.currentTimeMillis() - this.createdTime;
            if (costTimeMs < context.getConnectTimeoutMs()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("rcv(" + this.channelID + ") NotYetConnected, read try again later.");
                }
                this.continueRcv();
            } else {
                SoConnectTimeoutException cause = SoUtils.newTimeout(false, this.channelID, this.context, e);

                this.status = SoHandlerStatus.IDLE;
                context.notifyRcvChannelError(this.channelID, cause);
                context.asyncUnsafeCloseChannel(this.channelID, cause.getMessage(), cause);
            }
            return;
        }

        String errorMsg = "";
        if (e instanceof ShutdownChannelGroupException || e instanceof ClosedChannelException) {
            if (context.isClose(this.channelID)) {
                return;
            }
            // rcv Close
            errorMsg = "rcv(" + this.channelID + ") channel is closed " + e.getMessage();
        } else {
            // rcv Exception
            errorMsg = "rcv(" + this.channelID + ") " + e.getMessage();
        }

        this.status = SoHandlerStatus.IDLE;
        context.notifyRcvChannelError(this.channelID, e);
        context.asyncUnsafeCloseChannel(this.channelID, errorMsg, e);
    }
}