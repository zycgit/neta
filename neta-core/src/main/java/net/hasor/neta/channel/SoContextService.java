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
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.timer.HashedWheelTimer;
import net.hasor.cobble.concurrent.timer.TimerTask;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBufAllocator;

/**
 * Default implementation of {@link SoContext} and the shared runtime container for a single
 * {@link NetManager}.
 * <p>It owns transport-agnostic shared resources such as the byte buffer allocator, I/O executor,
 * worker executor, timer wheel, active channel registry, and the subscription list used to
 * dispatch {@link PlayLoad} events.</p>
 * <p>It is also responsible for coordinating channel lifecycle operations, error reporting, and
 * shutdown ordering.</p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see SoContext
 * @see SoTaskExecutor
 * @see NetManager
 */
public class SoContextService implements SoContext {
    private static final Logger                  logger    = Logger.getLogger(SoContextService.class);
    private final        AtomicLong              nextID    = new AtomicLong(0);
    private final        NetConfig               config;
    private final        NetManager              manager;
    private final        ByteBufAllocator        allocator;
    private final        ClassLoader             useClassLoader;
    private final        SoThreadFactory         useSoThreadFactory;
    //
    private final        List<SubscriptionEntry> listeners = new CopyOnWriteArrayList<>();
    //
    private final        HashedWheelTimer        globalTimer;
    private final        ExecutorService         ioExecutor;
    private final        SoTaskExecutor          eventExecutor;
    private final        ReentrantReadWriteLock  closeSyncLock;
    private final        Map<Long, SoChannel<?>> channelMap;
    private final        Queue<NetChannel>       channelList;
    private final        Queue<NetListen>        listenList;
    private volatile     boolean                 closeStatus;

    SoContextService(NetConfig netConf, NetManager manager) {
        this.manager = manager;
        this.allocator = netConf.getBufAllocator() == null ? ByteBufAllocator.DEFAULT : netConf.getBufAllocator();
        this.config = Objects.requireNonNull(netConf);
        this.useClassLoader = this.config.getClassLoader() == null ? SoContextService.class.getClassLoader() : this.config.getClassLoader();

        if (netConf.getThreadFactory() == null) {
            this.useSoThreadFactory = (loader, nameTemplate) -> ThreadUtils.threadFactory(loader, nameTemplate, true);
        } else {
            this.useSoThreadFactory = netConf.getThreadFactory();
        }

        // timer
        ThreadFactory timerThread = ThreadUtils.daemonThreadFactory(this.useClassLoader, "Neta-Timer");
        this.globalTimer = new HashedWheelTimer(timerThread, 50, TimeUnit.MILLISECONDS);
        this.globalTimer.start();

        // io exec
        int defaultProcess = this.config.getIoThreads();
        if (defaultProcess < 1) {
            defaultProcess = Math.max(Runtime.getRuntime().availableProcessors() / 4, 1);
        }
        ThreadFactory ioThreadFactory = this.useSoThreadFactory.newFactory(this.useClassLoader, "Neta-IO-%s");
        this.ioExecutor = Executors.newFixedThreadPool(defaultProcess, ioThreadFactory);

        // task exec
        int taskWorkSize = netConf.getTaskThreads();
        if (taskWorkSize < 1) {
            taskWorkSize = Runtime.getRuntime().availableProcessors();
        }
        this.eventExecutor = new SoTaskExecutor(this.useClassLoader, this.useSoThreadFactory, taskWorkSize, this.globalTimer);

        //
        this.closeStatus = false;
        this.closeSyncLock = new ReentrantReadWriteLock(true);
        this.channelMap = new ConcurrentHashMap<>();
        this.channelList = new ConcurrentLinkedQueue<>();
        this.listenList = new ConcurrentLinkedQueue<>();
    }

    private static void doCloseChannel(boolean now, SoChannel<?> channel, List<Future<?>> waitFinish) {
        if (now) {
            channel.closeNow();
        } else {
            waitFinish.add(channel.close());
        }
    }

    /** Generate a unique channel ID using a monotonically increasing sequence. */
    public long nextID() {
        return nextID.incrementAndGet();
    }

    /** {@inheritDoc} */
    @Override
    public NetConfig getConfig() {
        return this.config;
    }

    /** {@inheritDoc} */
    @Override
    public ByteBufAllocator getByteBufAllocator() {
        return this.allocator;
    }

    /** {@inheritDoc} */
    @Override
    public SocketAddress getRemoteAddress(long channelId) {
        SoChannel<?> channel = this.channelMap.get(channelId);
        if (channel == null) {
            return null;
        } else {
            return channel.getRemoteAddr();
        }
    }

    /** Return the I/O thread pool executor. */
    public ExecutorService getIoExecutor() {
        return this.ioExecutor;
    }

    /** Return whether new connection sockets may currently be accepted. */
    public boolean acceptChannel(SocketAddress remoteAddress) {
        return !this.closeStatus;
    }

    /** Return whether the specified channel has already closed. */
    @Override
    public boolean isClose(long channelId) {
        SoChannel<?> channel = this.channelMap.get(channelId);
        return channel == null || channel.isClose();
    }

    /** Return whether the context itself has entered the closed state. */
    public boolean isClose() {
        return this.closeStatus;
    }

    /** {@inheritDoc} */
    @Override
    public SoChannel<?> findChannel(long channelId) {
        return this.channelMap.get(channelId);
    }

    /** Register a channel or listener and trigger init/active lifecycle callbacks when needed. */
    public void initChannel(SoChannel<?> channel, boolean init) throws Throwable {
        long channelId = channel.getChannelId();
        if (this.channelMap.containsKey(channelId)) {
            throw new SoException("channelId already exists.");
        }

        // add channel
        try {
            this.closeSyncLock.readLock().lock();

            if (this.closeStatus) {
                throw new SoException("context is closed, cannot init channel.");
            }

            this.channelMap.put(channel.getChannelId(), channel);
            if (channel.isListen()) {
                this.listenList.add((NetListen) channel);
            } else {
                this.channelList.add((NetChannel) channel);
            }
        } finally {
            this.closeSyncLock.readLock().unlock();
        }

        // init
        if (init && channel instanceof NetChannel) {
            NetChannel netChannel = (NetChannel) channel;
            ProtoStackChain protoStack = netChannel.protoStack;
            ProtoContextService protoCtx = netChannel.protoCtx;

            try {
                protoStack.onInit(protoCtx);
                if (!channel.isClose()) {
                    protoStack.onActive(protoCtx);
                }
            } catch (Throwable e) {
                // Roll back registration if initialization fails.
                this.channelMap.remove(channel.getChannelId());
                if (channel.isListen()) {
                    this.listenList.remove(channel);
                } else {
                    this.channelList.remove(channel);
                }
                throw e;
            }

            if (!channel.isClose() && netChannel.getListen() != null) {
                netChannel.getListen().notifyAccept(netChannel);
            }
        }
    }

    @Override
    public SubscribeHolder subscribe(long channelId, PlayLoadListener listener) {
        return this.subscribe(channelId, SubscribeMode.ASYNC, listener);
    }

    /** {@inheritDoc} */
    @Override
    public SubscribeHolder subscribe(long channelId, SubscribeMode mode, PlayLoadListener listener) {
        return this.subscribe(p -> p.getSource().getChannelId() == channelId, mode, listener);
    }

    /** {@inheritDoc} */
    @Override
    public SubscribeHolder subscribe(final Predicate<PlayLoad> select, final PlayLoadListener listener) {
        return this.subscribe(select, SubscribeMode.ASYNC, listener);
    }

    /** {@inheritDoc} */
    @Override
    public SubscribeHolder subscribe(final Predicate<PlayLoad> select, SubscribeMode mode, final PlayLoadListener listener) {
        if (listener == null) {
            return null;
        }

        mode = (mode == null) ? SubscribeMode.ASYNC : mode;
        SubscriptionEntry subscription = new SubscriptionEntry(select, mode, listener);
        this.listeners.add(subscription);
        return subscription;
    }

    /** Trigger an event. */
    @Deprecated
    public void trigger(PlayLoad data) {
        String prefix;
        if (data.isInbound()) {
            prefix = "rcv";
        } else if (data.isOutbound()) {
            prefix = "snd";
        } else {
            prefix = "event";
        }

        boolean hasProcessed = false;
        for (SubscriptionEntry listener : this.listeners) {
            try {
                hasProcessed = hasProcessed | listener.dispatch(data);
            } catch (Exception e) {
                logger.error(prefix + "(" + data.getSource().getChannelId() + ") trigger " + listener.listener.getClass().getName() + " has error " + e.getMessage(), e);
            }
        }

        if (!hasProcessed) {
            String msg = prefix + "(" + data.getSource().getChannelId() + ") There are no program at the tail of the ProtoStackChain, Skipping event: ";
            logger.debug(msg + data.getData());
        }
    }

    /** {@inheritDoc} */
    @Override
    public NetManager getNetManager() {
        return this.manager;
    }

    void foreachListen(Consumer<NetListen> consumer) {
        this.listenList.forEach(consumer);
    }

    /**
     * Close all listeners and channels owned by the current context.
     * <p>This method executes mutually exclusively with {@link #initChannel(SoChannel, boolean)}.</p>
     * @param now when {@code true}, close immediately; otherwise use the normal asynchronous close flow
     */
    public void closeAll(boolean now) {
        // Mark the context as closed.
        try {
            this.closeSyncLock.writeLock().lock();
            this.closeStatus = true;
        } finally {
            this.closeSyncLock.writeLock().unlock();
        }

        List<Future<?>> waitFinish = new LinkedList<>();

        // Close all NetListen instances.
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

        // Close all NetChannel instances.
        while (!this.channelList.isEmpty()) {
            NetChannel channel = this.channelList.poll();
            if (channel == null || channel.isClose()) {
                continue;
            }
            if (channel instanceof SoSubChannel) {
                SoChannel<?> parent = ((SoSubChannel) channel).getParent();
                if (parent != null && !parent.isClose()) {
                    doCloseChannel(now, parent, waitFinish);// close parent first
                }
            }
            doCloseChannel(now, channel, waitFinish);
        }

        // Wait for all close operations to finish.
        for (Future<?> future : waitFinish) {
            try {
                future.get(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                // Timeout or other error; continue with the next future.
            }
        }
    }

    /**
     * Shut down all thread pools and the global timer wheel.
     * <p>This method may be called repeatedly. Resources that are already closed are skipped.</p>
     */
    public void shutdown() {
        if (this.ioExecutor != null) {
            this.ioExecutor.shutdown();
            try {
                if (!this.ioExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    this.ioExecutor.shutdownNow();
                    if (!this.ioExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                        logger.error("Pool did not terminate");
                    }
                }
            } catch (InterruptedException ie) {
                this.ioExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("shutdown ioExecutor done.");
        }

        if (this.eventExecutor != null) {
            try {
                this.eventExecutor.close();
            } catch (Exception e) {
                logger.error("shutdown eventExecutor failed.", e);
            }
        }

        if (this.globalTimer != null) {
            this.globalTimer.stop();
        }
    }

    /**
     * Submit an event-loop task to the shared executor for asynchronous execution.
     * @param task task to execute
     * @param result value returned by the Future when the task completes successfully
     * @param <T> Future result type
     * @return the corresponding asynchronous result
     */
    public <T> Future<T> submitSoTask(DefaultSoTask task, T result) {
        return this.eventExecutor.submitSoTask(task, result);
    }

    /**
     * Register a timed task on the shared timer wheel.
     * @param task timer task
     * @param delay delay duration
     * @param unit time unit of the delay
     */
    protected void newTimeout(TimerTask task, long delay, TimeUnit unit) {
        this.globalTimer.newTimeout(task, delay, unit);
    }

    /**
     * Deliver a network event into the inbound pipeline of the specified channel.
     * @param channelId target channel ID
     * @param stackName starting handler name; when {@code null}, delivery starts from the chain head
     * @param event network event to deliver
     */
    public void notifyRcvEvent(long channelId, String stackName, SoEvent event) {
        SoChannel<?> channel = this.channelMap.get(channelId);
        if (channel == null) {
            logger.error("notifyRcvEvent, channel not found. channelId : " + channelId);
            return;
        }

        if (!(channel instanceof NetChannel)) {
            logger.error("only NetChannel can notifyRcvEvent. channelId : " + channelId);
            return;
        }

        try {
            NetChannel netChannel = (NetChannel) channel;
            netChannel.protoStack.onRcvEvent(netChannel.protoCtx, stackName, event);
        } catch (Throwable e) {
            SoException ee = e instanceof SoException ? (SoException) e : new SoRcvException(e.getMessage(), e);
            this.notifyRcvChannelException(channelId, false, ee);
        }
    }

    /**
     * Deliver a network event into the outbound pipeline of the specified channel.
     * @param channelId target channel ID
     * @param stackName starting handler name; when {@code null}, delivery starts from the chain tail
     * @param event network event to deliver
     */
    public void notifySndEvent(long channelId, String stackName, SoEvent event) {
        SoChannel<?> channel = this.channelMap.get(channelId);
        if (channel == null) {
            logger.error("notifySndEvent, channel not found. channelId : " + channelId);
            return;
        }

        if (!(channel instanceof NetChannel)) {
            logger.error("only NetChannel can notifySndEvent. channelId : " + channelId);
            return;
        }

        try {
            NetChannel netChannel = (NetChannel) channel;
            netChannel.protoStack.onSndEvent(netChannel.protoCtx, stackName, event);
        } catch (Throwable e) {
            SoException ee = e instanceof SoException ? (SoException) e : new SoSndException(e.getMessage(), e);
            this.notifySndChannelException(channelId, false, ee);
        }
    }

    /**
     * Handle a listener bind failure and close the corresponding listening channel.
     * @param channelId listening channel ID
     * @param e bind exception
     */
    public void notifyBindChannelException(long channelId, SoBindException e) {
        SoChannel<?> channel = this.channelMap.get(channelId);
        if (channel == null) {
            logger.error("channel not found. channelId : " + channelId, e);
            return;
        }

        if (channel instanceof NetListen) {
            logger.error("ERROR: bindFailed, " + e.getMessage(), e);
            this.doCloseChannel(channel, "bindFailed, " + e.getMessage(), e);
        } else {
            logger.error("only NetChannel can notifyBindException. channelId : " + channelId);
        }
    }

    /**
     * Handle a failure while establishing an outbound connection.
     * @param channelId target channel ID
     * @param doClose when {@code true}, close the channel after delivering the error
     * @param e connection exception
     */
    public void notifyConnectChannelException(long channelId, boolean doClose, SoConnectException e) {
        SoChannel<?> channel = this.channelMap.get(channelId);
        if (channel == null) {
            logger.error("channel not found. channelId : " + channelId, e);
            return;
        }

        if (channel instanceof NetChannel) {
            this.doNotifyError(true, doClose, e, channel);
        } else {
            logger.error("only NetChannel can notifyConnectException. channelId : " + channelId);
        }
    }

    /**
     * Notify the specified channel that new inbound data has arrived.
     * @param channelId target channel ID
     * @param rcvData received data
     */
    public void notifyRcvChannelData(long channelId, Object... rcvData) {
        SoChannel<?> channel = this.channelMap.get(channelId);
        if (channel == null) {
            logger.error("notifyRcvFailed, channel not found. channelId : " + channelId);
            return;
        }

        if (!(channel instanceof NetChannel)) {
            logger.error("only NetChannel can notifyData. channelId : " + channelId);
            return;
        }

        try {
            ((NetChannel) channel).notifyRcv(rcvData);
        } catch (Throwable e) {
            SoException ee = e instanceof SoException ? (SoException) e : new SoRcvException(e.getMessage(), e);
            this.notifyRcvChannelException(channelId, true, ee);
        }
    }

    /**
     * Handle an exception raised by the inbound pipeline.
     * @param channelId target channel ID
     * @param doClose when {@code true}, close the channel after delivering the error
     * @param e pipeline exception
     */
    public void notifyRcvChannelException(long channelId, boolean doClose, SoException e) {
        SoChannel<?> channel = this.channelMap.get(channelId);
        if (channel == null) {
            logger.error("channel not found. channelId : " + channelId, e);
            return;
        }

        if (channel instanceof NetChannel) {
            this.doNotifyError(true, doClose, e, channel);
        } else {
            logger.error("only NetChannel can notifyRcvException. channelId : " + channelId);
        }
    }

    /**
     * Handle an exception raised by the outbound pipeline.
     * @param channelId target channel ID
     * @param doClose when {@code true}, close the channel after delivering the error
     * @param e pipeline exception
     */
    public void notifySndChannelException(long channelId, boolean doClose, SoException e) {
        SoChannel<?> channel = this.channelMap.get(channelId);
        if (channel == null) {
            logger.error("channel not found. channelId : " + channelId, e);
            return;
        }

        if (channel instanceof NetChannel) {
            this.doNotifyError(false, doClose, e, channel);
        } else {
            logger.error("only NetChannel can notifySendException. channelId : " + channelId);
        }
    }

    private void doNotifyError(boolean isRcv, boolean doClose, SoException e, SoChannel<?> channel) {
        try {
            ((NetChannel) channel).notifyError(isRcv, e);
            if (doClose) {
                this.doCloseChannel(channel, "close channel for exception " + e.getMessage(), e);
            }
        } catch (Throwable ee) {
            String errorMsg = "close channel for unhandled exception " + ee.getMessage();
            logger.error(errorMsg, ee);
            this.doCloseChannel(channel, errorMsg, ee);
        }
    }

    /**
     * Handle a channel close event.
     * @param channelId target channel ID
     * @param remote when {@code true}, the close was initiated by the remote side
     */
    public void notifyChannelClose(long channelId, boolean remote) {
        SoChannel<?> channel = this.channelMap.get(channelId);
        if (channel != null) {
            this.doCloseChannel(channel, "closed from " + (remote ? "remote" : "local"), null);
        }
    }

    private void doCloseChannel(SoChannel<?> channel, String message, Throwable e) {
        if (e == null) {
            logger.info("channel(" + channel.getChannelId() + ") " + message);
        } else {
            logger.error(message, e);
        }

        // Clean the write queue.
        if (channel instanceof NetChannel) {
            NetChannel netChannel = (NetChannel) channel;
            IOUtils.closeQuietly(netChannel.asyncChannel);
            netChannel.closeStatus.set(true);

            // Purge pending data.
            netChannel.wContext.purge(e);

            // Trigger close callbacks.
            try {
                netChannel.protoStack.onClose(netChannel.protoCtx);
            } catch (Exception ignore) {
                //
            } finally {
                NetListen forListen = netChannel.getListen();
                if (forListen != null) {
                    forListen.notifyClose(netChannel);
                }

                this.channelMap.remove(channel.getChannelId());
                this.channelList.remove(netChannel);
            }

            try {
                netChannel.closeFuture.completed(netChannel);
            } catch (Exception ignore) {
                //
            }
        } else {
            NetListen netListen = (NetListen) channel;
            IOUtils.closeQuietly(netListen.channel);
            netListen.closeStatus.set(true);

            this.channelMap.remove(channel.getChannelId());
            this.listenList.remove(channel);
            logger.info("listen(" + channel.getChannelId() + ") closed, port :" + netListen.getListenPort());
        }
    }

    private final class SubscriptionEntry implements SubscribeHolder {
        private final Predicate<PlayLoad> select;
        private final SubscribeMode       mode;
        private final PlayLoadListener    listener;
        private final Queue<PlayLoad>     eventQueue;
        private final AtomicBoolean       draining;
        private final AtomicBoolean       active;

        private SubscriptionEntry(Predicate<PlayLoad> select, SubscribeMode mode, PlayLoadListener listener) {
            this.select = select;
            this.mode = mode;
            this.listener = listener;
            this.eventQueue = mode == SubscribeMode.ASYNC ? new ConcurrentLinkedQueue<>() : null;
            this.draining = mode == SubscribeMode.ASYNC ? new AtomicBoolean(false) : null;
            this.active = new AtomicBoolean(true);
        }

        /** {@inheritDoc} */
        @Override
        public SubscribeMode getSubscribeMode() {
            return this.mode;
        }

        /** {@inheritDoc} */
        @Override
        public void unSubscribe() {
            this.active.set(false);
            if (this.eventQueue != null) {
                this.eventQueue.clear();
            }
            SoContextService.this.listeners.remove(this);
        }

        private boolean dispatch(PlayLoad data) {
            if (!this.active.get()) {
                return false;
            }
            if (this.select != null && !this.select.test(data)) {
                return false;
            }

            if (this.mode == SubscribeMode.SYNC) {
                this.listener.onEvent(data);
            } else {
                this.eventQueue.offer(data);
                this.scheduleDrain();
            }
            return true;
        }

        private void scheduleDrain() {
            if (!this.draining.compareAndSet(false, true)) {
                return;
            }
            SoContextService.this.submitSoTask(new DefaultSoTask() {
                @Override
                protected void doWork(int retryCnt) {
                    drainQueue();
                    finishTask();
                }
            }, this);
        }

        private void drainQueue() {
            while (this.active.get()) {
                PlayLoad next = this.eventQueue.poll();
                if (next == null) {
                    this.draining.set(false);
                    if (!this.active.get() || this.eventQueue.isEmpty() || !this.draining.compareAndSet(false, true)) {
                        return;
                    }
                    continue;
                }

                try {
                    this.listener.onEvent(next);
                } catch (Exception e) {
                    String prefix;
                    if (next.isInbound()) {
                        prefix = "rcv";
                    } else if (next.isOutbound()) {
                        prefix = "snd";
                    } else {
                        prefix = "event";
                    }
                    logger.error(prefix + "(" + next.getSource().getChannelId() + ") trigger " + this.listener.getClass().getName() + " has error " + e.getMessage(), e);
                }
            }
            this.eventQueue.clear();
            this.draining.set(false);
        }
    }
}