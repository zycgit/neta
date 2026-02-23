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
package net.hasor.neta.channel.quic;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.cobble.io.IOUtils;
import net.hasor.neta.channel.*;

/**
 * QUIC transport provider. Creates QUIC server and client channels
 * that are built on top of UDP (DatagramChannel).
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicProvider implements AsyncChannelProvider {
    public static final String NAME = "QUIC";

    private final Map<Long, QuicAsyncServerChannel> serverChannels = new ConcurrentHashMap<>();

    public QuicProvider(NetManager neta) {
        // reserved for future initialization
    }

    @Override
    public AsyncServerChannel createServerChannel(long channelId, SoContext context, SocketAddress listenAddr, SoConfig soConfig) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        QuicAsyncServerChannel serverChannel = new QuicAsyncServerChannel(channelId, channel, context, listenAddr, soConfig);
        this.serverChannels.put(channelId, serverChannel);
        return serverChannel;
    }

    @Override
    public AsyncChannel createClientChannel(long channelId, SoContext context, SocketAddress remoteAddr, SoConfig soConfig) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        return new QuicAsyncClientChannel(channelId, channel, context, remoteAddr, soConfig);
    }

    @Override
    public void shutdown() {
        for (QuicAsyncServerChannel server : this.serverChannels.values()) {
            IOUtils.closeQuietly(server);
        }
        this.serverChannels.clear();
    }
}
