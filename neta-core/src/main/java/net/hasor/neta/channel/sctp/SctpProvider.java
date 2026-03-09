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
 * Provider that creates SCTP client and server transports for Neta.
 * <p>It opens the underlying JDK SCTP channels and wraps them as
 * {@link SctpAsyncChannel} or {@link SctpAsyncServerChannel}. Beyond object
 * creation, this provider currently has no additional lifecycle logic.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-07
 */
public class SctpProvider implements AsyncChannelProvider {
    public static final String NAME = "SCTP";

    public SctpProvider(NetManager neta) throws IOException {
    }

    @Override
    public AsyncServerChannel createServerChannel(long channelId, SoContext context, SocketAddress listenAddr, SoConfig soConfig) throws IOException {
        SctpServerChannel channel = SctpServerChannel.open();
        return new SctpAsyncServerChannel(channelId, channel, context, listenAddr, soConfig);
    }

    @Override
    public AsyncChannel createClientChannel(long channelId, SoContext context, SocketAddress remoteAddr, SoConfig soConfig) throws IOException {
        com.sun.nio.sctp.SctpChannel channel = com.sun.nio.sctp.SctpChannel.open();
        return new SctpAsyncChannel(channelId, channel, null, remoteAddr, (SoContextService) context, (SctpSoConfig) soConfig);
    }

    @Override
    public void shutdown() {
    }
}