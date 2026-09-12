/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.tcp;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;
/**
 * Asynchronous channel adapter around a single {@link AsynchronousSocketChannel}.
 * <p>This type is used for both outbound client connections and connections accepted by
 * {@link TcpAsyncServerChannel}. It wraps the raw AIO socket and exposes transport operations to
 * higher-level components such as {@link TcpChannel} and the related completion handlers.
 * <p><b>Runtime structure:</b>
 * <pre>
 *   AsynchronousSocketChannel
 *      +--> TcpAsyncChannel
 *          +--> TcpConnectCompletionHandler   (connect completion)
 *          +--> TcpRcvCompletionHandler       (read loop)
 *          +--> TcpSndCompletionHandler       (single-writer send loop)
 *          +--> TcpChannel                    (application-facing NetChannel)
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
class TcpAsyncChannel implements AsyncChannel {
    private static final Logger             logger = Logger.getLogger(TcpAsyncChannel.class);
    private final long                      channelId;
    private final AsynchronousSocketChannel channel;
    private final SocketAddress             localAddress;
    private final SocketAddress             remoteAddress;
    private final AtomicBoolean             shutdownInputSignal;
    private final SoContextService          context;
    private final TcpSoConfig               soConfig;

    /**
     * Create a TCP asynchronous channel adapter.
     * @param channelId the channel ID
     * @param channel the underlying asynchronous socket channel
     * @param context the runtime context
     * @param remoteAddress the remote address
     * @param soConfig the channel configuration
     * @throws IOException if an I/O error occurs while initializing address information
     */
    TcpAsyncChannel(long channelId, AsynchronousSocketChannel channel, SoContext context, SocketAddress remoteAddress, SoConfig soConfig) throws IOException {
        this.channelId = channelId;
        this.channel = channel;
        this.localAddress = channel.getLocalAddress();
        this.remoteAddress = remoteAddress;
        this.shutdownInputSignal = new AtomicBoolean(false);
        this.context = (SoContextService) context;
        this.soConfig = (TcpSoConfig) soConfig;
    }

    /**
     * Return the TCP configuration used by the current channel.
     * @return the TCP configuration object
     */
    @Override
    public TcpSoConfig getSoConfig() {
        return this.soConfig;
    }

    /**
     * Return the channel ID assigned by the framework.
     * @return the channel ID
     */
    @Override
    public long getChannelId() {
        return this.channelId;
    }

    /**
     * Return the local address.
     * @return the local address
     */
    @Override
    public SocketAddress getLocalAddress() {
        return this.localAddress;
    }

    /**
     * Return the remote address.
     * @return the remote address
     */
    @Override
    public SocketAddress getRemoteAddress() {
        return this.remoteAddress;
    }

    /**
     * Determine whether the underlying channel is still open.
     * @return true if the channel is open
     */
    @Override
    public boolean isOpen() {
        return this.channel.isOpen();
    }

    /**
     * Return whether the read side has been shut down.
     * @return true if the read side is shut down
     */
    public boolean isShutdownInput() {
        return this.shutdownInputSignal.get();
    }

    /**
     * Shut down the input side of the current channel.
     * @throws IOException if an I/O error occurs during shutdown
     */
    public void shutdownInput() throws IOException {
        if (this.shutdownInputSignal.compareAndSet(false, true)) {
            if (this.context.getConfig().isPrintLog()) {
                logger.info("channel(" + this.getChannelId() + ") shutdownInput.");
            }
            this.channel.shutdownInput();
        }
    }

    /**
     * Close the underlying channel.
     * @throws IOException if an I/O error occurs while closing
     */
    @Override
    public void close() throws IOException {
        if (this.channel.isOpen()) {
            if (this.context.getConfig().isPrintLog()) {
                logger.info("tcpChannel(" + this.getChannelId() + ") close.");
            }
            IOUtils.closeQuietly(this.channel);
        }
    }

    /**
     * Initiate a connection and create the framework-level channel after success.
     * @param initializer the protocol initializer
     * @param future the future used to receive the connection result
     * @throws Throwable if an exception occurs during connection setup or initiation
     */
    @Override
    public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) throws Throwable {
        TcpSoConfigUtils.configSocket(this.soConfig, this.channel);
        TcpAsyncChannel asyncChannel = new TcpAsyncChannel(this.channelId, this.channel, this.context, this.remoteAddress, this.soConfig);
        TcpChannel channel = this.newChannel(asyncChannel, initializer);
        this.channel.connect(this.remoteAddress, this.context, new TcpConnectCompletionHandler(channel, asyncChannel, future));
    }

    /**
     * Create a framework-level TCP channel for the underlying asynchronous channel.
     * @param realChannel the underlying asynchronous channel
     * @param initializer the protocol initializer
     * @return the newly created TCP channel
     * @throws IOException if an I/O error occurs during creation
     */
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

    /**
     * Trigger one write flow.
     * @param channel the framework-level channel
     * @param wContext the send context
     */
    @Override
    public void write(NetChannel channel, SoSndContext wContext) {
        ((TcpChannel) channel).getWriteHandler().doWrite(wContext);
    }

    /**
     * Asynchronously read a sequence of bytes from the current channel into the given buffer.
     * @param dst the target buffer
     * @param context the runtime context
     * @param handler the read completion handler
     * @param rTimeoutMs the read timeout
     * @param timeUnit the timeout unit
     */
    public void read(ByteBuffer dst, SoContextService context, TcpRcvCompletionHandler handler, long rTimeoutMs, TimeUnit timeUnit) {
        this.channel.read(dst, rTimeoutMs, timeUnit, context, handler);
    }

    /**
     * Asynchronously write a sequence of bytes from the given buffer to the current channel.
     * @param src the source buffer
     * @param context the send context
     * @param handler the write completion handler
     * @param wTimeoutMs the write timeout
     * @param timeUnit the timeout unit
     */
    public void write(ByteBuffer src, SoSndContext context, TcpSndCompletionHandler handler, long wTimeoutMs, TimeUnit timeUnit) {
        this.channel.write(src, wTimeoutMs, timeUnit, context, handler);
    }
}
