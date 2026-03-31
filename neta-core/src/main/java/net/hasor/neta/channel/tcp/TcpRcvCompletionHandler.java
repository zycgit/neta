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
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.*;

/**
 * TCP receive-side {@link CompletionHandler} that continuously maintains the asynchronous read loop
 * for a single {@link TcpAsyncChannel}.
 * <p>After a read completes, it copies the bytes from the reusable swap buffer into a fresh
 * {@link ByteBuf}, forwards that data into the channel pipeline, and immediately arms the next read
 * operation.
 * <p><b>Read flow:</b>
 * <pre>
 *   TcpAsyncChannel.read(rcvSwapBuffer, ...)
 *                  ▼
 *       completed(bytesRead, ctx)
 *       ┌──────────┼──────────────┐
 *       ▼          ▼              ▼
 *   result > 0   result == 0   result < 0
 *       │          │              ├── local shutdownInput
 *       │          │              │      -> SoInputCloseException
 *       │          │              └── remote close
 *       │          │                     -> notifyChannelClose(...)
 *       │          └── emit ByteBuf.EMPTY
 *       │              -> continue read()
 *       ├── swapBuffer -> ByteBuf
 *       ├── update receive metrics
 *       ├── notifyRcvSingle(...) / notifyRcvChannelData(...)
 *       └── continue read()
 * </pre>
 * <p><b>Error handling:</b> this handler normalizes EOF, local input shutdown, read timeout,
 * connect timeout, and channel-close edge cases into Neta's {@link SoException} hierarchy before
 * reporting them to {@link SoContextService}.
 * <p><b>Buffer management:</b> the swap buffer is allocated once per channel and released when the
 * handler is closed.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see TcpAsyncChannel
 * @see SoRcvException
 * @see SoReadTimeoutException
 * @see SoInputCloseException
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
    private              NetChannel       netChannel; // Direct reference used by the fast path.

    /**
     * Create a TCP read-completion handler.
     * @param channel the underlying asynchronous channel
     * @param context the runtime context
     * @param monitor the channel metrics monitor
     */
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

    /**
     * Set the direct {@link NetChannel} reference for the fast receive path.
     * <p>This bypasses the ConcurrentHashMap lookup.
     * @param netChannel the framework-level channel
     */
    void setNetChannel(NetChannel netChannel) {
        this.netChannel = netChannel;
    }

    /**
     * Start one read operation and place the bytes from the current channel into the swap buffer.
     */
    public void read() {
        if (this.channel.isShutdownInput()) {
            return;
        }

        ((Buffer) this.rcvSwapBuffer).clear();
        long timeout = this.rTimeoutMs != null && this.rTimeoutMs > 0 ? this.rTimeoutMs : 0L;
        this.channel.read(this.rcvSwapBuffer, this.context, this, timeout, TimeUnit.MILLISECONDS);
    }

    /**
     * Process the result after data has been read successfully and arm the next read.
     * @param result the number of bytes read this time
     * @param context the runtime context
     */
    @Override
    public void completed(Integer result, SoContextService context) {
        boolean printLog = this.context.getConfig().isPrintLog();
        if (result > 0) {
            if (printLog) {
                logger.info("rcv(" + this.channelId + ") [TCP-READ] bytes=" + result);
            }

            // Copy the data from the swap buffer into the receive buffer.
            ((Buffer) this.rcvSwapBuffer).flip();
            ByteBuf byteBuf = this.allocator.buffer(result);
            byteBuf.writeBuffer(this.rcvSwapBuffer);
            byteBuf.markWriter();

            this.monitor.updateRcvCounter(result);

            // Fast path: direct call that bypasses ConcurrentHashMap lookup and varargs allocation.
            if (this.netChannel != null) {
                try {
                    this.netChannel.notifyRcvSingle(byteBuf);
                } catch (Throwable e) {
                    SoException ee = e instanceof SoException ? (SoException) e : new SoRcvException(e.getMessage(), e);
                    this.context.notifyRcvChannelException(this.channelId, true, ee);
                }
            } else {
                this.context.notifyRcvChannelData(this.channelId, byteBuf);
            }

            this.read();
        } else if (result == 0) {
            if (printLog) {
                logger.info("rcv(" + this.channelId + ") empty");
            }

            this.context.notifyRcvChannelData(this.channelId, ByteBuf.EMPTY);
            this.read();
        } else {
            if (this.channel.isShutdownInput()) {
                // Local ShutdownInput scenario.
                logger.info("rcv(" + this.channelId + ") shutdownInput form local.");
                this.context.notifyRcvChannelException(this.channelId, false, new SoInputCloseException("shutdownInput form local"));
            } else {
                // Remote-close scenario.
                logger.info("rcv(" + this.channelId + ") close form remote.");
                context.notifyChannelClose(this.channelId, true);
            }
        }
    }

    /**
     * Normalize and report exceptions when reading fails.
     * @param e the failure cause
     * @param context the runtime context
     */
    @Override
    public void failed(Throwable e, SoContextService context) {
        if (e instanceof NotYetConnectedException) {
            long costTimeMs = System.currentTimeMillis() - this.monitor.getCreatedTime();
            if (costTimeMs < this.connectTimeoutMs) {
                if (this.context.getConfig().isPrintLog()) {
                    logger.info("rcv(" + this.channelId + ") NotYetConnected, read try again later.");
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
            // Receive-side close exception.
            SoCloseException err = new SoCloseException("channel is closed " + e.getMessage());
            context.notifyRcvChannelException(this.channelId, true, err);
        } else if (e instanceof InterruptedByTimeoutException) {
            // Receive timeout.
            SoReadTimeoutException err = new SoReadTimeoutException(e.getMessage(), e);
            context.notifyRcvChannelException(this.channelId, false, err);
            this.read();
        } else {
            // Other receive exception.
            SoRcvException err = new SoRcvException(e.getMessage(), e);
            context.notifyRcvChannelException(this.channelId, true, err);
        }
    }

    /**
     * Close the read handler and release the swap buffer.
     * @throws IOException if an I/O error occurs while closing
     */
    @Override
    public void close() throws IOException {
        ByteBufUtils.CLEANER.freeDirectBuffer(this.rcvSwapBuffer);
    }
}