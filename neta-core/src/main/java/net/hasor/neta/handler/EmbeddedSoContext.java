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
package net.hasor.neta.handler;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;

import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base class for {@link SoContext} implementations that are used in an embedded fashion.
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class EmbeddedSoContext implements SoContext {
    private static final Logger                      logger = Logger.getLogger(EmbeddedSoContext.class);
    private static final AtomicLong                  nextID = new AtomicLong();
    private final        SoConfig                    config;
    private final        SoResManager                defaultRm;
    private final        Map<Long, SoChannel<?>>     channelMap;
    private final        Queue<SoChannel<?>>         channelList;
    private final        Map<Long, EmbeddedTransfer> networkMap;

    public EmbeddedSoContext() {
        this(new SoConfig());
    }

    public EmbeddedSoContext(SoConfig config) {
        this.config = config;
        this.channelMap = new ConcurrentHashMap<>();
        this.channelList = new ConcurrentLinkedQueue<>();
        this.networkMap = new ConcurrentHashMap<>();

        SoExecutorFactory executorFactory = config.getTaskExecutorFactory();
        if (executorFactory == null) {
            executorFactory = (cfg, ctxName) -> {
                String tempName = "Cobble[" + StringUtils.getOrDefault("default", ctxName) + "]-AIO-Workers-%s";
                int process = Runtime.getRuntime().availableProcessors();
                ThreadFactory threadFactory = ThreadUtils.threadFactory(CobbleSocket.class.getClassLoader(), tempName, true);
                return Executors.newFixedThreadPool(process, threadFactory);
            };
        }
        ExecutorService executor = Objects.requireNonNull(executorFactory.newExecutor(this.config, null));
        this.defaultRm = new DefaultSoResManager(this.config, executor);
    }

    protected static long nextID() {
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

    /** new channel. */
    public void openChannel(SoChannel<?> channel) {
        logger.info("channel(" + channel.getChannelID() + ") created.");
        this.channelMap.put(channel.getChannelID(), channel);
        this.channelList.add(channel);
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

    /** test the channel has been closed */
    @Override
    public boolean isClose(long channelID) {
        SoChannel<?> channel = this.channelMap.get(channelID);
        return channel == null || channel.isClose();
    }

    @Override
    public void closeChannel(long channelID, String message) {
        logger.info("channel(" + channelID + ") close in progress, " + message);
        SoChannel<?> channel = this.channelMap.get(channelID);
        this.channelMap.remove(channelID);

        EmbeddedChannel netChannel = (EmbeddedChannel) channel;
        netChannel.pipeStack.release(netChannel.pipeCtx);

        logger.info("channel(" + channelID + ") closed.");
        this.channelList.remove(channel);
    }

    /**
     * that are used in an embedded fashion, connected two {@link EmbeddedTransfer} makes it a server/client.
     * @param client client side {@link EmbeddedTransfer}
     * @param server server side {@link EmbeddedTransfer}
     */
    public EmbeddedTransfer joinChannel(EmbeddedChannel client, EmbeddedChannel server) {
        return new EmbeddedTransfer(this.defaultRm, client, server);
    }
}