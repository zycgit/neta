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
import java.net.SocketAddress;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;

/**
 * Provides TCP-specific implementation for asynchronous server and client channels.
 * Implements the AsyncChannelProvider interface to create and configure TCP channels.
 *
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-07
 */
public class TcpProvider implements AsyncChannelProvider {
    public static final String NAME = "TCP";

    @Override
    public AsyncServerChannel createServerChannel(long channelId, SoContext context, AsynchronousChannelGroup channelGroup, SoConfig soConfig) throws IOException {
        AsynchronousServerSocketChannel channel = AsynchronousServerSocketChannel.open(channelGroup);
        SoConfigUtils.configListen(soConfig, channel);
        return new TcpAsyncServerChannel(channelId, channel);
    }

    @Override
    public AsyncChannel createClientChannel(long channelId, SoContext context, SocketAddress remoteAddr, AsynchronousChannelGroup channelGroup, SoConfig soConfig) throws IOException {
        AsynchronousSocketChannel channel = AsynchronousSocketChannel.open(channelGroup);
        SoConfigUtils.configSocket(soConfig, channel);
        return new TcpAsyncChannel(channelId, channel, soConfig);
    }
}