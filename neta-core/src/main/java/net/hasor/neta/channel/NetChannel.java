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
import net.hasor.neta.bytebuf.ByteBufAdapter;
import net.hasor.neta.bytebuf.ByteBufAllocator;

import java.io.IOException;
import java.io.PrintStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.NotYetConnectedException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A tcp network channel
 * the channel that binds to the Application layer network protocol stack.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class NetChannel extends AttributeChannel<NetChannel> implements NetDuplexChannel<NetChannel> {
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
    protected final      SoRcvCompletionHandler rHandler;
    protected final      SoSndCompletionHandler wHandler;
    private final        AtomicBoolean          wStatus;
    //
    protected            ProtoContextImpl       protoCtx;
    protected            ProtoStack<ByteBuf>    protoStack;
    //
    private final        boolean                netLog;
    protected final      AtomicBoolean          closeStatus;
    protected final      Future<NetChannel>     closeFuture;

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

    protected void initChannel(ProtoContextImpl protoCtx, ProtoStack<ByteBuf> protoStack) {
        this.protoCtx = protoCtx;
        this.protoStack = Objects.requireNonNull(protoStack, "ProtoStack is null.");
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

    /** Returns the receiving Handler state */
    public SoHandlerStatus getRcvHandlerStatus() {
        return this.rHandler.getStatus();
    }

    /** Returns the send Handler state */
    public SoHandlerStatus getSndHandlerStatus() {
        return this.wHandler.getStatus();
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
    public boolean isShutdownInput() {
        return this.channel.isShutdownInput();
    }

    @Override
    public void ignoreReadEofFlag() {
        this.channel.ignoreReadEofFlag();
    }

    @Override
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

    @Override
    public boolean isShutdownOutput() {
        return this.channel.isShutdownOutput();
    }

    @Override
    public void shutdownOutput() {
        try {
            this.channel.shutdownOutput();
        } catch (NotYetConnectedException | IOException e) {
            logger.warn("channel(" + this.channelID + ") shutdownOutput " + e.getMessage(), e);
        }
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
    public SoContext getContext() {
        return this.context;
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

    /** Number of bytes received */
    public long getRcvBytes() {
        return this.rHandler.getCounterBytes();
    }

    /** Number of bytes send */
    public long getSndBytes() {
        return this.wHandler.getCounterBytes();
    }

    /** Returns the received buffer size. */
    public int getRcvBufferSize() {
        return this.rHandler.getRcvBuffer().capacity();
    }

    /** Returns the send buffer size. */
    public int getSndBufferSize() {
        return this.wHandler.getSndBuffer().capacity();
    }

    /** Returns the size of the received buffer used. */
    public int getRcvBufferUsed() {
        ByteBuf rcvBuf = this.rHandler.getRcvBuffer();
        return rcvBuf.capacity() - rcvBuf.writableBytes();
    }

    /** Returns the size of the send buffer used. */
    public int getSndBufferUsed() {
        ByteBuf sndBuf = this.wHandler.getSndBuffer();
        return sndBuf.capacity() - sndBuf.writableBytes();
    }

    /** Returns the number of ProtoStack received slots. */
    public int getRcvSlotSize() {
        return this.protoStack.getRcvSlotSize();
    }

    /** Returns the number of ProtoStack send slots. */
    public int getSndSlotSize() {
        return this.protoStack.getSndSlotSize();
    }

    /* Receive data without concurrency */
    synchronized final void notifyRcv(int dataSize, int retryCnt) {
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
            this.protoCtx.flash(ProtoContext.SO_CHANNEL_RETRY_CNT, retryCnt);

            if (this.protoStack.getRcvSlotSize() == 0) {
                logger.info("rcv(" + this.channelID + ") the ProtoStack slot is full.");
                this.protoStack.onRcvError(this.protoCtx, null, ProtoFullException.INSTANCE);
                return;
            }

            //The root Buffer cannot be deallocated
            ByteBuf rcvByteBuf = this.rHandler.getRcvBuffer();
            Object[] sndBufSet = this.protoStack.onRcvMessage(this.protoCtx, null, new ByteBuf[] { new ByteBufSafe(rcvByteBuf) });
            for (Object sndBuf : sndBufSet) {
                ByteBuf buf = (ByteBuf) sndBuf;
                if (buf.hasReadable()) {
                    appendSoSndTask(new SoSndData(buf, new BasicFuture<>(), this));
                }
            }
        } catch (Throwable e) {
            // It is not executed unless the exception is thrown in ProtoReceiveListener.onError(...)
            String msg = "invoker ProtoStack failed: " + e.getMessage();
            logger.error("rcv(" + this.channelID + ") " + msg, e);

            this.closeStatus.set(true);
            this.context.syncUnsafeCloseChannel(this.channelID, msg, e);
        } finally {
            this.protoCtx.clearFlash(); // Cleanup must be performed because there are times when ProtoChainRoot is not used
        }
    }

    /* Receive error */
    synchronized final void notifyError(boolean isRcv, Throwable e) {
        try {
            //The root Buffer cannot be deallocated
            Object[] sndBufSet;
            if (isRcv) {
                sndBufSet = this.protoStack.onRcvError(this.protoCtx, null, e);
            } else {
                sndBufSet = this.protoStack.onSndError(this.protoCtx, null, e);
            }

            for (Object sndBuf : sndBufSet) {
                ByteBuf buf = (ByteBuf) sndBuf;
                if (buf.hasReadable()) {
                    appendSoSndTask(new SoSndData(buf, new BasicFuture<>(), this));
                }
            }
        } catch (Throwable ee) {
            // It is not executed unless the exception is thrown in ProtoReceiveListener.onError(...)
            String msg = "invoker ProtoStack failed: " + ee.getMessage();
            logger.error("rcv(" + this.channelID + ") " + msg, ee);

            this.closeStatus.set(true);
            this.context.syncUnsafeCloseChannel(this.channelID, msg, e);
        } finally {
            this.protoCtx.clearFlash(); // Cleanup must be performed because there are times when ProtoChainRoot is not used
        }
    }

    @Deprecated
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
        Objects.requireNonNull(writeData, "the send data is null.");
        return this.sendOrFlush(writeData, null);
    }

    /**
     * sent data to remote, The network IO transfer operation is performed asynchronously.
     * <p>data goes through the application layer network protocol stack</p>
     */
    public Future<NetChannel> sendData(Object writeData, String stackName) {
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

    private Future<NetChannel> sendOrFlush(Object writeData, String stackName) {
        Future<NetChannel> future = newFutureForSend();
        if (future.isDone()) {
            return future;
        }

        try {
            boolean isFlush = writeData == null;
            Object[] sndByteBuf;
            if (isFlush) {
                sndByteBuf = this.protoStack.onSndMessage(this.protoCtx, stackName, ArrayUtils.EMPTY_OBJECT_ARRAY);
            } else {
                sndByteBuf = this.protoStack.onSndMessage(this.protoCtx, stackName, new Object[] { writeData });
            }

            ByteBuf merged = ByteBufAllocator.DEFAULT.arrayBuffer();
            for (Object buf : sndByteBuf) {
                if (buf instanceof byte[]) {
                    merged.writeBytes((byte[]) buf);
                } else if (buf instanceof ByteBuffer) {
                    merged.write((ByteBuffer) buf);
                } else if (buf instanceof ByteBuf) {
                    merged.write((ByteBuf) buf);
                    ((ByteBuf) buf).markReader();
                } else {
                    throw new ClassCastException(writeData.getClass().getName() + " cannot be cast to (byte[] / ByteBuffer / ByteBuf)");
                }
            }
            merged.markWriter();
            appendSoSndTask(new SoSndData(merged, future, this));
            //            AtomicInteger cnt = new AtomicInteger(sndByteBuf.length);
            //            for (Object buf : sndByteBuf) {
            //                Future<NetChannel> itemFuture = new BasicFuture<>();
            //                itemFuture.onFailed(f -> {
            //                    future.failed(f.getCause());
            //                }).onFinal(f -> {
            //                    cnt.decrementAndGet();
            //                    if (cnt.get() == 0) {
            //                        future.completed(this);
            //                    }
            //                });
            //
            //                if (buf instanceof byte[]) {
            //                    ByteBuf wrap = ByteBufAllocator.DEFAULT.wrap((byte[]) buf);
            //                    appendSoSndTask(new SoSndData(wrap, itemFuture, this));
            //                } else if (buf instanceof ByteBuffer) {
            //                    ByteBuf wrap = ByteBufAllocator.DEFAULT.wrap((ByteBuffer) buf);
            //                    appendSoSndTask(new SoSndData(wrap, itemFuture, this));
            //                } else if (buf instanceof ByteBuf) {
            //                    ByteBuf wrap = (ByteBuf) buf;
            //                    appendSoSndTask(new SoSndData(wrap, itemFuture, this));
            //                } else {
            //                    throw new ClassCastException(writeData.getClass().getName() + " cannot be cast to (byte[] / ByteBuffer / ByteBuf)");
            //                }
            //            }
        } catch (Throwable e) {
            logger.error("snd(" + channelID + ") failed, " + e.getMessage(), e);
            future.failed(e);
        } finally {
            this.protoCtx.clearFlash(); // Cleanup must be performed because there are times when ProtoChainRoot is not used
        }
        return future;
    }

    private Future<NetChannel> newFutureForSend() {
        Future<NetChannel> future = new BasicFuture<>();

        if (this.isShutdownOutput()) {
            logger.info("snd(" + this.channelID + ") the channel is shutdownOutput.");
            future.failed(SoOutputCloseException.INSTANCE);
            return future;
        }

        if (this.protoStack.getSndSlotSize() == 0) {
            logger.info("snd(" + this.channelID + ") the ProtoStack slot is full.");
            future.failed(ProtoFullException.INSTANCE);
            return future;
        }

        if (this.closeStatus.get()) {
            logger.info("snd(" + this.channelID + ") the channel is closed.");
            future.failed(SoCloseException.INSTANCE);
            return future;
        }

        return future;
    }

    private void appendSoSndTask(SoSndData wTask) {
        if (this.netLog) {
            logger.info("snd(" + this.channelID + ") appendSoSndTask, dataSize is " + wTask.getDataSize() + ", closeStatus is " + this.closeStatus.get());
        }

        synchronized (this.wStatus) {
            this.wContext.offer(wTask);

            if (this.wStatus.compareAndSet(false, true)) {
                SoSndTask sendTask = new SoSndTask(this.channelID, this.channel, this.wHandler, this.wContext);
                this.wContext.submitTask(sendTask, this).onCompleted(f -> {
                    checkOrSend(sendTask);
                });
            }
        }
    }

    private void checkOrSend(SoSndTask sendTask) {
        synchronized (this.wStatus) {
            this.lastSndTime = System.currentTimeMillis();
            if (this.wContext.isEmpty()) {
                this.wStatus.compareAndSet(true, false);
            } else {
                this.wContext.submitTask(sendTask, this).onCompleted(f -> {
                    checkOrSend(sendTask);
                });
            }
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