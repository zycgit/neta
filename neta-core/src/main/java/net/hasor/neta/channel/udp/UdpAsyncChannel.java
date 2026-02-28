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
package net.hasor.neta.channel.udp;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;

/**
 * An implementation of the {@link AsyncChannel} interface for UDP communication.
 * This class provides asynchronous read and write operations over a UDP channel,
 * using a non-blocking {@link DatagramChannel} and a dedicated I/O executor service.
 * <p>
 * The UdpAsyncChannel supports reading data into a {@link ByteBuffer} with or without
 * a specified timeout. It does not support writing data, as well as connecting to a remote
 * address, which are unsupported operations for this type of channel.
 * <p>
 * Upon creation, the channel is registered with a selector for reading, and all I/O operations
 * are performed by the provided I/O executor service.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 * @see java.nio.channels.DatagramChannel
 * @see java.util.concurrent.ExecutorService
 */
public class UdpAsyncChannel implements AsyncChannel {
    private static final Logger            logger = Logger.getLogger(UdpAsyncChannel.class);
    protected final      long              channelId;
    protected final      DatagramChannel   channel;
    protected final      InetSocketAddress localAddress;
    protected final      InetSocketAddress remoteAddress;
    protected final      SoContextService  context;
    protected final      UdpSoConfig       soConfig;
    //
    protected final      AtomicBoolean     writing;

    protected UdpAsyncChannel(long channelId, DatagramChannel channel, SoContext context, SocketAddress remoteAddress, SoConfig soConfig) throws IOException {
        this.channelId = channelId;
        this.channel = channel;
        this.localAddress = (InetSocketAddress) channel.getLocalAddress();
        this.remoteAddress = (InetSocketAddress) remoteAddress;
        this.context = (SoContextService) context;
        this.soConfig = (UdpSoConfig) soConfig;

        this.writing = new AtomicBoolean(false);
    }

    @Override
    public UdpSoConfig getSoConfig() {
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

    //

    @Override
    public boolean isOpen() {
        return this.channel.isOpen();
    }

    @Override
    public void close() throws IOException {
    }

    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void write(NetChannel channel, SoSndContext wContext) {
        if (wContext.isEmpty()) {
            return;
        }

        if (this.writing.compareAndSet(false, true)) {
            this.asyncWrite(channel, wContext);
        }
    }

    protected void asyncWrite(NetChannel channel, SoSndContext wContext) {
        UdpWriteTask task = new UdpWriteTask(channel, this.channel, wContext, this.context);
        this.context.submitSoTask(task, this).onFinal(f -> {
            this.writing.set(false);
        });
    }
}
