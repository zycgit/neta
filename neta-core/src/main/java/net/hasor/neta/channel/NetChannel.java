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
import net.hasor.cobble.ArrayUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.timer.Timeout;
import net.hasor.cobble.concurrent.timer.TimerTask;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;

import java.io.IOException;
import java.io.PrintStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A tcp network channel
 * the channel that binds to the Application layer network protocol stack.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class NetChannel extends AttributeChannel<NetChannel> implements SoChannel<NetChannel> {
    private static final Logger              logger = Logger.getLogger(NetChannel.class);
    protected final      AsyncChannel        asyncChannel;
    protected final      NetListen           forListen;
    protected final      SoSndContext        wContext;
    protected final      SoContextService    context;
    protected final      NetMonitor          monitor;
    //
    protected final      ProtoContextService protoCtx;
    protected final      ProtoStack<ByteBuf> protoStack;
    protected final      AtomicBoolean       closeStatus;
    protected final      Future<NetChannel>  closeFuture;
    private final        long                channelId;
    private final        Object              readTimeoutSyncObj;

    protected NetChannel(long channelId, NetMonitor monitor, NetListen forListen, ProtoInitializer initializer, AsyncChannel asyncChannel, SoContextService context) throws IOException {
        this.channelId = channelId;
        this.asyncChannel = asyncChannel;
        this.forListen = forListen;
        this.monitor = monitor;
        this.readTimeoutSyncObj = new Object();
        this.wContext = new SoSndContext();
        this.context = context;

        this.protoCtx = new ProtoContextService(this, context);
        this.protoStack = initializer.config(this.protoCtx);
        this.closeStatus = new AtomicBoolean(false);
        this.closeFuture = new BasicFuture<>();
    }

    @Override
    public long getChannelId() {
        return this.channelId;
    }

    @Override
    public boolean isListen() {
        return false;
    }

    @Override
    public long getCreatedTime() {
        return this.monitor.getCreatedTime();
    }

    @Override
    public long getLastActiveTime() {
        return this.monitor.getLastActiveTime();
    }

    /** last sent data time */
    public long getLastSndTime() {
        return this.monitor.getLastSndTime();
    }

    /** last received data time */
    public long getLastRcvTime() {
        return this.monitor.getLastRcvTime();
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
        return this.asyncChannel.getLocalAddress();
    }

    @Override
    public SocketAddress getRemoteAddr() {
        return this.asyncChannel.getRemoteAddress();
    }

    @Override
    public SoContext getContext() {
        return this.context;
    }

    AsyncChannel getAsyncChannel() {
        return this.asyncChannel;
    }

    public NetMonitor getMonitor() {
        return this.monitor;
    }

    @Override
    public SoConfig getConfig() {
        return this.asyncChannel.getSoConfig();
    }

    @Override
    public <T> T findProtoContext(Class<T> serviceType) {
        return this.protoCtx.context(serviceType);
    }

    /** Returns the {@link NetListen} that accepts this channel */
    public NetListen getListen() {
        return this.forListen;
    }

    @Override
    public boolean isClose() {
        return !this.asyncChannel.isOpen() || this.closeStatus.get();
    }

    @Override
    public Future<NetChannel> close() {
        if (this.closeStatus.compareAndSet(false, true)) {
            if (this.asyncChannel.isOpen()) {
                SoCloseTask task = new SoCloseTask(this.channelId, this.context, false);
                this.context.submitSoTask(task, this).onCompleted(f -> {
                    this.closeFuture.completed(this);
                }).onFailed(f -> {
                    this.closeFuture.failed(f.getCause());
                });
            } else {
                this.closeFuture.completed(this);
            }
        }
        return this.closeFuture;
    }

    @Override
    public void closeNow() {
        if (this.asyncChannel.isOpen() && this.closeStatus.compareAndSet(false, true)) {
            logger.info("channel(" + this.channelId + ") closeNow");
            new SoCloseTask(this.channelId, this.context, true).run();
        }
        this.closeFuture.completed(this);
    }

    @Override
    public void onClose(SoCloseListener<SoChannel<?>> listener) {
        this.closeFuture.onCompleted(f -> listener.onClose(this));
    }

    /** Number of bytes received */
    public long getRcvBytes() {
        return this.monitor.getRcvCounterBytes();
    }

    /** Number of bytes send */
    public long getSndBytes() {
        return this.monitor.getSndCounterBytes();
    }

    /**
     * Returns protocol stack statistics
     */
    public ProtoStatistical getStatistical() {
        return this.protoStack.getStatistical();
    }

    /* Receive data without concurrency */
    protected final void notifyRcv(Object[] rcvBytes) throws Throwable {
        synchronized (this.readTimeoutSyncObj) {
            this.readTimeoutSyncObj.notifyAll();
        }

        try {
            Object[] dataArray = this.protoStack.onRcvMessage(this.protoCtx, null, rcvBytes);
            if (dataArray != null && dataArray.length > 0) {
                appendSoSndTask(toSoSndData(new BasicFuture<>(), dataArray));
            }
        } finally {
            this.protoCtx.clearFlash(); // Cleanup must be performed because there are times when ProtoChainRoot is not used
        }
    }

    /* Receive error */
    protected final void notifyError(boolean isRcv, Throwable e) throws Throwable {
        try {
            Object[] dataArray = isRcv ?//
                    this.protoStack.onRcvError(this.protoCtx, null, e) ://
                    this.protoStack.onSndError(this.protoCtx, null, e);
            if (dataArray != null && dataArray.length > 0) {
                appendSoSndTask(toSoSndData(new BasicFuture<>(), dataArray));
            }
        } finally {
            this.protoCtx.clearFlash(); // Cleanup must be performed because there are times when ProtoChainRoot is not used
        }
    }

    /**
     * sent data to remote, The network IO transfer operation is performed asynchronously.
     * <p>data goes through the application layer network protocol stack</p>
     */
    public Future<?> sendData(Object writeData) {
        Objects.requireNonNull(writeData, "the send data is null.");
        return this.sendOrFlush(new Object[] { writeData }, null);
    }

    /**
     * sent data to remote, The network IO transfer operation is performed asynchronously.
     * <p>data goes through the application layer network protocol stack</p>
     */
    public Future<NetChannel> sendData(Object writeData, String stackName) {
        Objects.requireNonNull(writeData, "the send data is null.");
        return this.sendOrFlush(new Object[] { writeData }, stackName);
    }

    /**
     * sent data to remote, The network IO transfer operation is performed asynchronously.
     * <p>data goes through the application layer network protocol stack</p>
     */
    public Future<?> sendData(Object[] writeData) {
        Objects.requireNonNull(writeData, "the send data is null.");
        return this.sendOrFlush(writeData, null);
    }

    /**
     * sent data to remote, The network IO transfer operation is performed asynchronously.
     * <p>data goes through the application layer network protocol stack</p>
     */
    public Future<NetChannel> sendData(Object[] writeData, String stackName) {
        Objects.requireNonNull(writeData, "the send data is null.");
        return this.sendOrFlush(writeData, stackName);
    }

    /** flash */
    public Future<NetChannel> flush() {
        return this.sendOrFlush(null, null);
    }

    /**
     * sent data to remote, The network IO transfer operation is performed asynchronously.
     * <p>data goes through the application layer network protocol stack</p>
     */
    public Future<?> flush(String stackName) {
        return this.sendOrFlush(null, stackName);
    }

    private Future<NetChannel> sendOrFlush(Object[] writeData, String stackName) {
        Future<NetChannel> future = newFutureForSend();
        if (future.isDone()) {
            return future;
        }

        try {
            Object[] dataArray;
            synchronized (this) {
                boolean isFlush = writeData == null || writeData.length == 0;
                dataArray = isFlush ?//
                        this.protoStack.onSndMessage(this.protoCtx, stackName, ArrayUtils.EMPTY_OBJECT_ARRAY) ://
                        this.protoStack.onSndMessage(this.protoCtx, stackName, writeData);
            }
            appendSoSndTask(toSoSndData(future, dataArray));
        } catch (Throwable e) {
            logger.error("snd(" + this.channelId + ") failed, " + e.getMessage(), e);
            future.failed(e);
        } finally {
            this.protoCtx.clearFlash(); // Cleanup must be performed because there are times when ProtoChainRoot is not used
        }
        return future;
    }

    private SoSndData toSoSndData(Future<NetChannel> future, Object[] dataArray) {
        if (dataArray.length == 0) {
            return new SoSndData(0, new ByteBuf[0], future, this);
        }

        int sendSize = 0;
        Object[] wrap = new Object[dataArray.length];
        for (int i = 0; i < dataArray.length; i++) {
            Object buf = dataArray[i];
            if (buf instanceof byte[]) {
                wrap[i] = ByteBuf.wrap((byte[]) buf);
                sendSize = sendSize + ((byte[]) buf).length;
            } else if (buf instanceof ByteBuffer) {
                wrap[i] = ByteBuf.wrap((ByteBuffer) buf);
                sendSize = sendSize + ((ByteBuffer) buf).remaining();
            } else if (buf instanceof ByteBuf) {
                ByteBuf tmpBuf = (ByteBuf) buf;
                sendSize = sendSize + ((ByteBuf) buf).readableBytes();

                wrap[i] = this.context.getByteBufAllocator().buffer(tmpBuf.readableBytes());
                ((ByteBuf) wrap[i]).writeBuffer(tmpBuf);
                ((ByteBuf) wrap[i]).markWriter();
                tmpBuf.markReader();
            } else {
                wrap[i] = buf;
            }
        }

        return new SoSndData(sendSize, wrap, future, this);
    }

    private Future<NetChannel> newFutureForSend() {
        Future<NetChannel> future = new BasicFuture<>();
        if (this.protoStack.getSndSlotSize() == 0) {
            logger.info("snd(" + this.channelId + ") the ProtoStack slot is full.");
            future.failed(ProtoFullException.INSTANCE);
            return future;
        }

        if (this.closeStatus.get()) {
            future.failed(new SoCloseException("the channel is closed."));
            return future;
        }

        return future;
    }

    private synchronized void appendSoSndTask(SoSndData wTask) {
        if (this.context.getConfig().isPrintLog()) {
            logger.info("snd(" + this.channelId + ") appendSoSndTask, dataSize is " + wTask.getDataSize() + ", closeStatus is " + this.closeStatus.get());
        }

        this.wContext.offer(wTask);
        this.asyncChannel.write(this, this.wContext);
    }

    /**
     * Sets a timer that will fire readTimeout if no network data is received within a specified amount of time.
     * @see SoConfig#getSoReadTimeoutMs()
     */
    public void setReadTimeout() {
        SoConfig config = this.getListen().getConfig();
        if (config.getSoReadTimeoutMs() > 0) {
            this.setReadTimeout(config.getSoReadTimeoutMs(), TimeUnit.MILLISECONDS);
        } else {
            this.setReadTimeout(6, TimeUnit.SECONDS);
        }
    }

    /**
     * Sets a timer that will fire readTimeout if no network data is received within a specified amount of time.
     */
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
                    SoReadTimeoutException readTimeout = new SoReadTimeoutException("no data was received with " + this.waitTimeMs + " milliseconds.");
                    context.notifyRcvChannelException(channelId, false, readTimeout);
                }
            }
        }

        long waitTimeMs = unit.toMillis(timeout);
        this.context.newTimeout(new CheckTimeout(this.monitor.getLastRcvTime(), waitTimeMs), timeout, unit);
    }

    /**
     * expect new data to be received within SoReadTimeoutMs
     * @see SoConfig#getSoReadTimeoutMs()
     */
    public void waitReceive() throws InterruptedException, SoReadTimeoutException {
        SoConfig config = this.getListen().getConfig();
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

    /**
     * Prints this pipline status and its backtrace to the System.out.
     */
    public void printStackTrace() {
        printStackTrace(System.out);
    }

    /**
     * Prints this pipline status and its backtrace to the specified print stream.
     * @param s {@code PrintStream} to use for output
     */
    public void printStackTrace(PrintStream s) {
        SoUtils.printStackTrace(s, this, this.protoStack);
    }
}