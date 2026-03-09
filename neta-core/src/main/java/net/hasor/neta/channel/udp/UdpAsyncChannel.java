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
import java.nio.channels.DatagramChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.*;

/**
 * Base async-channel wrapper for one UDP peer view.
 * <p>This type is used as the lightweight transport adapter behind {@link UdpChannel}
 * instances. It stores the remote/local addressing view and delegates outbound
 * queue flushing to {@link UdpWriteTask}.
 * <p>Inbound receive loops are <em>not</em> implemented here: they are driven by
 * {@link UdpTransport} through {@link UdpAsyncClientChannel} or
 * {@link UdpAsyncServerChannel}. What this base class actually provides is the
 * common outbound single-writer gate shared by both modes.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 * @see java.nio.channels.DatagramChannel
 */
public class UdpAsyncChannel implements AsyncChannel {
    protected final long              channelId;
    protected final DatagramChannel   channel;
    protected final InetSocketAddress localAddress;
    protected final InetSocketAddress remoteAddress;
    protected final SoContextService  context;
    protected final UdpSoConfig       soConfig;
    //
    protected final AtomicBoolean     writing;

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
