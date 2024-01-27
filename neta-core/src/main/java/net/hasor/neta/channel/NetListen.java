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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A listener channel for accept incoming sockets and binding them to the protocol stack
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class NetListen extends AttributeChannel<NetListen> {
    private final    long                            channelID;
    private final    long                            createdTime;
    private          long                            lastActiveTime;
    private          long                            lastAcceptTime;
    private final    AtomicLong                      acceptCount;
    private final    Object                          acceptLock;
    private final    Object                          closeLock;
    //
    private final    InetSocketAddress               listen;
    protected final  AsynchronousServerSocketChannel channel;
    private final    PipeInitializer                 initializer;
    private final    SoContextImpl                   context;
    private volatile boolean                         suspend;
    //
    protected final  AtomicBoolean                   closeStatus;
    protected final  Future<NetListen>               closeFuture;

    NetListen(long channelID, long createdTime, InetSocketAddress listen, AsynchronousServerSocketChannel channel,//
            PipeInitializer initializer, SoContextImpl context, NetListenOptions options) {
        this.channelID = channelID;
        this.createdTime = createdTime;
        this.lastActiveTime = createdTime;
        this.acceptCount = new AtomicLong();
        this.acceptLock = new Object();
        this.closeLock = new Object();
        this.listen = listen;
        this.channel = channel;
        this.initializer = initializer;
        this.context = context;
        this.suspend = options.isSuspend();

        this.closeStatus = new AtomicBoolean(false);
        this.closeFuture = new BasicFuture<>();
    }

    @Override
    public long getChannelID() {
        return this.channelID;
    }

    @Override
    public long getCreatedTime() {
        return this.createdTime;
    }

    @Override
    public long getLastActiveTime() {
        return this.lastActiveTime;
    }

    /** The last time for accepted channel.*/
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
        return this.listen;
    }

    @Override
    public SocketAddress getRemoteAddr() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SoContext getContext() {
        return this.context;
    }

    /**
     * Search for NetChannel by id,
     *  return null if NetChannel is not from this NetListen
     */
    public NetChannel findChannel(long channelID) {
        SoChannel<?> channel = this.context.findChannel(channelID);
        if (channel != null && ((NetChannel) channel).getListen() == this) {
            return (NetChannel) channel;
        } else {
            return null;
        }
    }

    @Override
    public <T> T findPipeContext(Class<T> serviceType) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the listener current suspend status.
     *
     * <p>all new accept socket will be closed when suspend = true.</p>
     */
    public boolean isSuspend() {
        return this.suspend;
    }

    /**
     * set suspend is true
     *
     * <p>all new accept socket will be closed when suspend = true.</p>
     */
    public NetListen suspend() {
        this.suspend = true;
        return this;
    }

    /**
     * set suspend is false
     *
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
        return this.listen.getPort();
    }

    /** return Application layer network protocol stack to use */
    PipeInitializer getInitializer() {
        return initializer;
    }

    @Override
    public boolean isClose() {
        return !this.channel.isOpen() || this.closeStatus.get();
    }

    @Override
    public Future<NetListen> close() {
        if (this.closeStatus.compareAndSet(false, true)) {
            if (this.channel.isOpen()) {
                SoCloseTask task = new SoCloseTask(this.channelID, this.context, false);
                this.context.submitSoTask(this.channelID, task, this).onCompleted(f -> {
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
    public Future<NetListen> closeNow() {
        if (this.channel.isOpen() && this.closeStatus.compareAndSet(false, true)) {
            new SoCloseTask(this.channelID, this.context, true).run();
        }

        this.closeFuture.completed(this);
        return this.closeFuture;
    }

    /**
     * a new accept socket
     */
    final void notifyAccept(NetChannel channel) {
        if (channel.getListen() == this) {
            this.lastActiveTime = System.currentTimeMillis();
            this.lastAcceptTime = System.currentTimeMillis();
            this.acceptCount.incrementAndGet();

            synchronized (this.acceptLock) {
                this.acceptLock.notifyAll();
            }
        }
    }

    /**
     * socket closed
     */
    final void notifyClose(NetChannel channel) {
        if (channel.getListen() == this) {
            this.lastActiveTime = System.currentTimeMillis();
            this.acceptCount.decrementAndGet();

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