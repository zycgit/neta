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
import net.hasor.neta.channel.*;

import java.io.IOException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NetworkChannel;
import java.util.concurrent.ExecutorService;

public class UdpAsyncServerChannel implements AsyncServerChannel {
    private final long            channelID;
    private final DatagramChannel channel;

    public UdpAsyncServerChannel(long channelID, DatagramChannel channel) {
        this.channelID = channelID;
        this.channel = channel;
    }

    @Override
    public long getChannelID() {
        return this.channelID;
    }

    @Override
    public boolean isOpen() {
        return this.channel.isOpen();
    }

    @Override
    public NetworkChannel getChannel() {
        return this.channel;
    }

    @Override
    public void close() throws IOException {
        this.channel.close();
    }

    @Override
    public void bind(NetListen listen, SoContext context) throws IOException {
        this.channel.bind(listen.getLocalAddr());

        long channelId = ((SoContextService) context).nextID();
        ExecutorService executor = ((SoContextService) context).getIoExecutor();
        context.initChannel(listen, new UdpAsyncChannel(channelId, this.channel, executor));
    }

    public static AsyncServerChannel openChannel(long channelId, SoConfig config) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        SoConfigUtils.configListen(config, channel);
        return new UdpAsyncServerChannel(channelId, channel);
    }
}