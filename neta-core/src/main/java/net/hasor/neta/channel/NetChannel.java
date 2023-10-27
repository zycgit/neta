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
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAdapter;

import java.net.SocketAddress;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tcp network channel
 *
 * the channel that binds to the Application layer network protocol stack.
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class NetChannel implements SoChannel<NetChannel> {
    private static final Logger                      logger = Logger.getLogger(NetChannel.class);
    private final        long                        channelID;
    private final        NetListen                   forListen;
    protected final      AsynchronousSocketChannel   channel;
    protected final      SoContextImpl               context;
    protected final      SoResManager                rm;
    private final        SocketAddress               localAddr;
    private final        SocketAddress               remoteAddr;
    private final        long                        createdTime;
    private              long                        lastSndTime;
    private              long                        lastRcvTime;
    //
    private final        SoRcvCompletionHandler      rHandler;
    protected final      Queue<SoSndData>            wQueue;
    private final        AtomicBoolean               wStatus;
    private final        SoSndCompletionHandler      wHandler;
    //
    protected            PipeContextImpl             pipeContext;
    protected            PipeStack<ByteBuf, ByteBuf> pipeStack;
    //
    protected final      AtomicBoolean               closeStatus;
    protected final      Future<NetChannel>          closeFuture;

    NetChannel(long channelID, long createdTime, NetListen forListen, SocketAddress localAddr, SocketAddress remoteAddr,//
            AsynchronousSocketChannel channel, SoRcvCompletionHandler rHandler, SoSndCompletionHandler wHandler, SoContextImpl context, SoResManager rm) {
        this.channelID = channelID;
        this.forListen = forListen;
        this.createdTime = createdTime;
        this.lastSndTime = createdTime;
        this.lastRcvTime = createdTime;

        this.channel = channel;
        this.context = context;
        this.rm = rm;
        this.localAddr = localAddr;
        this.remoteAddr = remoteAddr;
        this.closeStatus = new AtomicBoolean(false);
        this.closeFuture = new BasicFuture<>();

        this.rHandler = rHandler;
        this.wQueue = new ConcurrentLinkedQueue<>();
        this.wStatus = new AtomicBoolean(false);
        this.wHandler = wHandler;
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

    /**  last sent data time */
    public long getLastSndTime() {
        return this.lastSndTime;
    }

    /**  last received data time */
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

    /** local {@link SocketAddress} */
    public SocketAddress getLocalAddr() {
        return this.localAddr;
    }

    /** remote {@link SocketAddress} */
    public SocketAddress getRemoteAddr() {
        return this.remoteAddr;
    }

    /** Returns the {@link NetListen} that accepts this channel  */
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
                SoCloseTask task = new SoCloseTask(this.channelID, this.context);
                this.context.submitSoTask(this.rm, task, this).onCompleted(f -> {
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
            new SoCloseTask(this.channelID, this.context).run();
        }
        this.closeFuture.completed(this);
        return this.closeFuture;
    }

    /* Receive data without concurrency */
    final void notifyRcv(int retryCnt) {
        if (retryCnt == 0) {
            this.lastRcvTime = System.currentTimeMillis();
        }

        try {
            //The root Buffer cannot be deallocated
            ByteBuf rcvByteBuf = new ByteBufAdapter(this.rHandler.getRcvBuffer()) {
                @Override
                public void free() {
                }

                @Override
                public void close() {
                }
            };

            ByteBuf[] sndByteBuf = this.pipeStack.rcvLayer(this.pipeContext, rcvByteBuf);

            for (ByteBuf buf : sndByteBuf) {
                if (buf.hasReadable()) {
                    appendSoSndTask(new SoSndData(buf, new BasicFuture<>(), this));
                }
            }
        } catch (Throwable e) {
            logger.error("rcv(" + this.channelID + ") invoker pipeline failed: " + e.getMessage(), e);
            closeNow();
        } finally {
            this.pipeContext.clearFlash(); // Cleanup must be performed because there are times when PipeChainRoot is not used
        }
    }

    /**
     * sent data to remote, The network IO transfer operation is performed asynchronously.
     *
     * <p>data goes through the application layer network protocol stack</p>
     */
    public Future<NetChannel> sendData(Object writeData) {
        if (writeData == null) {
            return new BasicFuture<>(this);
        }

        Future<NetChannel> future = new BasicFuture<>();
        try {
            ByteBuf[] sndByteBuf = this.pipeStack.sndLayer(this.pipeContext, writeData);
            AtomicInteger cnt = new AtomicInteger(sndByteBuf.length);
            for (ByteBuf buf : sndByteBuf) {
                Future<NetChannel> itemFuture = new BasicFuture<>();
                new BasicFuture<>().onFailed(f -> {
                    future.failed(f.getCause());
                }).onCompleted(f -> {
                    cnt.decrementAndGet();
                    if (cnt.get() == 0) {
                        future.completed(this);
                    }
                });

                appendSoSndTask(new SoSndData(buf, itemFuture, this));
            }
        } catch (Throwable e) {
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
            wTask.failed(new ClosedChannelException());
            return;
        }

        this.wQueue.offer(wTask);

        if (this.wStatus.compareAndSet(false, true)) {
            SoSndContext wContext = new SoSndContext(this.createdTime, this.context, this.rm, this.wQueue);

            // queue -> sndBuffer and sending
            SoSndCopyTask task = new SoSndCopyTask(this.channelID, this.channel, this.wHandler, wContext);

            wContext.submitTask(task, this).onCompleted(f -> {
                this.lastSndTime = System.currentTimeMillis();
                if (this.wQueue.isEmpty()) {
                    this.wStatus.compareAndSet(true, false);
                } else {
                    wContext.submitTask(task, this);
                }
            });
        }
    }
}