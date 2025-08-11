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

import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.ShutdownChannelGroupException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * received Handler
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class SoRcvCompletionHandler implements CompletionHandler<Integer, SoContextService> {
    private static final Logger                           logger = Logger.getLogger(SoRcvCompletionHandler.class);
    private final        long                             channelID;
    private final        long                             createdTime;
    private final        AtomicReference<SoHandlerStatus> status;
    private final        AtomicLong                       counterBytes;
    private final        int                              connectTimeoutMs;
    //
    private final        SoAsyncChannel                   channel;
    private final        SoContextService                 context;

    public SoRcvCompletionHandler(long channelID, long createdTime, SoAsyncChannel channel, SoContextService context) {
        this.channelID = channelID;
        this.createdTime = createdTime;
        this.status = new AtomicReference<>(SoHandlerStatus.IDLE);
        this.counterBytes = new AtomicLong();
        this.connectTimeoutMs = Math.max(10, channel.getSoConfig().getConnectTimeoutMs());

        this.channel = channel;
        this.context = context;
    }

    /** Returns this Handler status. */
    public SoHandlerStatus getStatus() {
        return this.status.get();
    }

    /** Gets the number of bytes that have been received. */
    public long getCounterBytes() {
        return this.counterBytes.get();
    }

    @Override
    public void completed(Integer result, SoContextService context) {
        this.status.set(SoHandlerStatus.PENDING);

        if (result > 0) {
            this.counterBytes.addAndGet(result);
            if (logger.isDebugEnabled()) {
                logger.debug("rcv(" + this.channelID + ") size:" + result);
            }

            // copy buffer form swap to rcv
            ByteBuf rcvBytes = this.channel.pullSwapBuffer(result);
            this.context.notifyChannelRcv(this.channelID, rcvBytes);

            this.read();
        } else if (result == 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("rcv(" + this.channelID + ") empty");
            }

            this.context.notifyChannelRcv(this.channelID, ByteBuf.EMPTY);
            this.read();
        } else {

            this.status.set(SoHandlerStatus.IDLE);

            if (this.channel.isShutdownInput()) {
                // for ShutdownInput local
                NetChannel netChannel = (NetChannel) this.context.findChannel(this.channelID);
                if (netChannel != null && !netChannel.closeStatus.get()) {
                    String msg = "rcv(" + this.channelID + ") shutdownInput form local.";
                    logger.info(msg);
                    this.context.notifyRcvChannelError(this.channelID, SoInputCloseException.INSTANCE);
                }
            } else if (!this.channel.isIgnoreReadEofFlag()) {
                // for Remote
                String msg = "rcv(" + this.channelID + ") close form remote.";
                logger.info(msg);
                context.asyncUnsafeCloseChannel(this.channelID, msg, SoCloseException.INSTANCE);
            } else {
                logger.info("rcv(" + this.channelID + ") shutdownInput form remote.");
            }
        }
    }

    public void read() {
        this.status.set(SoHandlerStatus.WAITING);
        if (!this.channel.read(this.context, this)) {
            this.status.set(SoHandlerStatus.IDLE);
        }
    }

    @Override
    public void failed(Throwable e, SoContextService context) {
        this.status.set(SoHandlerStatus.PENDING);

        if (e instanceof NotYetConnectedException) {
            long costTimeMs = System.currentTimeMillis() - this.createdTime;
            if (costTimeMs < this.connectTimeoutMs) {
                if (logger.isDebugEnabled()) {
                    logger.debug("rcv(" + this.channelID + ") NotYetConnected, read try again later.");
                }
                this.read();
            } else {
                SoConnectTimeoutException cause = SoUtils.newTimeout(false, this.channelID, this.context, e);

                context.notifyRcvChannelError(this.channelID, cause);
                context.asyncUnsafeCloseChannel(this.channelID, cause.getMessage(), cause);
                this.status.set(SoHandlerStatus.IDLE);
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

        context.notifyRcvChannelError(this.channelID, e);
        context.asyncUnsafeCloseChannel(this.channelID, errorMsg, e);
        this.status.set(SoHandlerStatus.IDLE);
    }
}