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

import java.net.SocketAddress;
import java.nio.channels.AsynchronousSocketChannel;
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
    private static final Logger                      logger = Logger.getLogger(NetChannel.class);
    private final        long                        channelID;
    private final        NetListen                   forListen;
    protected final      AsynchronousSocketChannel   channel;
    protected final      SoSndContext                wContext;
    protected final      SoContextImpl               context;
    private final        SocketAddress               localAddr;
    private final        SocketAddress               remoteAddr;
    private final        long                        createdTime;
    private              long                        lastSndTime;
    private              long                        lastRcvTime;
    private              long                        lastNotifyRcvRetryTime;
    //
    private final        SoRcvCompletionHandler      rHandler;
    private final        SoSndCompletionHandler      wHandler;
    private final        AtomicBoolean               wStatus;
    //
    protected            PipeContextImpl             pipeContext;
    protected            PipeStack<ByteBuf, ByteBuf> pipeStack;
    //
    protected final      AtomicBoolean               closeStatus;
    protected final      Future<NetChannel>          closeFuture;

    NetChannel(long channelID, long createdTime, NetListen forListen, SocketAddress localAddr, SocketAddress remoteAddr,//
            AsynchronousSocketChannel channel, SoRcvCompletionHandler rHandler, SoSndCompletionHandler wHandler, SoSndContext wContext) {
        this.channelID = channelID;
        this.forListen = forListen;
        this.createdTime = createdTime;
        this.lastSndTime = createdTime;
        this.lastRcvTime = createdTime;

        this.channel = channel;
        this.wContext = wContext;
        this.context = wContext.getContext();
        this.localAddr = localAddr;
        this.remoteAddr = remoteAddr;
        this.closeStatus = new AtomicBoolean(false);
        this.closeFuture = new BasicFuture<>();

        this.rHandler = rHandler;
        this.wHandler = wHandler;
        this.wStatus = new AtomicBoolean(false);
    }

    protected void initPipe(PipeContextImpl pipeContext, PipeStack<?, ?> pipeStack) {
        this.pipeContext = pipeContext;
        this.pipeStack = (PipeStack<ByteBuf, ByteBuf>) pipeStack;
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

    @Override
    public SocketAddress getLocalAddr() {
        return this.localAddr;
    }

    @Override
    public SocketAddress getRemoteAddr() {
        return this.remoteAddr;
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

    /* Receive data without concurrency */
    final void notifyRcv(int retryCnt) {
        if (retryCnt == 0) {
            this.lastRcvTime = System.currentTimeMillis();
            this.lastNotifyRcvRetryTime = 0;
        }

        if (retryCnt > 3 && (this.lastNotifyRcvRetryTime + 3000) < System.currentTimeMillis()) {
            logger.info("rcv(" + this.channelID + ") the receive buffer is full, ");
            this.lastNotifyRcvRetryTime = System.currentTimeMillis();
        }

        try {
            //The root Buffer cannot be deallocated
            ByteBuf rcvByteBuf = this.rHandler.getRcvBuffer();
            Object[] sndBufSet = this.pipeStack.rcvLayer(this.pipeContext, new ByteBuf[] { new ByteBufSafe(rcvByteBuf) });
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
            this.context.unsafeCloseChannel(this.channelID, msg, e);
        } finally {
            this.pipeContext.clearFlash(); // Cleanup must be performed because there are times when PipeChainRoot is not used
        }
    }

    /* Receive error */
    final void notifyError(Throwable e) {
        if (!(e instanceof SoReadTimeoutException)) {
            this.lastRcvTime = System.currentTimeMillis();
        }

        try {
            //The root Buffer cannot be deallocated
            Object[] sndBufSet = this.pipeStack.soError(this.pipeContext, e);
            for (Object sndBuf : sndBufSet) {
                ByteBuf buf = (ByteBuf) sndBuf;
                if (buf.hasReadable()) {
                    appendSoSndTask(new SoSndData(buf, new BasicFuture<>(), this));
                }
            }
        } catch (Throwable ee) {
            // It is not executed unless the exception is thrown in PipeReceiveListener.onError(...)
            String msg = "invoker pipeline failed: " + e.getMessage();
            logger.error("rcv(" + this.channelID + ") " + msg, e);

            this.closeStatus.set(true);
            this.context.unsafeCloseChannel(this.channelID, msg, e);
        } finally {
            this.pipeContext.clearFlash(); // Cleanup must be performed because there are times when PipeChainRoot is not used
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
    public Future<NetChannel> sendData(Object writeData) {
        if (writeData == null) {
            return new BasicFuture<>(this);
        }

        Future<NetChannel> future = new BasicFuture<>();
        try {
            Object[] sndByteBuf = this.pipeStack.sndLayer(this.pipeContext, new Object[] { writeData });
            AtomicInteger cnt = new AtomicInteger(sndByteBuf.length);
            for (Object buf : sndByteBuf) {
                Future<NetChannel> itemFuture = new BasicFuture<>();
                new BasicFuture<>().onFailed(f -> {
                    future.failed(f.getCause());
                }).onCompleted(f -> {
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
            this.pipeContext.clearFlash(); // Cleanup must be performed because there are times when PipeChainRoot is not used
        }
        return future;
    }

    /** flash */
    public Future<NetChannel> flash() {
        Future<NetChannel> future = new BasicFuture<>();
        appendSoSndTask(new SoSndData(SoSndData.EMPTY_DATA, future, this));
        return future;
    }

    private void appendSoSndTask(SoSndData wTask) {
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
                    notifyError(new SoReadTimeoutException("no data was received with " + this.waitTimeMs + " milliseconds."));
                }
            }
        }

        long waitTimeMs = unit.toMillis(timeout);
        this.context.newTimeout(new CheckTimeout(this.lastRcvTime, waitTimeMs), timeout, unit);
    }
}