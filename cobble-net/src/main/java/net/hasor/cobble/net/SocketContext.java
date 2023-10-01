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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * cobble 一个套接字 server 上的 channel 管理器
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SocketContext {
    private static final AtomicLong            nextID = new AtomicLong();
    private final        SocketConfig          config;
    private final        ExecutorService       ioExecutor;
    private final        ExecutorService       workerExecutor;
    private final        ByteBufAllocator      bufAllocator;
    private final        Map<Long, NetChannel> channelMap;

    public SocketContext(SocketConfig config, ExecutorService ioExec, ExecutorService worker) {
        this.config = config;
        this.ioExecutor = Objects.requireNonNull(ioExec);
        this.workerExecutor = Objects.requireNonNull(worker);
        this.bufAllocator = config.getBufAllocator() == null ? ByteBufAllocator.DEFAULT : config.getBufAllocator();
        this.channelMap = new ConcurrentHashMap<>();
    }

    public static long nextID() {
        return nextID.incrementAndGet();
    }

    public SocketConfig getConfig() {
        return this.config;
    }

    public ByteBuffer newSwapRcvBuf() {
        if (this.bufAllocator.isDirect()) {
            return ByteBuffer.allocateDirect(this.config.getRcvSwapBuf());
        } else {
            return ByteBuffer.allocate(this.config.getRcvSwapBuf());
        }
    }

    public ByteBuffer newSwapSndBuf() {
        if (this.bufAllocator.isDirect()) {
            return ByteBuffer.allocateDirect(this.config.getSndSwapBuf());
        } else {
            return ByteBuffer.allocate(this.config.getSndSwapBuf());
        }
    }

    public ByteBuf newLocalRcvBuf() {
        int bufSize = this.config.getRcvLocalBuf();
        return this.bufAllocator.buffer(bufSize);
    }

    public ByteBuf newLocalSndBuf() {
        int bufSize = this.config.getSndLocalBuf();
        return this.bufAllocator.buffer(bufSize);
    }

    //

    public int getConnectTimeoutMs() {
        return Math.max(10, this.config.getConnectTimeoutMs());
    }

    public ExecutorService getIoExecutor() {
        return this.ioExecutor;
    }

    public ExecutorService getWorkerExecutor() {
        return this.workerExecutor;
    }

    /** 是否接受链接请求 */
    public boolean acceptChannel(SocketAddress remoteAddress) {
        return true;
    }

    /** 新链接 */
    public void openChannel(NetChannel channel) {
        System.out.println("openChannel " + channel.getChannelID());
        this.channelMap.put(channel.getChannelID(), channel);
    }

    /** 关闭链接 */
    public void closeChannel(long channelID, boolean isRemote, String message) {
        System.out.println("closeChannel " + channelID + ", msg:" + message);
        NetChannel channel = this.channelMap.get(channelID);
        channel.close();
        this.channelMap.remove(channelID);
    }

    /** 有新数据到达 */
    public void notifyChannelRcv(long channelID) {
        NetChannel channel = this.channelMap.get(channelID);
        if (channel != null) {
            channel.notifyRcv();
        }
    }

    /** 异步方式处理 swap 区到 rcv/snd 区的 IO 操作任务 */
    public <T> Future<T> submitSoTask(AbstractSoTask mainTask, T result) {
        Future<T> future = new BasicFuture<>();

        AtomicReference<Runnable> refTemp = new AtomicReference<>();
        Runnable runnable = () -> {
            try {
                mainTask.run();

                switch (mainTask.getStatus()) {
                    case Continue:
                        this.getWorkerExecutor().submit(refTemp.get());
                        break;
                    case Finish:
                        future.completed(result);
                        break;
                    case Exit:
                        future.failed(mainTask.getCause());
                        break;
                }
            } catch (Throwable e) {
                future.failed(e);
            }
        };
        refTemp.set(runnable);

        this.getWorkerExecutor().submit(refTemp.get());
        return future;
    }

    /** Socket 通道是否已经关闭 */
    public boolean isClose(long channelID) {
        NetChannel channel = this.channelMap.get(channelID);
        return channel == null || channel.isClose();
    }
}