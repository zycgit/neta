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
import java.util.ArrayList;
import java.util.List;
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
    private final    PipelineFactory                 pipeline;
    private final    SoContextImpl                   context;
    private volatile boolean                         suspend;
    private final    List<NetListener>               listeners;
    //
    protected final  AtomicBoolean                   closeStatus;
    protected final  Future<NetListen>               closeFuture;

    NetListen(long channelID, long createdTime, InetSocketAddress listen, AsynchronousServerSocketChannel channel,//
            PipelineFactory pipeline, SoContextImpl context, NetListenOptions options) {
        this.channelID = channelID;
        this.createdTime = createdTime;
        this.lastActiveTime = createdTime;
        this.acceptCount = new AtomicLong();
        this.acceptLock = new Object();
        this.closeLock = new Object();
        this.listen = listen;
        this.channel = channel;
        this.pipeline = pipeline;
        this.context = context;
        this.suspend = options.isSuspend();
        this.listeners = new ArrayList<>();

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
        return null;
    }

    public SoContext getContext() {
        return this.context;
    }

    @Override
    public <T> T findPipeContext(Class<T> serviceType) {
        return null;
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
    public void suspend() {
        this.suspend = true;
    }

    /**
     * set suspend is false
     *
     * <p>all new accept socket will be closed when suspend = true.</p>
     */
    public void resume() {
        this.suspend = false;
    }

    /**
     * return this listener bind socket port.
     */
    public int getListenPort() {
        return this.listen.getPort();
    }

    /**
     * add {@link NetListener}
     */
    public void addListener(NetListener listener) {
        if (!this.listeners.contains(listener)) {
            this.listeners.add(listener);
        }
    }

    /**
     * remove {@link NetListener}
     */
    public void removeListener(NetListener listener) {
        this.listeners.remove(listener);
    }

    /**
     * return Application layer network protocol stack to use
     */
    PipelineFactory getPipeline() {
        return this.pipeline;
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
        this.lastActiveTime = System.currentTimeMillis();
        this.lastAcceptTime = System.currentTimeMillis();
        this.acceptCount.incrementAndGet();

        Runnable task = () -> {
            for (NetListener listener : listeners) {
                try {
                    listener.accept(channel);
                } catch (Exception ignored) {

                }
            }

            synchronized (acceptLock) {
                acceptLock.notifyAll();
            }
        };

        this.context.submitSoTask(channel.getChannelID(), new SimpleTask(task), this);
    }

    /**
     * socket closed
     */
    final Future<NetListen> notifyClose(NetChannel channel, boolean async) {
        this.lastActiveTime = System.currentTimeMillis();
        this.acceptCount.decrementAndGet();

        Runnable task = () -> {
            for (NetListener listener : listeners) {
                try {
                    listener.close(channel);
                } catch (Exception ignored) {
                }
            }

            synchronized (closeLock) {
                closeLock.notifyAll();
            }
        };

        if (async) {
            return this.context.submitSoTask(channel.getChannelID(), new SimpleTask(task), this);
        } else {
            task.run();
            return new BasicFuture<>(this);
        }
    }

    public void notifyError(Throwable e) {

    }

    /** Wait for an incoming. */
    public boolean waitAnyAccept() {
        if (this.acceptCount.get() > 0) {
            return true;
        }
        return this.waitAnyNewAccept();
    }

    /** Wait for an new incoming. */
    public boolean waitAnyNewAccept() {
        synchronized (this.acceptLock) {
            try {
                this.acceptLock.wait();
                return true;
            } catch (InterruptedException e) {
                return false;
            }
        }
    }

    /** Wait for all disconnection. */
    public boolean waitIdle() {
        while (true) {
            if (this.acceptCount.get() == 0) {
                return true;
            }
            synchronized (this.closeLock) {
                try {
                    this.closeLock.wait();
                } catch (InterruptedException e) {
                    return false;
                }
            }
        }
    }
}