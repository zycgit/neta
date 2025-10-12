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
package net.hasor.neta.channel.tcp;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP implementation of asynchronous client channel.
 * Provides TCP-specific implementation for establishing connections and performing I/O operations asynchronously.
 * Wraps Java NIO's AsynchronousSocketChannel for actual network operations.
 *
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
class TcpAsyncChannel implements AsyncChannel {
    private static final Logger                    logger = Logger.getLogger(TcpAsyncChannel.class);
    private final        long                      channelId;
    private final        AsynchronousSocketChannel channel;
    private final        SocketAddress             localAddress;
    private final        SocketAddress             remoteAddress;
    private final        AtomicBoolean             shutdownInputSignal;
    private final        SoContextService          context;
    private final        TcpSoConfig               soConfig;

    TcpAsyncChannel(long channelId, AsynchronousSocketChannel channel, SoContext context, SocketAddress remoteAddress, SoConfig soConfig) throws IOException {
        this.channelId = channelId;
        this.channel = channel;
        this.localAddress = channel.getLocalAddress();
        this.remoteAddress = remoteAddress;
        this.shutdownInputSignal = new AtomicBoolean(false);
        this.context = (SoContextService) context;
        this.soConfig = (TcpSoConfig) soConfig;
    }

    @Override
    public TcpSoConfig getSoConfig() {
        return this.soConfig;
    }

    @Override
    public long getChannelId() {
        return this.channelId;
    }

    @Override
    public SocketAddress getLocalAddress() {
        return this.localAddress;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return this.remoteAddress;
    }

    @Override
    public boolean isOpen() {
        return this.channel.isOpen();
    }

    /** Returns whether the read channel is closed. */
    public boolean isShutdownInput() {
        return this.shutdownInputSignal.get();
    }

    /**
     * Shuts down the input side of this channel.
     * @throws IOException If an I/O error occurs
     */
    public void shutdownInput() throws IOException {
        if (this.shutdownInputSignal.compareAndSet(false, true)) {
            if (this.context.getConfig().isPrintLog()) {
                logger.info("channel(" + this.getChannelId() + ") shutdownInput.");
            }
            this.channel.shutdownInput();
        }
    }

    @Override
    public void close() throws IOException {
        if (this.channel.isOpen()) {
            if (this.context.getConfig().isPrintLog()) {
                logger.info("tcpChannel(" + this.getChannelId() + ") close.");
            }
            IOUtils.closeQuietly(this.channel);
        }
    }

    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) throws Throwable {
        TcpSoConfigUtils.configSocket(this.soConfig, this.channel);
        TcpAsyncChannel asyncChannel = new TcpAsyncChannel(this.channelId, this.channel, this.context, this.remoteAddress, this.soConfig);
        TcpChannel channel = this.newChannel(asyncChannel, initializer);
        this.channel.connect(this.remoteAddress, this.context, new TcpConnectCompletionHandler(channel, asyncChannel, future));
    }

    protected TcpChannel newChannel(TcpAsyncChannel realChannel, ProtoInitializer initializer) throws IOException {
        NetMonitor monitor = new NetMonitor();
        return new TcpChannel(                                                  //
                realChannel.getChannelId(),                                     //
                monitor,                                                        //
                null,                                                           //
                initializer,                                                    //
                realChannel,                                                    //
                this.context,                                                   //
                new TcpRcvCompletionHandler(realChannel, this.context, monitor),//
                new TcpSndCompletionHandler(realChannel, this.context, monitor) //
        );
    }

    @Override
    public void write(NetChannel channel, SoSndContext wContext) {
        ((TcpChannel) channel).getWriteHandler().doWrite(wContext);
    }

    /** Reads a sequence of bytes from this channel into the given buffer. */
    public void read(ByteBuffer dst, SoContextService context, TcpRcvCompletionHandler handler, long rTimeoutMs, TimeUnit timeUnit) {
        this.channel.read(dst, rTimeoutMs, timeUnit, context, handler);
    }

    /** Writes a sequence of bytes to this channel from the given buffer. */
    public void write(ByteBuffer src, SoSndContext context, TcpSndCompletionHandler handler, long wTimeoutMs, TimeUnit timeUnit) {
        this.channel.write(src, wTimeoutMs, timeUnit, context, handler);
    }
}