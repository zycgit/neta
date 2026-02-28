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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;

/**
 * A listener channel for accept incoming sockets and binding them to the protocol stack
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public abstract class NetListen extends SoAttrChannel<NetListen> {
    protected static final Logger                                logger = Logger.getLogger(NetListen.class);
    protected final        AsyncServerChannel                    channel;
    //
    protected final        AtomicBoolean                         closeStatus;
    protected final        Future<NetListen>                     closeFuture;
    private final          List<SoChannelListener<SoChannel<?>>> onAcceptListeners;
    private final          long                                  channelId;
    private final          long                                  createdTime;
    private final          AtomicLong                            acceptCount;
    private final          Object                                acceptLock;
    private final          Object                                closeLock;
    //
    private final          SocketAddress                         listenAddr;
    private final          int                                   listenPort;
    private final          ProtoInitializer                      initializer;
    private final          SoContextService                      context;
    private final          SoConfig                              soConfig;
    private volatile       long                                  lastActiveTime;
    private volatile       long                                  lastAcceptTime;
    private volatile       boolean                               suspend;

    protected NetListen(long channelId, SocketAddress listenAddr, int listenPort, AsyncServerChannel channel,//
            ProtoInitializer initializer, SoContextService context, SoConfig soConfig) {
        this.channelId = channelId;
        this.createdTime = System.currentTimeMillis();
        this.lastActiveTime = this.createdTime;
        this.acceptCount = new AtomicLong();
        this.acceptLock = new Object();
        this.closeLock = new Object();
        this.listenAddr = listenAddr;
        this.listenPort = listenPort;
        this.channel = channel;
        this.initializer = initializer;
        this.context = context;
        this.soConfig = soConfig;
        this.suspend = soConfig.isSuspend();

        this.closeStatus = new AtomicBoolean(false);
        this.closeFuture = new BasicFuture<>();
        this.onAcceptListeners = new CopyOnWriteArrayList<>();
    }

    @Override
    public long getChannelId() {
        return this.channelId;
    }

    @Override
    public long getCreatedTime() {
        return this.createdTime;
    }

    @Override
    public long getLastActiveTime() {
        return this.lastActiveTime;
    }

    /** The last time for accepted channel. */
    public long getLastAcceptTime() {
        return this.lastAcceptTime;
    }

    /** get channel Count */
    public long getChannelCount() {
        return this.acceptCount.get();
    }

    @Override
    public boolean isListen() {
        return true;
    }

    @Override
    public boolean isServer() {
        return false;
    }

    @Override
    public boolean isClient() {
        return false;
    }

    @Override
    public SocketAddress getLocalAddr() {
        return this.listenAddr;
    }

    @Override
    public SocketAddress getRemoteAddr() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SoContext getContext() {
        return this.context;
    }

    @Override
    public SoConfig getConfig() {
        return this.soConfig;
    }

    /**
     * Search for NetChannel by id,
     * return null if NetChannel is not from this NetListen
     */
    public NetChannel findChannel(long channelID) {
        SoChannel<?> channel = this.context.findChannel(channelID);
        if (channel instanceof NetChannel && ((NetChannel) channel).getListen() == this) {
            return (NetChannel) channel;
        } else {
            return null;
        }
    }

    @Override
    public <T> T findProtoContext(Class<T> serviceType) {
        throw new UnsupportedOperationException("Listen channel not support this method.");
    }

    /**
     * Returns the listener current suspend status.
     * <p>all new accept socket will be closed when suspend = true.</p>
     */
    public boolean isSuspend() {
        return this.suspend;
    }

    /**
     * set suspend is true
     * <p>all new accept socket will be closed when suspend = true.</p>
     */
    public NetListen suspend() {
        this.suspend = true;
        return this;
    }

    /**
     * set suspend is false
     * <p>all new accept socket will be closed when suspend = true.</p>
     */
    public NetListen resume() {
        this.suspend = false;
        return this;
    }

    /**
     * return this listener bind socket port.
     */
    public int getListenPort() {
        return this.listenPort;
    }

    /** return Application layer network protocol stack to use */
    public ProtoInitializer getInitializer() {
        return this.initializer;
    }

    @Override
    public boolean isClose() {
        return !this.channel.isOpen() || this.closeStatus.get();
    }

    @Override
    public Future<NetListen> close() {
        if (this.closeStatus.compareAndSet(false, true)) {
            if (this.channel.isOpen()) {
                SoCloseTask task = new SoCloseTask(this.channelId, this.context, false);
                this.context.submitSoTask(task, this).onCompleted(f -> {
                    closeFuture.completed(this);
                }).onFailed(f -> {
                    closeFuture.failed(f.getCause());
                }).onCancel(f -> {
                    closeFuture.cancel();
                });
            } else {
                this.closeFuture.completed(this);
            }
        }
        return this.closeFuture;
    }

    @Override
    public void closeNow() {
        if (this.channel.isOpen() && this.closeStatus.compareAndSet(false, true)) {
            new SoCloseTask(this.channelId, this.context, true).run();
        }
        this.closeFuture.completed(this);
    }

    @Override
    public void onClose(SoChannelListener<SoChannel<?>> listener) {
        this.closeFuture.onCompleted(f -> listener.onEvent(this));
    }

    /** Registers a listener to be notified when a new channel is accepted. */
    public void onAccept(SoChannelListener<SoChannel<?>> listener) {
        if (listener != null) {
            this.onAcceptListeners.add(listener);
        }
    }

    /**
     * a new accept socket
     */
    protected final void notifyAccept(NetChannel channel) {
        if (channel.getListen() == this) {
            this.lastActiveTime = System.currentTimeMillis();
            this.lastAcceptTime = System.currentTimeMillis();
            this.acceptCount.incrementAndGet();

            synchronized (this.acceptLock) {
                this.acceptLock.notifyAll();
            }

            this.onAcceptListeners.forEach(listener -> {
                try {
                    listener.onEvent(channel);
                } catch (Exception e) {
                    logger.error("onAccept error " + e.getMessage(), e);
                }
            });
        }
    }

    /**
     * socket closed
     */
    final void notifyClose(NetChannel channel) {
        if (channel.getListen() == this) {
            this.lastActiveTime = System.currentTimeMillis();
            long count;
            do {
                count = this.acceptCount.get();
                if (count <= 0) {
                    break;
                }
            } while (!this.acceptCount.compareAndSet(count, count - 1));

            synchronized (this.closeLock) {
                this.closeLock.notifyAll();
            }
        }
    }

    /** Wait for an incoming. */
    public void waitAnyAccept() throws InterruptedException {
        synchronized (this.acceptLock) {
            if (this.acceptCount.get() > 0) {
                return;
            }
            this.waitAnyNewAccept();
        }
    }

    /** Wait for an new incoming. */
    public void waitAnyNewAccept() throws InterruptedException {
        synchronized (this.acceptLock) {
            this.acceptLock.wait();
        }
    }

    /** Wait for all disconnection. */
    public void waitIdle() throws InterruptedException {
        while (true) {
            if (this.acceptCount.get() <= 0) {
                return;
            }
            synchronized (this.closeLock) {
                this.closeLock.wait();
            }
        }
    }
}