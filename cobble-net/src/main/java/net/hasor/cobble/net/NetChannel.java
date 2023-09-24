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
import net.hasor.cobble.logging.Logger;

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
public class NetChannel {
    private static final Logger                    logger = Logger.getLogger(NetChannel.class);
    private final        long                      channelID;
    private final        AsynchronousSocketChannel channel;
    private final        SocketContext             context;
    //
    private final        SoRcvCompletionHandler    rHandler;
    private final        Object                    rSyncLock;
    //
    private final        Queue<SoSndData>          wQueue;
    private final        AtomicBoolean             wStatus;
    private final        SoSndCompletionHandler    wHandler;
    private final        Object                    wSyncLock;

    NetChannel(long channelID, AsynchronousSocketChannel channel, SoRcvCompletionHandler rHandler, SoSndCompletionHandler wHandler, SocketContext context) {
        this.channelID = channelID;
        this.channel = channel;
        this.context = context;

        this.rHandler = rHandler;
        this.rSyncLock = new Object();

        this.wQueue = new ConcurrentLinkedQueue<>();
        this.wStatus = new AtomicBoolean(false);
        this.wHandler = wHandler;
        this.wSyncLock = new Object();
    }

    public long getChannelID() {
        return this.channelID;
    }

    final void notifyRcv() {
        synchronized (this.rSyncLock) {
            this.rSyncLock.notifyAll();
        }

        if (this.channelID % 2 == 0) {
            return;
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
            this.sendData("Hello ".getBytes());
            this.sendData("Word\n".getBytes());
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

    /** 写数据 */
    public Future<NetChannel> sendData(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new BasicFuture<>(this);
        }

        Future<NetChannel> future = new BasicFuture<>();
        ByteBuf buf = ByteBufAllocator.DEFAULT.wrap(bytes);

        synchronized (this.wSyncLock) {
            appendSoSndTask(new SoSndData(buf, future, this));
        }

        return future;
    }

    /** 写数据 */
    public Future<NetChannel> sendData(ByteBuf byteBuf) {
        if (byteBuf == null || !byteBuf.hasReadable()) {
            return new BasicFuture<>(this);
        }

        Future<NetChannel> future = new BasicFuture<>();

        synchronized (this.wSyncLock) {
            appendSoSndTask(new SoSndData(byteBuf, future, this));
        }

        return future;
    }

    /** 写数据 */
    public Future<NetChannel> sendData(ByteBuffer byteBuf) {
        if (byteBuf == null || !byteBuf.hasRemaining()) {
            return new BasicFuture<>(this);
        }

        Future<NetChannel> future = new BasicFuture<>();
        ByteBuf buf = ByteBufAllocator.DEFAULT.wrap(byteBuf);

        synchronized (this.wSyncLock) {
            appendSoSndTask(new SoSndData(buf, future, this));
        }

        return future;
    }

    private void appendSoSndTask(SoSndData wTask) {
        this.wQueue.offer(wTask);

        if (this.wStatus.compareAndSet(false, true)) {
            SoSndContext wContext = new SoSndContext(this.context, this.wQueue);

            // queue order sending.
            SoSndTask task = new SoSndTask(this.channelID, this.channel, this.wHandler, wContext);

            // start dispatch, then finish dispatch wStatus set false.
            this.context.submitSoTask(task, this).onCompleted(net -> {
                if (wContext.isEmpty()) {
                    this.wStatus.compareAndSet(true, false);
                } else {
                    this.context.submitSoTask(task, net);
                }
            });
        }
    }
}