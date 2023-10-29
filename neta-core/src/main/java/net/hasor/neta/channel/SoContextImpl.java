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
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;

import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SoContext implements
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoContextImpl implements SoContext {
    private static final Logger                     logger = Logger.getLogger(SoContextImpl.class);
    private static final AtomicLong                 nextID = new AtomicLong(1);
    private final        SoConfig                   config;
    private final        ClassLoader                useClassLoader;
    private final        SoThreadFactory            useSoThreadFactory;
    //
    private final        ExecutorService            ioExecutor;
    private final        Map<Long, SoEventExecutor> taskExecutor;
    private final        SoResManager               bufferManager;
    //
    private final        Map<Long, SoChannel<?>>    channelMap;
    private final        Queue<NetChannel>          channelList;
    private final        Queue<NetListen>           listenList;

    public SoContextImpl(SoConfig config) {
        this.config = Objects.requireNonNull(config);
        this.useClassLoader = this.config.getClassLoader() == null ? SoContextImpl.class.getClassLoader() : this.config.getClassLoader();

        if (config.getThreadFactory() == null) {
            this.useSoThreadFactory = (loader, nameTemplate) -> ThreadUtils.threadFactory(loader, nameTemplate, true);
        } else {
            this.useSoThreadFactory = config.getThreadFactory();
        }

        // io exec
        int defaultProcess = this.config.getIoThreads();
        if (defaultProcess < 1) {
            defaultProcess = Math.max(Runtime.getRuntime().availableProcessors() / 4, 1);
        }
        ThreadFactory ioThreadFactory = this.useSoThreadFactory.newFactory(this.useClassLoader, "Cobble-AIO-Thread-%s");
        this.ioExecutor = Executors.newFixedThreadPool(defaultProcess, ioThreadFactory);

        // task exec
        int taskWorkSize = config.getTaskThreads();
        if (taskWorkSize < 1) {
            taskWorkSize = Runtime.getRuntime().availableProcessors();
        }
        this.taskExecutor = new ConcurrentHashMap<>();
        this.taskExecutor.put(0L, new SoEventExecutor("default", this.useClassLoader, this.useSoThreadFactory, taskWorkSize));

        //
        this.bufferManager = new DefaultSoResManager(this.config);
        this.channelMap = new ConcurrentHashMap<>();
        this.channelList = new ConcurrentLinkedQueue<>();
        this.listenList = new ConcurrentLinkedQueue<>();
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
        return this.bufferManager;
    }

    public int getConnectTimeoutMs() {
        return Math.max(10, this.config.getConnectTimeoutMs());
    }

    public ExecutorService getIoExecutor() {
        return this.ioExecutor;
    }

    public void specialConfig(long channelID, SocketAddress remoteAddress) {
        if (this.specialResManager(remoteAddress)) {
            int threads = this.config.getTaskThreads();
            SoEventExecutor executor = new SoEventExecutor(String.valueOf(channelID), this.useClassLoader, this.useSoThreadFactory, threads);
            this.taskExecutor.put(channelID, executor);
        }
    }

    /** Whether to use special SoResManager. */
    public boolean specialResManager(SocketAddress remoteAddress) {
        return false;
    }

    /** Whether to accept link socket. */
    public boolean acceptChannel(SocketAddress remoteAddress) {
        return true;
    }

    /** new channel. */
    public void openChannel(SoChannel<?> channel) {
        logger.info("channel(" + channel.getChannelID() + ") created.");
        this.channelMap.put(channel.getChannelID(), channel);
        if (channel.isListen()) {
            this.listenList.add((NetListen) channel);
        } else {
            this.channelList.add((NetChannel) channel);
        }
    }

    /** close all socket. */
    public void closeAll(boolean now) {
        List<Long> ids = new LinkedList<>();

        // lock close method.
        for (NetListen listen : this.listenList) {
            listen.suspend();
            listen.closeFuture.completed(listen);
            ids.add(listen.getChannelID());
        }
        for (NetChannel channel : this.channelList) {
            channel.closeFuture.completed(channel);
            ids.add(channel.getChannelID());
        }

        ids.forEach(channelID -> closeChannel(channelID, "closeAll "));
    }

    /** Close channel, clean up resources, and process callbacks */
    @Override
    public void closeChannel(long channelID, String message) {
        logger.info("channel(" + channelID + ") close in progress, " + message);
        SoChannel<?> channel = this.channelMap.get(channelID);
        SoEventExecutor specialExecutor = this.taskExecutor.get(channelID);
        this.channelMap.remove(channelID);
        this.taskExecutor.remove(channelID);

        if (channel.isClient() || channel.isServer()) {
            NetChannel netChannel = (NetChannel) channel;
            netChannel.pipeStack.release(netChannel.pipeContext);

            SoSndData data;
            do {
                data = netChannel.wQueue.poll();
                if (data != null) {
                    try {
                        data.failed(new ClosedChannelException());
                    } catch (Exception ignored) {

                    }
                }
            } while (data != null);

            NetListen listen = netChannel.getSource();
            if (netChannel.isServer()) {
                listen.notifyClose(netChannel);
            }

            IOUtils.closeQuietly(netChannel.channel);
            IOUtils.closeQuietly(specialExecutor);
            logger.info("channel(" + channelID + ") closed.");
            this.channelList.remove(channel);
        } else {

            NetListen netListen = (NetListen) channel;
            IOUtils.closeQuietly(netListen.channel);
            IOUtils.closeQuietly(specialExecutor);
            logger.info("listen(" + channelID + ") closed, port :" + netListen.getListenPort());
            this.listenList.remove(channel);
        }
    }

    /** test the channel has been closed */
    @Override
    public boolean isClose(long channelID) {
        SoChannel<?> channel = this.channelMap.get(channelID);
        return channel == null || channel.isClose();
    }

    /** receiving new data */
    public void notifyChannelRcv(long channelID, int retryCnt) {
        SoChannel<?> channel = this.channelMap.get(channelID);
        if (channel != null) {
            if (channel.isClient() || channel.isServer()) {
                NetChannel netChannel = (NetChannel) channel;
                netChannel.notifyRcv(retryCnt);
            } else {
                throw new UnsupportedOperationException(); // Can't happen
            }
        }
    }

    /** asynchronously copy data from swap to rcv/snd */
    public <T> Future<T> submitSoTask(long channelID, DefaultSoTask task, T result) {
        SoEventExecutor executor = this.taskExecutor.get(channelID);
        if (executor == null) {
            executor = this.taskExecutor.get(0L);
        }
        return executor.submitSoTask(task, result);
    }
}