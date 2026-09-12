/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.udp;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;
import net.hasor.neta.channel.*;
/**
 * UDP transport provider for Neta.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-07
 */
public class UdpProvider implements AsyncChannelProvider {
    public static final String NAME = "UDP";

    /**
     * Create the UDP provider.
     * @param neta the current NetManager
     */
    public UdpProvider(NetManager neta) {
    }

    /**
     * Create a UDP server channel.
     * @param channelId the channel ID
     * @param context the runtime context
     * @param listenAddr the listen address
     * @param soConfig the channel configuration
     * @return the UDP server channel
     * @throws IOException if an I/O error occurs during creation
     */
    @Override
    public AsyncServerChannel createServerChannel(long channelId, SoContext context, SocketAddress listenAddr, SoConfig soConfig) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        return new UdpAsyncServerChannel(channelId, channel, context, listenAddr, soConfig);
    }

    /**
     * Create a UDP client channel.
     * @param channelId the channel ID
     * @param context the runtime context
     * @param remoteAddr the remote address
     * @param soConfig the channel configuration
     * @return the UDP client channel
     * @throws IOException if an I/O error occurs during creation
     */
    @Override
    public AsyncChannel createClientChannel(long channelId, SoContext context, SocketAddress remoteAddr, SoConfig soConfig) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        return new UdpAsyncClientChannel(channelId, channel, context, remoteAddr, soConfig);
    }

    /**
     * Shut down the provider.
     * <p>The current implementation has no extra shared resources to release.
     */
    @Override
    public void shutdown() {

    }
}
