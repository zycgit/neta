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
import net.hasor.cobble.io.IOUtils;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网络通道
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class NetChannel implements Closeable {
    private static final ByteBuf                   EMPTY_DATA = ByteBufAllocator.DEFAULT.arrayBuffer(0);
    private final        long                      channelID;
    private final        AsynchronousSocketChannel channel;
    private final        SocketContext             context;
    private final        long                      beginTime;
    private              long                      lastSndTime;
    private              long                      lastRcvTime;
    //
    private final        SoRcvCompletionHandler    rHandler;
    private final        Object                    rSyncLock;
    //
    private final        Queue<SoSndData>          wQueue;
    private final        AtomicBoolean             wStatus;
    private final        SoSndCompletionHandler    wHandler;

    NetChannel(long channelID, long beginTime, AsynchronousSocketChannel channel, SoRcvCompletionHandler rHandler, SoSndCompletionHandler wHandler, SocketContext context) {
        this.channelID = channelID;
        this.beginTime = beginTime;
        this.lastSndTime = beginTime;
        this.lastRcvTime = beginTime;
        this.channel = channel;
        this.context = context;

        this.rHandler = rHandler;
        this.rSyncLock = new Object();

        this.wQueue = new ConcurrentLinkedQueue<>();
        this.wStatus = new AtomicBoolean(false);
        this.wHandler = wHandler;
    }

    /** Socket 连接通道 ID */
    public long getChannelID() {
        return this.channelID;
    }

    /** 连接建立时间 */
    public long getBeginTime() {
        return this.beginTime;
    }

    /** 最后一次发送数据的时间 */
    public long getLastSndTime() {
        return this.lastSndTime;
    }

    /** 最后一次接收数据的时间 */
    public long getLastRcvTime() {
        return this.lastRcvTime;
    }

    /** Socket 连接通道是否关闭 */
    public boolean isClose() {
        return !this.channel.isOpen();
    }

    /** 关闭 Socket 通道 */
    @Override
    public void close() {
        if (!isClose()) {
            IOUtils.closeQuietly(this.channel);
        }
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

    /** 写数据 */
    public Future<NetChannel> sendEmpty() {
        Future<NetChannel> future = new BasicFuture<>();
        appendSoSndTask(new SoSndData(EMPTY_DATA, future, this));
        return future;
    }

    private void appendSoSndTask(SoSndData wTask) {
        this.wQueue.offer(wTask);

        if (this.wStatus.compareAndSet(false, true)) {
            SoSndContext wContext = new SoSndContext(this.beginTime, this.context, this.wQueue);

            // queue -> sndBuffer and sending
            SoSndCopyTask task = new SoSndCopyTask(this.channelID, this.channel, this.wHandler, wContext);

            this.context.submitSoTask(task, this).onCompleted(f -> {
                this.lastSndTime = System.currentTimeMillis();
                if (this.wQueue.isEmpty()) {
                    this.wStatus.compareAndSet(true, false);
                } else {
                    this.context.submitSoTask(task, this);
                }
            });
        }
    }

    //
    //
    //
    //
    final void notifyRcv() {
        this.lastRcvTime = System.currentTimeMillis();

        synchronized (this.rSyncLock) {
            this.rSyncLock.notifyAll();
        }

        ByteBuf buffer = this.getRecByteBuf();
        StringBuilder sb = new StringBuilder();
        while (buffer.expect("\r\n", StandardCharsets.US_ASCII) >= 0) {
            sb.append(buffer.readExpect("\r\n", StandardCharsets.US_ASCII));
            sb.append("\n");
            buffer.markReader();
        }

        if (sb.length() > 0) {
            System.out.println("rcvChannel " + channelID + ", data=" + sb.toString());
            this.sendData("echo ".getBytes());
            this.sendData((sb.toString() + "\n").getBytes());
        }
    }

    public ByteBuf getRecByteBuf() {
        return this.rHandler.getRcvBuffer();
    }

    //    /** 读数据 */
    //    public Future<NetChannel> readData() {
    //        return null;
    //        //        return this.rChannel.getRcvBuffer();
    //    }

}