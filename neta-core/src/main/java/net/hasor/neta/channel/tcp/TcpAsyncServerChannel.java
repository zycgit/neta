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
package net.hasor.neta.channel.tcp;
import net.hasor.neta.channel.*;

import java.io.IOException;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.NetworkChannel;

/**
 * TCP implementation of asynchronous server channel.
 * Provides TCP-specific implementation for accepting incoming connections asynchronously.
 * Wraps Java NIO's AsynchronousServerSocketChannel for actual network operations.
 *
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class TcpAsyncServerChannel implements AsyncServerChannel {
    private final AsynchronousServerSocketChannel channel;
    private final long                            channelID;

    public TcpAsyncServerChannel(long channelId, AsynchronousServerSocketChannel channel) {
        this.channel = channel;
        this.channelID = channelId;
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
        this.channel.bind(listen.getLocalAddr(), 0);
        this.channel.accept(context, new TcpAcceptCompletionHandler(listen, this.channel));
    }

    public static AsyncServerChannel openChannel(long channelId, SoConfig config, AsynchronousChannelGroup channelGroup) throws IOException {
        AsynchronousServerSocketChannel channel = AsynchronousServerSocketChannel.open(channelGroup);
        SoConfigUtils.configListen(config, channel);
        return new TcpAsyncServerChannel(channelId, channel);
    }
}