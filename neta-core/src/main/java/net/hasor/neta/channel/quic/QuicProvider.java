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
import net.hasor.neta.channel.*;

/**
 * QUIC transport provider that creates server and client channels built on top of UDP (DatagramChannel).
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicProvider implements AsyncChannelProvider {
    public static final String NAME = "QUIC";

    public QuicProvider(NetManager neta) {
    }

    /** {@inheritDoc} Creates a QUIC server channel over a new DatagramChannel. */
    @Override
    public AsyncServerChannel createServerChannel(long channelId, SoContext context, SocketAddress listenAddr, SoConfig soConfig) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        return new QuicAsyncServerChannel(channelId, channel, context, listenAddr, (QuicSoConfig) soConfig);
    }

    /** {@inheritDoc} Creates a QUIC client channel over a new DatagramChannel. */
    @Override
    public AsyncChannel createClientChannel(long channelId, SoContext context, SocketAddress remoteAddr, SoConfig soConfig) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        return new QuicAsyncClientChannel(channelId, channel, context, remoteAddr, (QuicSoConfig) soConfig);
    }

    @Override
    public void shutdown() {

    }
}