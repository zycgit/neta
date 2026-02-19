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
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.*;

/**
 * Provides TCP-specific implementation for asynchronous server and client channels.
 * Implements the AsyncChannelProvider interface to create and configure TCP channels.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-07
 */
public class TcpProvider implements AsyncChannelProvider {
    public static final  String                   NAME   = "TCP";
    private static final Logger                   logger = Logger.getLogger(TcpProvider.class);
    private final        AsynchronousChannelGroup channelGroup;

    public TcpProvider(NetManager neta) throws IOException {
        this.channelGroup = AsynchronousChannelGroup.withThreadPool(((SoContextService) neta.getContext()).getIoExecutor());
    }

    @Override
    public AsyncServerChannel createServerChannel(long channelId, SoContext context, SocketAddress listenAddr, SoConfig soConfig) throws IOException {
        AsynchronousServerSocketChannel channel = AsynchronousServerSocketChannel.open(this.channelGroup);
        return new TcpAsyncServerChannel(channelId, channel, context, listenAddr, soConfig);
    }

    @Override
    public AsyncChannel createClientChannel(long channelId, SoContext context, SocketAddress remoteAddr, SoConfig soConfig) throws IOException {
        AsynchronousSocketChannel channel = AsynchronousSocketChannel.open(this.channelGroup);
        return new TcpAsyncChannel(channelId, channel, context, remoteAddr, soConfig);
    }

    @Override
    public void shutdown() {
        if (this.channelGroup != null) {
            this.channelGroup.shutdown();
            try {
                if (!this.channelGroup.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    logger.info("close channelGroup waiting...");
                    this.channelGroup.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        logger.info("close tcpChannelGroup done.");
    }
}