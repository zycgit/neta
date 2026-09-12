/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.tcp;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;
/**
 * TCP server-side transport implementation based on {@link AsynchronousServerSocketChannel}.
 * <p>This class binds the listening socket, creates the framework-level {@link TcpNetListen}, and
 * re-arms {@link TcpAcceptCompletionHandler} after each successfully accepted connection in order
 * to keep the accept loop alive.
 * <p><b>Accept path:</b>
 * <pre>
 *   AsynchronousServerSocketChannel.accept(...)
 *                 v
 *      TcpAcceptCompletionHandler
 *                 |
 *                 +--> configure accepted socket
 *                 +--> create TcpAsyncChannel
 *                 +--> create TcpChannel
 *                 +--> initialize the pipeline
 *                 +--> start TcpRcvCompletionHandler.read()
 * </pre>
 * <p>The server transport itself is responsible only for the listening socket; each accepted peer
 * connection is handed off to its own {@link TcpAsyncChannel}.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
class TcpAsyncServerChannel implements AsyncServerChannel {
    private static final Logger                   logger = Logger.getLogger(TcpAsyncServerChannel.class);
    private final long                            channelId;
    private final AsynchronousServerSocketChannel channel;
    private final SoContextService                context;
    private final InetSocketAddress               listenAddr;
    private final TcpSoConfig                     soConfig;

    /**
     * Create a TCP asynchronous server channel.
     * @param channelId the channel ID
     * @param channel the underlying server listen channel
     * @param context the runtime context
     * @param listenAddr the listen address
     * @param soConfig the channel configuration
     */
    TcpAsyncServerChannel(long channelId, AsynchronousServerSocketChannel channel, SoContext context, SocketAddress listenAddr, SoConfig soConfig) {
        this.channelId = channelId;
        this.channel = channel;
        this.context = (SoContextService) context;
        this.listenAddr = (InetSocketAddress) listenAddr;
        this.soConfig = (TcpSoConfig) soConfig;
    }

    /**
     * Return the internal identifier of the listening channel.
     * @return the channel ID
     */
    @Override
    public long getChannelId() {
        return this.channelId;
    }

    /**
     * Return the configuration used by the current listener.
     * @return the configuration object
     */
    @Override
    public SoConfig getSoConfig() {
        return this.soConfig;
    }

    /**
     * Determine whether the listening channel is still open.
     * @return true if the channel is open
     */
    @Override
    public boolean isOpen() {
        return this.channel.isOpen();
    }

    /**
     * Bind the listening address and start the accept flow.
     * @param initializer the protocol initializer for new connections
     * @return the listen handle
     * @throws IOException if an I/O error occurs during bind or initialization
     */
    @Override
    public NetListen bind(ProtoInitializer initializer) throws IOException {
        // Create the listen handle.
        TcpSoConfigUtils.configListen(this.soConfig, this.channel);
        NetListen listen = new TcpNetListen( //
                this.channelId,           //
                this.listenAddr,          //
                this.listenAddr.getPort(),//
                this,                     //
                initializer,              //
                this.context,             //
                this.soConfig);

        // Initialize and start the accept flow.
        try {
            this.context.initChannel(listen, false);
            this.channel.bind(this.listenAddr, 0);
            this.channel.accept(context, new TcpAcceptCompletionHandler(listen, this.channel, this.soConfig));
        } catch (Throwable e) {
            SoBindException ee = e instanceof SoBindException ? (SoBindException) e : new SoBindException(e.getMessage(), e);
            this.context.notifyBindChannelException(this.channelId, ee);
            throw ee;
        }
        return listen;
    }

    /**
     * Close the listening channel.
     * @throws IOException if an I/O error occurs while closing
     */
    @Override
    public void close() throws IOException {
        if (this.context.getConfig().isPrintLog()) {
            logger.info("tcpListen(" + this.getChannelId() + ") close.");
        }
        this.channel.close();
    }
}
