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