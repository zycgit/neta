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
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;

import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 套接字管理器
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoContextImpl implements SoContext {
    private static final Logger                  logger = Logger.getLogger(SoContextImpl.class);
    private static final AtomicLong              nextID = new AtomicLong();
    private final        SoConfig                config;
    private final        ExecutorService         ioExecutor;
    private final        SoExecutorFactory       executorFactory;
    private final        SoResManager            defaultRm;
    private final        Map<Long, NetChannel>   channelMap;
    private final        List<NetChannel>        channelList;
    private final        Map<Long, SoResManager> specialRmMap;

    public SoContextImpl(SoConfig config, ExecutorService ioExec, SoExecutorFactory executorFactory) {
        this.config = config;
        this.ioExecutor = Objects.requireNonNull(ioExec);
        this.executorFactory = Objects.requireNonNull(executorFactory);
        this.channelMap = new ConcurrentHashMap<>();
        this.channelList = new ArrayList<>();
        this.specialRmMap = new ConcurrentHashMap<>();

        ExecutorService executor = Objects.requireNonNull(executorFactory.newExecutor(this.config, null));
        this.defaultRm = new SoResManagerImpl(this.config, executor);
    }

    public static long nextID() {
        return nextID.incrementAndGet();
    }

    @Override
    public SoConfig getConfig() {
        return this.config;
    }

    @Override
    public SoResManager getResourceManager() {
        return this.defaultRm;
    }

    public int getConnectTimeoutMs() {
        return Math.max(10, this.config.getConnectTimeoutMs());
    }

    public ExecutorService getIoExecutor() {
        return this.ioExecutor;
    }

    public SoResManager newSoResManager(long channelID, SocketAddress remoteAddress) {
        if (this.specialResManager(remoteAddress)) {
            logger.info("channel(" + channelID + ") new special SoResManager.");
            ExecutorService executor = Objects.requireNonNull(this.executorFactory.newExecutor(this.config, String.valueOf(channelID)));
            SoResManager rm = new SoResManagerImpl(this.config, executor);
            this.specialRmMap.put(channelID, rm);
            return rm;
        } else {
            return this.defaultRm;
        }
    }

    /** 链接是否使用单独的 SoResManager */
    public boolean specialResManager(SocketAddress remoteAddress) {
        return false;
    }

    /** 是否接受链接请求 */
    public boolean acceptChannel(SocketAddress remoteAddress) {
        return true;
    }

    /** 新链接 */
    public void openChannel(NetChannel channel) {
        logger.info("channel(" + channel.getChannelID() + ") created.");
        this.channelMap.put(channel.getChannelID(), channel);
        this.channelList.add(channel);
    }

    /** 关闭所有 socket */
    public void closeAll(boolean now) {
        if (now) {
            this.channelList.forEach(NetChannel::closeNow);
        } else {
            this.channelList.forEach(NetChannel::close);
        }
    }

    /** 关闭链接，清理资源，处理回调 */
    @Override
    public void closeChannel(long channelID, String message) {
        logger.info("channel(" + channelID + ") close in progress, " + message);
        NetChannel channel = this.channelMap.get(channelID);
        SoResManager specialRm = this.specialRmMap.get(channelID);

        this.channelMap.remove(channelID);
        this.specialRmMap.remove(channelID);

        SoSndData data;
        do {
            data = channel.wQueue.poll();
            if (data != null) {
                try {
                    data.failed(new ClosedChannelException());
                } catch (Exception ignored) {

                }
            }
        } while (data != null);

        IOUtils.closeQuietly(channel.channel);
        IOUtils.closeQuietly(specialRm);

        logger.info("channel(" + channelID + ") closed.");
    }

    /** 有新数据到达 */
    public void notifyChannelRcv(long channelID) {
        NetChannel channel = this.channelMap.get(channelID);
        if (channel != null) {
            channel.notifyRcv();
        }
    }

    @Override
    public <T> Future<T> submitSoTask(AbstractSoTask task, T result) {
        return this.submitSoTask(this.defaultRm, task, result);
    }

    /** 异步方式处理 swap 区到 rcv/snd 区的 IO 操作任务 */
    @Override
    public <T> Future<T> submitSoTask(SoResManager rm, AbstractSoTask task, T result) {
        Future<T> future = new BasicFuture<>();

        AtomicReference<Runnable> refTemp = new AtomicReference<>();
        Runnable runnable = () -> {
            try {
                task.run();

                switch (task.getStatus()) {
                    case Continue:
                        rm.getExecutor().submit(refTemp.get());
                        break;
                    case Finish:
                        future.completed(result);
                        break;
                    case Exit:
                        future.failed(task.getCause());
                        break;
                }
            } catch (Throwable e) {
                future.failed(e);
            }
        };
        refTemp.set(runnable);

        rm.getExecutor().submit(refTemp.get());
        return future;
    }

    /** Socket 通道是否已经关闭 */
    @Override
    public boolean isClose(long channelID) {
        NetChannel channel = this.channelMap.get(channelID);
        return channel == null || channel.isClose();
    }
}