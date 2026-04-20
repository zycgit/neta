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
package net.hasor.neta.channel.transport.udp;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.*;
/**
 * Base asynchronous channel wrapper for the view of a single UDP peer.
 * <p>This type acts as the lightweight transport adapter behind {@link UdpChannel}. It stores the
 * local and remote address views and delegates actual send-queue flushing to {@link UdpWriteTask}.
 * <p>The receive loop is not implemented here. It is driven by {@link UdpTransport} together with
 * either {@link UdpAsyncClientChannel} or {@link UdpAsyncServerChannel}. What this base class
 * really provides is the single-writer send gate shared by both client and server modes.
 * <p><b>Send flow:</b>
 * <pre>
 *   NetChannel.sendData(...) / flush()
 *                  ▼
 *        write(channel, wContext)
 *       ┌──────────┴────────────────┐
 *       ▼                           ▼
 *   queue empty, return   writing=false -> CAS succeeds
 *                                   ▼
 *                        asyncWrite(channel, wContext)
 *                                   ▼
 *                              UdpWriteTask
 *                                   ▼
 *                         onFinal(...) -> writing=false
 * </pre>
 * <p><b>Responsibility boundary:</b> this class only owns the shared address view and send gate;
 * the actual receive loop is driven by higher-level client or server channel types.
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
    protected final AtomicBoolean writing;

    /**
     * Create a UDP asynchronous channel wrapper.
     * @param channelId the channel ID
     * @param channel the underlying DatagramChannel
     * @param context the runtime context
     * @param remoteAddress the remote address
     * @param soConfig the channel configuration
     * @throws IOException if an I/O error occurs while initializing the local address
     */
    protected UdpAsyncChannel(long channelId, DatagramChannel channel, SoContext context, SocketAddress remoteAddress, SoConfig soConfig) throws IOException {
        this.channelId = channelId;
        this.channel = channel;
        this.localAddress = (InetSocketAddress) channel.getLocalAddress();
        this.remoteAddress = (InetSocketAddress) remoteAddress;
        this.context = (SoContextService) context;
        this.soConfig = (UdpSoConfig) soConfig;

        this.writing = new AtomicBoolean(false);
    }

    /**
     * Return the UDP configuration used by the current channel.
     * @return the UDP configuration object
     */
    @Override
    public UdpSoConfig getSoConfig() {
        return this.soConfig;
    }

    /**
     * Return the framework channel ID.
     * @return the channel ID
     */
    @Override
    public long getChannelId() {
        return this.channelId;
    }

    /**
     * Return the local address of the current channel.
     * @return the local address
     */
    @Override
    public SocketAddress getLocalAddress() {
        return this.localAddress;
    }

    /**
     * Return the remote address of the current channel.
     * @return the remote address
     */
    @Override
    public SocketAddress getRemoteAddress() {
        return this.remoteAddress;
    }

    //

    /**
     * Determine whether the underlying DatagramChannel is still open.
     * @return true if the channel is open
     */
    @Override
    public boolean isOpen() {
        return this.channel.isOpen();
    }

    /**
     * Close the current UDP asynchronous channel.
     * <p>The base implementation does not actively close any resources. Concrete subclasses manage
     * their own lifecycle.
     * @throws IOException if an I/O error occurs while closing
     */
    @Override
    public void close() throws IOException {
    }

    /**
     * Start the connection flow.
     * <p>The base UDP asynchronous channel does not support this directly. Concrete subclasses
     * decide the actual connection strategy.
     * @param initializer the protocol initializer
     * @param future the connection-result future
     */
    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        throw new UnsupportedOperationException();
    }

    /**
     * Trigger one write flow.
     * <p>This method guarantees through a single-writer gate that at most one send task flushes
     * the underlying channel at any given time.
     * @param channel the framework-level channel
     * @param wContext the send context
     */
    @Override
    public void write(NetChannel channel, SoSndContext wContext) {
        if (wContext.isEmpty()) {
            return;
        }

        if (this.writing.compareAndSet(false, true)) {
            this.asyncWrite(channel, wContext);
        }
    }

    /**
     * Submit a UDP write task asynchronously.
     * @param channel the framework-level channel
     * @param wContext the send context
     */
    protected void asyncWrite(NetChannel channel, SoSndContext wContext) {
        UdpWriteTask task = new UdpWriteTask(channel, this.channel, wContext, this.context);
        this.context.submitSoTask(task, this).onFinal(f -> {
            this.writing.set(false);
        });
    }
}
