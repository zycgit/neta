/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.neta.channel.sctp;
import java.io.IOException;
import java.net.SocketAddress;
import com.sun.nio.sctp.SctpServerChannel;
import net.hasor.neta.channel.*;

/**
 * Provider that creates SCTP client and server transport objects for Neta.
 * <p>This implementation opens the underlying JDK SCTP channels and wraps them as
 * {@link SctpAsyncChannel} or {@link SctpAsyncServerChannel}. It contains no extra
 * lifecycle management beyond object creation.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-07
 */
public class SctpProvider implements AsyncChannelProvider {
    public static final String NAME = "SCTP";

    /**
     * Create the SCTP provider.
     * @param neta the current NetManager
     * @throws IOException if an I/O error occurs while initializing underlying resources
     */
    public SctpProvider(NetManager neta) throws IOException {
    }

    /**
     * Create an SCTP server channel.
     * @param channelId the channel ID
     * @param context the runtime context
     * @param listenAddr the listen address
     * @param soConfig the channel configuration
     * @return the asynchronous server channel
     * @throws IOException if an I/O error occurs while creating the underlying channel
     */
    @Override
    public AsyncServerChannel createServerChannel(long channelId, SoContext context, SocketAddress listenAddr, SoConfig soConfig) throws IOException {
        SctpServerChannel channel = SctpServerChannel.open();
        return new SctpAsyncServerChannel(channelId, channel, context, listenAddr, soConfig);
    }

    /**
     * Create an SCTP client channel.
     * @param channelId the channel ID
     * @param context the runtime context
     * @param remoteAddr the remote address
     * @param soConfig the channel configuration
     * @return the asynchronous client channel
     * @throws IOException if an I/O error occurs while creating the underlying channel
     */
    @Override
    public AsyncChannel createClientChannel(long channelId, SoContext context, SocketAddress remoteAddr, SoConfig soConfig) throws IOException {
        com.sun.nio.sctp.SctpChannel channel = com.sun.nio.sctp.SctpChannel.open();
        return new SctpAsyncChannel(channelId, channel, null, remoteAddr, (SoContextService) context, (SctpSoConfig) soConfig);
    }

    /**
     * Shut down the provider.
     * <p>The current implementation has no extra resources to release, so this is a no-op.
     */
    @Override
    public void shutdown() {
    }
}