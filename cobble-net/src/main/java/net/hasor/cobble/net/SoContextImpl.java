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

import java.net.SocketAddress;
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
    private static final AtomicLong              nextID = new AtomicLong();
    private final        SoConfig                config;
    private final        ExecutorService         ioExecutor;
    private final        SoExecutorFactory       executorFactory;
    private final        SoResManager            defaultRm;
    private final        Map<Long, NetChannel>   channelMap;
    private final        Map<Long, SoResManager> specialRmMap;

    public SoContextImpl(SoConfig config, ExecutorService ioExec, SoExecutorFactory executorFactory) {
        this.config = config;
        this.ioExecutor = Objects.requireNonNull(ioExec);
        this.executorFactory = Objects.requireNonNull(executorFactory);
        this.channelMap = new ConcurrentHashMap<>();
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
            ExecutorService executor = Objects.requireNonNull(executorFactory.newExecutor(this.config, String.valueOf(channelID)));
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
        System.out.println("openChannel " + channel.getChannelID());
        this.channelMap.put(channel.getChannelID(), channel);
    }

    /** 关闭链接 */
    @Override
    public void closeChannel(long channelID, String message) {
        System.out.println("closeChannel " + channelID + ", msg:" + message);
        NetChannel channel = this.channelMap.get(channelID);
        SoResManager specialRm = this.specialRmMap.get(channelID);

        IOUtils.closeQuietly(channel);
        IOUtils.closeQuietly(specialRm);

        this.channelMap.remove(channelID);
        this.specialRmMap.remove(channelID);
    }

    public void closeAll(String message) {

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