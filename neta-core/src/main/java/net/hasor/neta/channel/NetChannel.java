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
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.timer.Timeout;
import net.hasor.cobble.concurrent.timer.TimerTask;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAdapter;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.NotYetConnectedException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tcp network channel
 * the channel that binds to the Application layer network protocol stack.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class NetChannel extends AttributeChannel<NetChannel> {
    private static final Logger                 logger = Logger.getLogger(NetChannel.class);
    private final        long                   channelID;
    private final        NetListen              forListen;
    protected final      SoAsyncChannel         channel;
    protected final      SoSndContext           wContext;
    protected final      SoContextImpl          context;
    private final        SocketAddress          localAddr;
    private final        SocketAddress          remoteAddr;
    private final        long                   createdTime;
    private              long                   lastSndTime;
    private              long                   lastRcvTime;
    private final        Object                 readTimeoutSyncObj;
    private              long                   lastNotifyRcvRetryTime;
    //
    private final        SoRcvCompletionHandler rHandler;
    private final        SoSndCompletionHandler wHandler;
    private final        AtomicBoolean          wStatus;
    //
    protected            PipeContextImpl        pipeCtx;
    protected            Pipeline<ByteBuf>      pipeline;
    //
    private final        boolean                netLog;
    protected final      AtomicBoolean          closeStatus;
    protected final      Future<NetChannel>     closeFuture;
    private volatile     long                   counterReceived;
    //private volatile     long                    counterSend;

    NetChannel(long channelID, long createdTime, NetListen forListen, SocketAddress localAddr, SocketAddress remoteAddr,//
            SoAsyncChannel channel, SoRcvCompletionHandler rHandler, SoSndCompletionHandler wHandler, SoSndContext wContext) {
        this.channelID = channelID;
        this.forListen = forListen;
        this.createdTime = createdTime;
        this.lastSndTime = createdTime;
        this.lastRcvTime = createdTime;
        this.readTimeoutSyncObj = new Object();

        this.channel = channel;
        this.wContext = wContext;
        this.context = wContext.getContext();
        this.localAddr = localAddr;
        this.remoteAddr = remoteAddr;
        this.netLog = this.context.getConfig().isNetlog();
        this.closeStatus = new AtomicBoolean(false);
        this.closeFuture = new BasicFuture<>();

        this.rHandler = rHandler;
        this.wHandler = wHandler;
        this.wStatus = new AtomicBoolean(false);
    }

    protected void initPipe(PipeContextImpl pipeContext, Pipeline<?> pipeline) {
        this.pipeCtx = pipeContext;
        this.pipeline = (Pipeline<ByteBuf>) pipeline;
    }

    @Override
    public long getChannelID() {
        return this.channelID;
    }

    @Override
    public boolean isListen() {
        return false;
    }

    @Override
    public long getCreatedTime() {
        return this.createdTime;
    }

    @Override
    public long getLastActiveTime() {
        return Math.max(this.lastRcvTime, this.lastSndTime);
    }

    /** last sent data time */
    public long getLastSndTime() {
        return this.lastSndTime;
    }

    /** last received data time */
    public long getLastRcvTime() {
        return this.lastRcvTime;
    }

    @Override
    public boolean isServer() {
        return this.forListen != null;
    }

    @Override
    public boolean isClient() {
        return this.forListen == null;
    }

    /** Returns whether the read channel is closed. */
    public boolean isShutdownInput() {
        return this.channel.isShutdownInput();
    }

    /** Shutdown the connection for reading without closing the channel. */
    public void shutdownInput() {
        try {
            if (this.netLog) {
                logger.info("channel(" + this.channelID + ") shutdownInput.");
            }
            this.channel.shutdownInput();
        } catch (NotYetConnectedException | IOException e) {
            logger.warn("channel(" + this.channelID + ") shutdownInput, failed " + e.getMessage(), e);
        }
    }

    /** Returns the receiving Handler state */
    public SoHandlerStatus getRcvHandlerStatus() {
        return this.rHandler.getStatus();
    }

    /** Returns whether the write channel is closed. */
    public boolean isShutdownOutput() {
        return this.channel.isShutdownOutput();
    }

    /** Shutdown the connection for write without closing the channel. */
    public void shutdownOutput() {
        try {
            this.channel.shutdownOutput();
        } catch (NotYetConnectedException | IOException e) {
            logger.warn("channel(" + this.channelID + ") shutdownOutput " + e.getMessage(), e);
        }
    }

    /** Returns the send Handler state */
    public SoHandlerStatus getSndHandlerStatus() {
        return this.wHandler.getStatus();
    }

    @Override
    public SocketAddress getLocalAddr() {
        return this.localAddr;
    }

    @Override
    public SocketAddress getRemoteAddr() {
        return this.remoteAddr;
    }

    @Override
    public <T> T findPipeContext(Class<T> serviceType) {
        return this.pipeCtx.context(serviceType);
    }

    /** Returns the {@link NetListen} that accepts this channel */
    public NetListen getSource() {
        return this.forListen;
    }

    @Override
    public boolean isClose() {
        return !this.channel.isOpen() || this.closeStatus.get();
    }

    @Override
    public Future<NetChannel> close() {
        if (this.closeStatus.compareAndSet(false, true)) {
            if (this.channel.isOpen()) {
                SoCloseTask task = new SoCloseTask(this.channelID, this.context, false);
                this.context.submitSoTask(this.channelID, task, this).onCompleted(f -> {
                    closeFuture.completed(this);
                }).onFailed(f -> {
                    closeFuture.failed(f.getCause());
                }).onCancel(f -> {
                    closeFuture.cancel();
                });
            } else {
                this.closeFuture.completed(this);
            }
        }
        return this.closeFuture;
    }

    @Override
    public Future<NetChannel> closeNow() {
        if (this.channel.isOpen() && this.closeStatus.compareAndSet(false, true)) {
            logger.info("channel(" + this.channelID + ") closeNow");
            new SoCloseTask(this.channelID, this.context, true).run();
        }
        this.closeFuture.completed(this);
        return this.closeFuture;
    }

    /** Returns whether pipleline rcv is available. */
    public boolean isRcvAvailable() {
        return this.pipeline.rcvAvailable();
    }

    /** Number of bytes received */
    public long getReceivedBytes() {
        return this.counterReceived;
    }

    /** Returns whether pipleline snd is available. */
    public boolean isSndAvailable() {
        return this.pipeline.sndAvailable();
    }

    /* Receive data without concurrency */
    synchronized final void notifyRcv(int dataSize, int retryCnt) {
        this.counterReceived += dataSize;

        if (this.netLog) {
            String retryMsg = (retryCnt > 0) ? (", retryCnt is " + retryCnt) : "";
            logger.info("rcv(" + this.channelID + ") the receive " + dataSize + " bytes" + retryMsg);
        }

        if (retryCnt == 0) {
            this.lastRcvTime = System.currentTimeMillis();
            this.lastNotifyRcvRetryTime = 0;
            synchronized (this.readTimeoutSyncObj) {
                this.readTimeoutSyncObj.notifyAll();
            }
        }

        if (retryCnt > 3 && (this.lastNotifyRcvRetryTime + 3000) < System.currentTimeMillis()) {
            logger.info("rcv(" + this.channelID + ") the receive buffer is full, ");
            this.lastNotifyRcvRetryTime = System.currentTimeMillis();
        }

        try {
            this.pipeCtx.flash(PipeContext.SO_CHANNEL_RETRY_CNT, retryCnt);

            if (!this.pipeline.rcvAvailable()) {
                logger.info("rcv(" + this.channelID + ") the pipeline is not available.");
                this.pipeline.rcvError(this.pipeCtx, null, PipeFullException.INSTANCE);
                return;
            }

            //The root Buffer cannot be deallocated
            ByteBuf rcvByteBuf = this.rHandler.getRcvBuffer();
            Object[] sndBufSet = this.pipeline.rcvLayer(this.pipeCtx, null, new ByteBuf[] { new ByteBufSafe(rcvByteBuf) });
            for (Object sndBuf : sndBufSet) {
                ByteBuf buf = (ByteBuf) sndBuf;
                if (buf.hasReadable()) {
                    appendSoSndTask(new SoSndData(buf, new BasicFuture<>(), this));
                }
            }
        } catch (Throwable e) {
            // It is not executed unless the exception is thrown in PipeReceiveListener.onError(...)
            String msg = "invoker pipeline failed: " + e.getMessage();
            logger.error("rcv(" + this.channelID + ") " + msg, e);

            this.closeStatus.set(true);
            this.context.syncUnsafeCloseChannel(this.channelID, msg, e);
        } finally {
            this.pipeCtx.clearFlash(); // Cleanup must be performed because there are times when PipeChainRoot is not used
        }
    }

    /* Receive error */
    synchronized final void notifyError(boolean isRcv, Throwable e) {
        try {
            //The root Buffer cannot be deallocated
            Object[] sndBufSet;
            if (isRcv) {
                sndBufSet = this.pipeline.rcvError(this.pipeCtx, null, e);
            } else {
                sndBufSet = this.pipeline.sndError(this.pipeCtx, null, e);
            }

            for (Object sndBuf : sndBufSet) {
                ByteBuf buf = (ByteBuf) sndBuf;
                if (buf.hasReadable()) {
                    appendSoSndTask(new SoSndData(buf, new BasicFuture<>(), this));
                }
            }
        } catch (Throwable ee) {
            // It is not executed unless the exception is thrown in PipeReceiveListener.onError(...)
            String msg = "invoker pipeline failed: " + ee.getMessage();
            logger.error("rcv(" + this.channelID + ") " + msg, ee);

            this.closeStatus.set(true);
            this.context.syncUnsafeCloseChannel(this.channelID, msg, e);
        } finally {
            this.pipeCtx.clearFlash(); // Cleanup must be performed because there are times when PipeChainRoot is not used
        }
    }

    private static class ByteBufSafe extends ByteBufAdapter {
        public ByteBufSafe(ByteBuf byteBuf) {
            super(byteBuf);
        }

        @Override
        public void free() {
        }

        @Override
        public void close() {
        }
    }

    /**
     * sent data to remote, The network IO transfer operation is performed asynchronously.
     * <p>data goes through the application layer network protocol stack</p>
     */
    public Future<?> sendData(Object writeData) {
        return this.sendData(writeData, null);
    }

    /**
     * sent data to remote, The network IO transfer operation is performed asynchronously.
     * <p>data goes through the application layer network protocol stack</p>
     */
    public Future<NetChannel> sendData(Object writeData, String pipeName) {
        Future<NetChannel> future = new BasicFuture<>();

        if (!this.pipeline.sndAvailable()) {
            logger.info("snd(" + this.channelID + ") the pipeline is not available.");
            future.failed(PipeFullException.INSTANCE);
            return future;
        }

        try {
            Object[] sndByteBuf = this.pipeline.sndLayer(this.pipeCtx, pipeName, new Object[] { writeData });
            AtomicInteger cnt = new AtomicInteger(sndByteBuf.length);
            for (Object buf : sndByteBuf) {
                Future<NetChannel> itemFuture = new BasicFuture<>();
                itemFuture.onFailed(f -> {
                    future.failed(f.getCause());
                }).onFinal(f -> {
                    cnt.decrementAndGet();
                    if (cnt.get() == 0) {
                        future.completed(this);
                    }
                });

                appendSoSndTask(new SoSndData((ByteBuf) buf, itemFuture, this));
            }
        } catch (Throwable e) {
            logger.error("snd(" + channelID + ") failed, " + e.getMessage(), e);
            future.failed(e);
        } finally {
            this.pipeCtx.clearFlash(); // Cleanup must be performed because there are times when PipeChainRoot is not used
        }
        return future;
    }

    /** flash */
    public Future<NetChannel> flush() {
        Future<NetChannel> future = new BasicFuture<>();
        appendSoSndTask(new SoSndData(SoSndData.EMPTY_DATA, future, this));
        return future;
    }

    /**
     * sent data to remote, The network IO transfer operation is performed asynchronously.
     * <p>data goes through the application layer network protocol stack</p>
     */
    public Future<?> flush(String pipeName) {
        return null;
    }

    private void appendSoSndTask(SoSndData wTask) {
        if (this.netLog) {
            logger.info("snd(" + this.channelID + ") appendSoSndTask, dataSize is " + wTask.getDataSize() + ", closeStatus is " + this.closeStatus.get());
        }

        if (this.closeStatus.get()) {
            wTask.failed(SoCloseException.INSTANCE);
            return;
        }

        this.wContext.offer(wTask);

        if (this.wStatus.compareAndSet(false, true)) {
            SoSndTask sendTask = new SoSndTask(this.channelID, this.channel, this.wHandler, this.wContext);
            this.wContext.submitTask(sendTask, this).onCompleted(f -> {
                this.lastSndTime = System.currentTimeMillis();
                if (this.wContext.isEmpty()) {
                    this.wStatus.compareAndSet(true, false);
                } else {
                    this.wContext.submitTask(sendTask, this);
                }
            });
        }
    }

    /**
     * Sets a timer that will fire readTimeout if no network data is received within a specified amount of time.
     * @see SoConfig#getSoReadTimeoutMs()
     */
    public void setReadTimeout() {
        SoConfig config = this.context.getConfig();
        if (config.getSoReadTimeoutMs() > 0) {
            this.setReadTimeout(config.getSoReadTimeoutMs(), TimeUnit.MILLISECONDS);
        } else {
            this.setReadTimeout(6, TimeUnit.SECONDS);
        }
    }

    /** Sets a timer that will fire readTimeout if no network data is received within a specified amount of time. */
    public void setReadTimeout(int timeout, TimeUnit unit) {
        final class CheckTimeout implements TimerTask {
            private final long lastRcvTime;
            private final long waitTimeMs;

            public CheckTimeout(long lastRcvTime, long waitTimeMs) {
                this.lastRcvTime = lastRcvTime;
                this.waitTimeMs = waitTimeMs;
            }

            @Override
            public void run(Timeout timeout) {
                if (getLastRcvTime() <= this.lastRcvTime) {
                    notifyError(true, new SoReadTimeoutException("no data was received with " + this.waitTimeMs + " milliseconds."));
                }
            }
        }

        long waitTimeMs = unit.toMillis(timeout);
        this.context.newTimeout(new CheckTimeout(this.lastRcvTime, waitTimeMs), timeout, unit);
    }

    /**
     * expect new data to be received within SoReadTimeoutMs
     * @see SoConfig#getSoReadTimeoutMs()
     */
    public void waitReceive() throws InterruptedException, SoReadTimeoutException {
        SoConfig config = this.context.getConfig();
        if (config.getSoReadTimeoutMs() > 0) {
            this.waitReceive(config.getSoReadTimeoutMs(), TimeUnit.MILLISECONDS);
        } else {
            this.waitReceive(6, TimeUnit.SECONDS);
        }
    }

    /**
     * expect new data to be received within timeout.
     * @see SoConfig#getSoReadTimeoutMs()
     */
    public void waitReceive(int timeout, TimeUnit unit) throws InterruptedException, SoReadTimeoutException {
        long waitTimeMs = unit.toMillis(timeout);
        long startTime = System.currentTimeMillis();

        synchronized (this.readTimeoutSyncObj) {
            this.readTimeoutSyncObj.wait(waitTimeMs);

            long cost = System.currentTimeMillis() - startTime;
            if (cost >= waitTimeMs) {
                throw new SoReadTimeoutException("no data was received with " + waitTimeMs + " milliseconds.");
            }
        }
    }
}