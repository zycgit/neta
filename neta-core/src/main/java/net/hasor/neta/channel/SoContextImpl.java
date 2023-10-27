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
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;

import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SoContext implements
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
    private final        Map<Long, SoChannel<?>> channelMap;
    private final        Queue<NetChannel>       channelList;
    private final        Queue<NetListen>        listenList;
    private final        Map<Long, SoResManager> specialRmMap;

    public SoContextImpl(SoConfig config, ExecutorService ioExec, SoExecutorFactory executorFactory) {
        this.config = config;
        this.ioExecutor = Objects.requireNonNull(ioExec);
        this.executorFactory = Objects.requireNonNull(executorFactory);
        this.channelMap = new ConcurrentHashMap<>();
        this.channelList = new ConcurrentLinkedQueue<>();
        this.listenList = new ConcurrentLinkedQueue<>();
        this.specialRmMap = new ConcurrentHashMap<>();

        ExecutorService executor = Objects.requireNonNull(executorFactory.newExecutor(this.config, null));
        this.defaultRm = new DefaultSoResManager(this.config, executor);
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
            SoResManager rm = new DefaultSoResManager(this.config, executor);
            this.specialRmMap.put(channelID, rm);
            return rm;
        } else {
            return this.defaultRm;
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
        SoResManager specialRm = this.specialRmMap.get(channelID);
        this.channelMap.remove(channelID);
        this.specialRmMap.remove(channelID);

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
            listen.notifyClose(netChannel);

            IOUtils.closeQuietly(netChannel.channel);
            IOUtils.closeQuietly(specialRm);
            logger.info("channel(" + channelID + ") closed.");
            this.channelList.remove(channel);
        } else {

            NetListen netListen = (NetListen) channel;
            IOUtils.closeQuietly(netListen.channel);
            IOUtils.closeQuietly(specialRm);
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

    @Override
    public <T> Future<T> submitSoTask(DefaultSoTask task, T result) {
        return this.submitSoTask(this.defaultRm, task, result);
    }

    /** asynchronously copy data from swap to rcv/snd */
    @Override
    public <T> Future<T> submitSoTask(SoResManager rm, DefaultSoTask task, T result) {
        Future<T> future = new BasicFuture<>();

        AtomicReference<Runnable> refTemp = new AtomicReference<>();
        Runnable runnable = () -> {
            try {
                task.run();

                switch (task.getStatus()) {
                    case Continue:
                        rm.submitTask(refTemp.get());
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

        rm.submitTask(refTemp.get());
        return future;
    }
}