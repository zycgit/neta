/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.channel;
import java.io.PrintStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.ArrayUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.future.Futures;
import net.hasor.cobble.concurrent.timer.Timeout;
import net.hasor.cobble.concurrent.timer.TimerTask;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * A connected socket channel with a bound application-layer protocol stack.
 * Supports async {@link #sendData}/{@link #flush} and read-timeout waiting.
 * <pre>
 *  Remote ──► [ByteBuf] ──► Decoder(n) ──► … ──► Decoder(0) ──► Application
 *  Remote ◄── [ByteBuf] ◄── Encoder(n) ◄── … ◄── Encoder(0) ◄── Application
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class NetChannel extends SoAttrChannel<NetChannel> implements SoChannel<NetChannel> {
    private static final Logger              logger              = Logger.getLogger(NetChannel.class);
    private static final ByteBuf[]           EMPTY_BYTEBUF_ARRAY = new ByteBuf[0];
    protected final      AsyncChannel        asyncChannel;
    protected final      NetListen           forListen;
    protected final      SoSndContext        wContext;
    protected final      SoContextService    soContext;
    protected final      NetMonitor          monitor;
    protected final      ProtoStack<Object>  protoStack;
    protected final      AtomicBoolean       closeStatus;
    protected final      Future<NetChannel>  closeFuture;
    protected final      Object              readTimeoutSyncObj;
    //
    final                ProtoContextService protoCtx;
    private final        long                channelId;
    private final        Object[]            singleRcvBuf        = new Object[1]; // reusable 1-element array for single RCV
    protected volatile   int                 readWaiters;

    protected NetChannel(long channelId, NetMonitor monitor, NetListen forListen, ProtoInitializer initializer, AsyncChannel asyncChannel, SoContextService soContext) {
        this.channelId = channelId;
        this.asyncChannel = asyncChannel;
        this.forListen = forListen;
        this.monitor = monitor;
        this.readTimeoutSyncObj = new Object();
        this.wContext = new SoSndContext();
        this.soContext = soContext;

        this.protoCtx = new ProtoContextService(this, soContext);
        this.protoStack = this.protoCtx.getChainRoot();
        initializer.config(this.protoCtx);
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
        return this.soContext;
    }

    protected AsyncChannel getAsyncChannel() {
        return this.asyncChannel;
    }

    /** Returns the traffic and timing monitor attached to this channel. */
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
            if (this.soContext.getConfig().isPrintLog()) {
                logger.info("[NET] ch=" + this.channelId + " close()");
            }
            if (this.asyncChannel.isOpen()) {
                SoCloseTask task = new SoCloseTask(this.channelId, this.soContext, false);
                this.soContext.submitSoTask(task, this).onCompleted(f -> {
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
            new SoCloseTask(this.channelId, this.soContext, true).run();
        }
        this.closeFuture.completed(this);
    }

    @Override
    public void onClose(SoChannelListener<SoChannel<?>> listener) {
        this.closeFuture.onCompleted(f -> listener.onEvent(this));
    }

    /** Number of bytes received */
    public long getRcvBytes() {
        return this.monitor.getRcvCounterBytes();
    }

    /** Number of bytes send */
    public long getSndBytes() {
        return this.monitor.getSndCounterBytes();
    }

    /** Feeds raw received data through the protocol stack; must be called from a single thread. */
    protected void notifyRcv(Object[] rcvBytes) throws Throwable {
        if (this.readWaiters > 0) {
            synchronized (this.readTimeoutSyncObj) {
                this.readTimeoutSyncObj.notifyAll();
            }
        }

        Object[] dataArray = this.protoStack.onRcvMessage(this.protoCtx, null, rcvBytes);
        if (dataArray != null && dataArray.length > 0) {
            appendSoSndTask(toSoSndData(Futures.buildNoop(), dataArray));
        }
    }

    /** Single-element fast path for {@link #notifyRcv}; reuses a cached array to avoid allocation. */
    public void notifyRcvSingle(Object rcvByte) throws Throwable {
        if (this.readWaiters > 0) {
            synchronized (this.readTimeoutSyncObj) {
                this.readTimeoutSyncObj.notifyAll();
            }
        }

        this.singleRcvBuf[0] = rcvByte;
        try {
            Object[] dataArray = this.protoStack.onRcvMessage(this.protoCtx, null, this.singleRcvBuf);
            if (dataArray != null && dataArray.length > 0) {
                appendSoSndTask(toSoSndData(Futures.buildNoop(), dataArray));
            }
        } finally {
            this.singleRcvBuf[0] = null; // avoid retaining reference
        }
    }

    /* Receive error */
    protected void notifyError(boolean isRcv, Throwable e) throws Throwable {
        Object[] dataArray = isRcv ?//
                this.protoStack.onRcvError(this.protoCtx, null, e) ://
                this.protoStack.onSndError(this.protoCtx, null, e);
        if (dataArray != null && dataArray.length > 0) {
            appendSoSndTask(toSoSndData(Futures.buildNoop(), dataArray));
        }
    }

    /** Fires a user-defined event into the inbound pipeline from this channel. */
    public <T> void fireUserEvent(Class<T> eventType, T event) {
        this.notifyUserEvent(true, null, eventType, event);
    }

    protected <T> void notifyUserEvent(boolean isRcv, String stackName, Class<T> eventType, T event) {
        if (isRcv) {
            this.soContext.notifyRcvUserEvent(this.channelId, stackName, SoUserEventObject.of(this, eventType, event));
        } else {
            this.soContext.notifySndUserEvent(this.channelId, stackName, SoUserEventObject.of(this, eventType, event));
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

    /** Flushes any pending outbound data through the full protocol stack. */
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
        }
        return future;
    }

    private SoSndData toSoSndData(Future<NetChannel> future, Object[] dataArray) {
        if (dataArray.length == 0) {
            return new SoSndData(0, EMPTY_BYTEBUF_ARRAY, future, this);
        }

        // Fast path: when all elements are ByteBuf (common echo path), reuse dataArray directly
        boolean allByteBuf = true;
        int sendSize = 0;
        for (int i = 0; i < dataArray.length; i++) {
            Object buf = dataArray[i];
            if (buf instanceof ByteBuf) {
                ByteBuf tmpBuf = (ByteBuf) buf;
                sendSize += tmpBuf.readableBytes();
                tmpBuf.markWriter();
            } else {
                allByteBuf = false;
                break;
            }
        }
        if (allByteBuf) {
            return new SoSndData(sendSize, dataArray, future, this);
        }

        // Slow path: wrap non-ByteBuf elements
        sendSize = 0;
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
                sendSize = sendSize + tmpBuf.readableBytes();
                tmpBuf.markWriter();
                wrap[i] = tmpBuf;
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

    private void appendSoSndTask(SoSndData wTask) {
        if (this.soContext.getConfig().isPrintLog()) {
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
        NetListen listen = this.getListen();
        SoConfig config = listen != null ? listen.getConfig() : this.getConfig();
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
                    soContext.notifyRcvChannelException(channelId, false, readTimeout);
                }
            }
        }

        long waitTimeMs = unit.toMillis(timeout);
        this.soContext.newTimeout(new CheckTimeout(this.monitor.getLastRcvTime(), waitTimeMs), timeout, unit);
    }

    /**
     * expect new data to be received within SoReadTimeoutMs
     * @see SoConfig#getSoReadTimeoutMs()
     */
    public void waitReceive() throws InterruptedException, SoReadTimeoutException {
        NetListen listen = this.getListen();
        SoConfig config = listen != null ? listen.getConfig() : this.getConfig();
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

        this.readWaiters++;
        try {
            synchronized (this.readTimeoutSyncObj) {
                this.readTimeoutSyncObj.wait(waitTimeMs);

                long cost = System.currentTimeMillis() - startTime;
                if (cost >= waitTimeMs) {
                    throw new SoReadTimeoutException("no data was received with " + waitTimeMs + " milliseconds.");
                }
            }
        } finally {
            this.readWaiters--;
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