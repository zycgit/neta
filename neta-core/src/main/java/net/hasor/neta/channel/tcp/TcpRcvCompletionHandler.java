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
package net.hasor.neta.channel.tcp;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;

/**
 * received Handler
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
class TcpRcvCompletionHandler implements CompletionHandler<Integer, SoContextService>, Closeable {
    private static final Logger           logger = Logger.getLogger(TcpRcvCompletionHandler.class);
    private final        long             channelId;
    private final        TcpAsyncChannel  channel;
    private final        SoContextService context;
    private final        NetMonitor       monitor;
    //
    private final        Integer          rTimeoutMs;
    private final        ByteBufAllocator allocator;
    private final        ByteBuffer       rcvSwapBuffer;
    private final        int              connectTimeoutMs;

    public TcpRcvCompletionHandler(TcpAsyncChannel channel, SoContext context, NetMonitor monitor) {
        this.channelId = channel.getChannelId();
        this.channel = channel;
        this.context = (SoContextService) context;
        this.monitor = monitor;

        this.connectTimeoutMs = Math.max(10, channel.getSoConfig().getConnectTimeoutMs());
        this.rTimeoutMs = channel.getSoConfig().getSoReadTimeoutMs();
        this.allocator = context.getByteBufAllocator();
        this.rcvSwapBuffer = this.allocator.jvmBuffer(channel.getSoConfig().getSwapRcvBuf());
    }

    /** Reads a sequence of bytes from this channel into the given buffer. */
    public void read() {
        if (this.channel.isShutdownInput()) {
            return;
        }

        this.rcvSwapBuffer.clear();
        long timeout = this.rTimeoutMs != null && this.rTimeoutMs > 0 ? this.rTimeoutMs : 0L;
        this.channel.read(this.rcvSwapBuffer, this.context, this, timeout, TimeUnit.MILLISECONDS);
    }

    @Override
    public void completed(Integer result, SoContextService context) {
        if (result > 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("rcv(" + this.channelId + ") the receive " + result + " bytes");
            }

            // copy buffer form swap to rcv
            this.rcvSwapBuffer.flip();
            ByteBuf byteBuf = this.allocator.buffer(result);
            byteBuf.writeBuffer(this.rcvSwapBuffer);
            byteBuf.markWriter();

            this.monitor.updateRcvCounter(result);
            this.context.notifyRcvChannelData(this.channelId, byteBuf);

            this.read();
        } else if (result == 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("rcv(" + this.channelId + ") empty");
            }

            this.context.notifyRcvChannelData(this.channelId, ByteBuf.EMPTY);
            this.read();
        } else {
            if (this.channel.isShutdownInput()) {
                // for ShutdownInput local
                logger.info("rcv(" + this.channelId + ") shutdownInput form local.");
                this.context.notifyRcvChannelException(this.channelId, false, new SoInputCloseException("shutdownInput form local"));
            } else {
                // for Remote
                logger.info("rcv(" + this.channelId + ") close form remote.");
                context.notifyChannelClose(this.channelId, true);
            }
        }
    }

    @Override
    public void failed(Throwable e, SoContextService context) {
        if (e instanceof NotYetConnectedException) {
            long costTimeMs = System.currentTimeMillis() - this.monitor.getCreatedTime();
            if (costTimeMs < this.connectTimeoutMs) {
                if (logger.isDebugEnabled()) {
                    logger.debug("rcv(" + this.channelId + ") NotYetConnected, read try again later.");
                }
                this.read();
            } else {
                SoConnectTimeoutException cause = SoUtils.newConnectTimeout(false, this.channelId, this.context, e);
                context.notifyRcvChannelException(this.channelId, true, cause);
            }
            return;
        }

        if (e instanceof ShutdownChannelGroupException || e instanceof ClosedChannelException) {
            if (context.isClose(this.channelId)) {
                return;
            }
            // rcv Close
            SoCloseException err = new SoCloseException("channel is closed " + e.getMessage());
            context.notifyRcvChannelException(this.channelId, true, err);
        } else if (e instanceof InterruptedByTimeoutException) {
            // rcv timeout
            SoReadTimeoutException err = new SoReadTimeoutException(e.getMessage(), e);
            context.notifyRcvChannelException(this.channelId, false, err);
        } else {
            // rcv Exception
            SoRcvException err = new SoRcvException(e.getMessage(), e);
            context.notifyRcvChannelException(this.channelId, true, err);
        }
    }

    @Override
    public void close() throws IOException {
        ByteBufUtils.CLEANER.freeDirectBuffer(this.rcvSwapBuffer);
    }
}