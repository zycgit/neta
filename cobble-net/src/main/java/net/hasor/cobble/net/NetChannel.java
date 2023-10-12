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
package net.hasor.cobble.net;
import net.hasor.cobble.bytebuf.ByteBuf;
import net.hasor.cobble.bytebuf.ByteBufAllocator;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网络通道
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class NetChannel implements Channel<NetChannel> {
    private static final ByteBuf                   EMPTY_DATA = ByteBufAllocator.DEFAULT.arrayBuffer(0);
    private final        long                      channelID;
    private final        NetListen                 forListen;
    protected final      AsynchronousSocketChannel channel;
    protected final      SoContextImpl             context;
    protected final      SoResManager              rm;
    private final        SocketAddress             localAddr;
    private final        SocketAddress             remoteAddr;
    private final        long                      createdTime;
    private              long                      lastSndTime;
    private              long                      lastRcvTime;
    //
    private final        SoRcvCompletionHandler    rHandler;
    private final        Object                    rSyncLock;
    //
    protected final      Queue<SoSndData>          wQueue;
    private final        AtomicBoolean             wStatus;
    private final        SoSndCompletionHandler    wHandler;
    //
    protected final      AtomicBoolean             closeStatus;
    protected final      Future<NetChannel>        closeFuture;

    NetChannel(long channelID, long createdTime, NetListen forListen, SocketAddress localAddr, SocketAddress remoteAddr, AsynchronousSocketChannel channel, //
            SoRcvCompletionHandler rHandler, SoSndCompletionHandler wHandler, SoContextImpl context, SoResManager rm) {
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
        this.rSyncLock = new Object();

        this.wQueue = new ConcurrentLinkedQueue<>();
        this.wStatus = new AtomicBoolean(false);
        this.wHandler = wHandler;

    }

    @Override
    public long getChannelID() {
        return this.channelID;
    }

    @Override
    public boolean isListen() {
        return false;
    }

    /** 连接建立时间 */
    public long getCreatedTime() {
        return this.createdTime;
    }

    @Override
    public long getLastActiveTime() {
        return Math.max(this.lastRcvTime, this.lastSndTime);
    }

    /** 最后一次发送数据的时间 */
    public long getLastSndTime() {
        return this.lastSndTime;
    }

    /** 最后一次接收数据的时间 */
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

    /** 本地 Socket 地址 */
    public SocketAddress getLocalAddr() {
        return this.localAddr;
    }

    /** 远程 Socket 地址 */
    public SocketAddress getRemoteAddr() {
        return this.remoteAddr;
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

    public Future<NetChannel> closeNow() {
        if (this.channel.isOpen() && this.closeStatus.compareAndSet(false, true)) {
            new SoCloseTask(this.channelID, this.context).run();
        }
        this.closeFuture.completed(this);
        return this.closeFuture;
    }

    /** 写数据 */
    public Future<NetChannel> sendData(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new BasicFuture<>(this);
        }

        ByteBuf wrap = ByteBufAllocator.DEFAULT.wrap(bytes);
        wrap.skipWritableBytes(bytes.length);
        wrap.markWriter();
        return sendData(wrap);
    }

    /** 写数据 */
    public Future<NetChannel> sendData(ByteBuffer byteBuf) {
        if (byteBuf == null || !byteBuf.hasRemaining()) {
            return new BasicFuture<>(this);
        }

        ByteBuf wrap = ByteBufAllocator.DEFAULT.wrap(byteBuf);
        wrap.skipWritableBytes(byteBuf.position());
        wrap.markWriter();
        return sendData(wrap);
    }

    /** 写数据 */
    public Future<NetChannel> sendData(ByteBuf byteBuf) {
        if (byteBuf == null || !byteBuf.hasReadable()) {
            return new BasicFuture<>(this);
        }

        Future<NetChannel> future = new BasicFuture<>();
        appendSoSndTask(new SoSndData(byteBuf, future, this));
        return future;
    }

    /** 刷出 */
    public Future<NetChannel> flash() {
        Future<NetChannel> future = new BasicFuture<>();
        appendSoSndTask(new SoSndData(EMPTY_DATA, future, this));
        return future;
    }

    private void appendSoSndTask(SoSndData wTask) {
        if (this.closeStatus.get()) {
            wTask.failed(new ClosedChannelException());
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

    //
    //
    //
    //

    final int notifyRcv(boolean rcvFull) {
        if (!rcvFull) {
            this.lastRcvTime = System.currentTimeMillis();
        }

        synchronized (this.rSyncLock) {
            this.rSyncLock.notifyAll();
        }

        //        if (rcvFull) {
        //            System.out.println("rcv Full " + this);
        //        }

        ByteBuf buffer = this.rHandler.getRcvBuffer();
        String line = buffer.readLine();
        buffer.markReader();

        if (line != null) {
            System.out.println("rcvChannel " + channelID + ", data=" + line);
            this.sendData("echo ".getBytes());
            this.sendData((line + "\n").getBytes());
            return 0;
        }

        // 返回任务希望在延迟多久后在处理接收事件
        return 0;//this.context.getConfig().getRetryIntervalMs();
    }

    //    /** 读数据 */
    //    public Future<NetChannel> readData() {
    //        return null;
    //        //        return this.rChannel.getRcvBuffer();
    //    }

}