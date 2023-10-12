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

import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 监听
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
public class NetListen implements Channel<NetListen> {
    private final   long                            channelID;
    private final   long                            createdTime;
    private         long                            lastActiveTime;
    private final   InetSocketAddress               listen;
    protected final AsynchronousServerSocketChannel channel;
    private final   SoContextImpl                   context;
    private         boolean                         suspend;
    //
    protected final AtomicBoolean                   closeStatus;
    protected final Future<NetListen>               closeFuture;

    NetListen(long channelID, long createdTime, InetSocketAddress listen, AsynchronousServerSocketChannel channel, SoContextImpl context) {
        this.channelID = channelID;
        this.createdTime = createdTime;
        this.lastActiveTime = createdTime;
        this.listen = listen;
        this.channel = channel;
        this.context = context;

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

    /** 侦听器是否被挂起，监听器被挂起后侦听端口仍然打开，但是所有传入的链接都会被 close。 */
    public boolean isSuspend() {
        return this.suspend;
    }

    /** 监听器挂起，监听器被挂起后侦听端口仍然打开，但是所有传入的链接都会被 close */
    public void suspend() {
        this.suspend = true;
    }

    /** 监听器恢复，恢复后可以继续处理传入的链接 */
    public void resume() {
        this.suspend = false;
    }

    /** 获取侦听的端口号 */
    public int getListenPort() {
        return this.listen.getPort();
    }

    @Override
    public boolean isClose() {
        return !this.channel.isOpen() || this.closeStatus.get();
    }

    @Override
    public Future<NetListen> close() {
        if (this.closeStatus.compareAndSet(false, true)) {
            if (this.channel.isOpen()) {
                SoCloseTask task = new SoCloseTask(this.channelID, this.context);
                this.context.submitSoTask(this.context.getResourceManager(), task, this).onCompleted(f -> {
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

    public Future<NetListen> closeNow() {
        if (this.channel.isOpen() && this.closeStatus.compareAndSet(false, true)) {
            new SoCloseTask(this.channelID, this.context).run();
        }
        this.closeFuture.completed(this);
        return this.closeFuture;
    }

    final void notifyAccept(long channelID) {
        this.lastActiveTime = System.currentTimeMillis();
    }

}