/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;
import net.hasor.neta.channel.*;
/**
 * QUIC transport provider for creating server and client channels on top of UDP DatagramChannel.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicProvider implements AsyncChannelProvider {
    public static final String NAME = "QUIC";

    /**
     * Creates a QUIC provider.
     * @param neta the owning NetManager
     */
    public QuicProvider(NetManager neta) {
    }

    /**
     * Creates a QUIC server channel backed by a new DatagramChannel.
     */
    @Override
    public AsyncServerChannel createServerChannel(long channelId, SoContext context, SocketAddress listenAddr, SoConfig soConfig) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        return new QuicAsyncServerChannel(channelId, channel, context, listenAddr, (QuicSoConfig) soConfig);
    }

    /**
     * Creates a QUIC client channel backed by a new DatagramChannel.
     */
    @Override
    public AsyncChannel createClientChannel(long channelId, SoContext context, SocketAddress remoteAddr, SoConfig soConfig) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        return new QuicAsyncClientChannel(channelId, channel, context, remoteAddr, (QuicSoConfig) soConfig);
    }

    /**
     * Shuts down the provider.
     * <p>The current implementation has no additional resources to release.
     */
    @Override
    public void shutdown() {

    }
}
