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
import net.hasor.cobble.concurrent.timer.HashedWheelTimer;
import net.hasor.cobble.concurrent.timer.TimerTask;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;

import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * SoContext implements
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoContextImpl implements SoContext {
    private static final Logger                     logger = Logger.getLogger(SoContextImpl.class);
    private final        AtomicLong                 nextID = new AtomicLong(0);
    private final        SoConfig                   config;
    private final        NetChannelManager          cobble;
    private final        ClassLoader                useClassLoader;
    private final        SoThreadFactory            useSoThreadFactory;
    //
    private final        HashedWheelTimer           globalTimer;
    private final        ExecutorService            ioExecutor;
    private final        SoEventExecutor            defaultTaskExecutor;
    private final        Map<Long, SoEventExecutor> specialTaskExecutor;
    private final        SoResManager               bufferManager;
    //
    private volatile     boolean                    closeStatus;
    private final        ReentrantReadWriteLock     closeSyncLock;
    private final        Map<Long, SoChannel<?>>    channelMap;
    private final        Queue<NetChannel>          channelList;
    private final        Queue<NetListen>           listenList;

    public SoContextImpl(SoConfig config, NetChannelManager cobble) {
        this.cobble = cobble;
        this.config = Objects.requireNonNull(config);
        this.useClassLoader = this.config.getClassLoader() == null ? SoContextImpl.class.getClassLoader() : this.config.getClassLoader();

        if (config.getThreadFactory() == null) {
            this.useSoThreadFactory = (loader, nameTemplate) -> ThreadUtils.threadFactory(loader, nameTemplate, true);
        } else {
            this.useSoThreadFactory = config.getThreadFactory();
        }

        // timer
        ThreadFactory timerThread = ThreadUtils.daemonThreadFactory(this.useClassLoader, "Cobble-AIO-Timer");
        this.globalTimer = new HashedWheelTimer(timerThread, 50, TimeUnit.MILLISECONDS);
        this.globalTimer.start();

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
        this.defaultTaskExecutor = new SoEventExecutor("default", this.useClassLoader, this.useSoThreadFactory, taskWorkSize, this.globalTimer);
        this.specialTaskExecutor = new ConcurrentHashMap<>();

        //
        this.bufferManager = new DefaultSoResManager(this.config);
        this.closeStatus = false;
        this.closeSyncLock = new ReentrantReadWriteLock(true);
        this.channelMap = new ConcurrentHashMap<>();
        this.channelList = new ConcurrentLinkedQueue<>();
        this.listenList = new ConcurrentLinkedQueue<>();
    }

    public long nextID() {
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

    @Override
    public SocketAddress getRemoteAddress(long channelID) {
        SoChannel<?> channel = this.channelMap.get(channelID);
        if (channel == null) {
            return null;
        } else {
            return channel.getRemoteAddr();
        }
    }

    public ExecutorService getIoExecutor() {
        return this.ioExecutor;
    }

    public void specialConfig(long channelID, SocketAddress remoteAddress) {
        if (this.specialResManager(remoteAddress)) {
            int threads = this.config.getTaskThreads();
            SoEventExecutor executor = new SoEventExecutor(String.valueOf(channelID), this.useClassLoader, this.useSoThreadFactory, threads, this.globalTimer);
            this.specialTaskExecutor.put(channelID, executor);
        }
    }

    /** Whether to use special SoResManager. */
    public boolean specialResManager(SocketAddress remoteAddress) {
        return false;
    }

    /** Whether to accept link socket. */
    public boolean acceptChannel(SocketAddress remoteAddress) {
        if (this.closeStatus) {
            return false;
        }

        return true;
    }

    /** new channel, The method {@link #openChannel(SoChannel)} and {@link #closeAll(boolean)} are mutually exclusive */
    public void openChannel(SoChannel<?> channel) {
        try {
            this.closeSyncLock.readLock().lock();

            this.channelMap.put(channel.getChannelID(), channel);
            if (channel.isListen()) {
                this.listenList.add((NetListen) channel);
            } else {
                this.channelList.add((NetChannel) channel);
            }

            if (this.closeStatus) {
                this.defaultTaskExecutor.submitSoTask(new SimpleTask(channel::closeNow), this);
            }
        } finally {
            this.closeSyncLock.readLock().unlock();
        }
    }

    /** test the channel has been closed */
    @Override
    public boolean isClose(long channelID) {
        SoChannel<?> channel = this.channelMap.get(channelID);
        return channel == null || channel.isClose();
    }

    /** close status. */
    public boolean isClose() {
        return this.closeStatus;
    }

    @Override
    public SoChannel<?> findChannel(long channelID) {
        return this.channelMap.get(channelID);
    }

    @Override
    public NetChannelManager getNeta() {
        return this.cobble;
    }

    void foreachListen(Consumer<NetListen> consumer) {
        this.listenList.forEach(consumer);
    }

    /** close all socket, The method {@link #openChannel(SoChannel)} and {@link #closeAll(boolean)} are mutually exclusive */
    public void closeAll(boolean now) {
        // mark close is true.
        try {
            this.closeSyncLock.writeLock().lock();
            this.closeStatus = true;
        } finally {
            this.closeSyncLock.writeLock().unlock();
        }

        List<Future<?>> waitFinish = new LinkedList<>();

        // close all NetListen
        while (!this.listenList.isEmpty()) {
            NetListen listen = this.listenList.poll();
            if (listen != null) {
                if (now) {
                    listen.closeNow();
                } else {
                    waitFinish.add(listen.close());
                }
            }
        }

        // close all NetChannel
        while (!this.channelList.isEmpty()) {
            NetChannel channel = this.channelList.poll();
            if (channel != null) {
                if (now) {
                    channel.closeNow();
                } else {
                    waitFinish.add(channel.close());
                }
            }
        }

        // wait all finish
        while (true) {
            boolean allFinish = true;

            for (Future<?> future : waitFinish) {
                allFinish = future.isDone();
                if (!allFinish) {
                    break;
                }
            }

            if (!allFinish) {
                ThreadUtils.sleep(300);
            } else {
                break;
            }
        }
    }

    /** The network channel is forced to close, and all data not sent is discarded. */
    protected void asyncUnsafeCloseChannel(long channelID, String message, Throwable e) {
        this.unsafeCloseChannel(channelID, message, e, true);
    }

    /** The network channel is forced to close, and all data not sent is discarded. */
    protected void syncUnsafeCloseChannel(long channelID, String message, Throwable e) {
        this.unsafeCloseChannel(channelID, message, e, false);
    }

    private void unsafeCloseChannel(long channelID, String message, Throwable e, boolean async) {
        if (this.config.isNetlog()) {
            if (e == SoCloseException.INSTANCE) {
                logger.info(message);
            } else {
                logger.error(message, e);
            }
        }

        SoChannel<?> channel = this.channelMap.get(channelID);
        if (channel == null) {
            return;// it does not exist, It is usually the io that triggers the shutdown early
        }

        SoEventExecutor specialExecutor = this.specialTaskExecutor.get(channelID);
        this.channelMap.remove(channelID);
        this.specialTaskExecutor.remove(channelID);

        if (channel.isClient() || channel.isServer()) {
            NetChannel netChannel = (NetChannel) channel;
            netChannel.closeStatus.set(true);

            // clean wQueue
            netChannel.wContext.purge(e);

            // release pipeStack
            NetListen forListen = netChannel.getListen();
            if (forListen != null) {
                forListen.notifyClose(netChannel);
            }

            netChannel.pipeline.onClose(netChannel.pipeCtx);
            IOUtils.closeQuietly(netChannel.channel);
            IOUtils.closeQuietly(specialExecutor);
            logger.info("channel(" + channelID + ") closed.");

            this.channelList.remove(channel);
        } else {
            NetListen netListen = (NetListen) channel;
            netListen.closeStatus.set(true);
            IOUtils.closeQuietly(netListen.channel);
            IOUtils.closeQuietly(specialExecutor);
            logger.info("listen(" + channelID + ") closed, port :" + netListen.getListenPort());
            this.listenList.remove(channel);
        }
    }

    /** receiving new data */
    public void notifyChannelRcv(long channelID, int dataSize, int retryCnt) {
        SoChannel<?> channel = this.channelMap.get(channelID);
        if (channel != null) {
            if (channel.isClient() || channel.isServer()) {
                NetChannel netChannel = (NetChannel) channel;
                netChannel.notifyRcv(dataSize, retryCnt);
            } else {
                throw new UnsupportedOperationException(); // Can't happen
            }
        }
    }

    /** receiving rcv Error data */
    public void notifyRcvChannelError(long channelID, Throwable e) {
        this.notifyChannelError(channelID, true, e);
    }

    /** receiving snd Error data */
    public void notifySndChannelError(long channelID, Throwable e) {
        this.notifyChannelError(channelID, false, e);
    }

    /** receiving new data */
    protected void notifyChannelError(long channelID, boolean isRcv, Throwable e) {
        SoChannel<?> channel = this.channelMap.get(channelID);
        if (channel != null) {
            if (channel.isClient() || channel.isServer()) {
                NetChannel netChannel = (NetChannel) channel;
                netChannel.notifyError(isRcv, e);
            }
        }
    }

    /** asynchronously copy data from swap to rcv/snd */
    public <T> Future<T> submitSoTask(long channelID, DefaultSoTask task, T result) {
        SoEventExecutor executor = this.specialTaskExecutor.get(channelID);
        if (executor == null) {
            executor = this.defaultTaskExecutor;
        }
        return executor.submitSoTask(task, result);
    }

    /** Set up a timer */
    protected void newTimeout(TimerTask task, long delay, TimeUnit unit) {
        this.globalTimer.newTimeout(task, delay, unit);
    }
}