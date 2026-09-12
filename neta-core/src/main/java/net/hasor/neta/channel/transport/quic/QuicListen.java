/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
import java.net.SocketAddress;
import net.hasor.neta.channel.AsyncServerChannel;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SoConfig;
import net.hasor.neta.channel.SoContextService;
import net.hasor.neta.channel.transport.udp.UdpNetListen;
/**
 * QUIC listening endpoint.
 * <p>Because QUIC is built on top of UDP, this type extends {@link UdpNetListen} and carries listening-phase state such as the listen address, initializer, and context.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicListen extends UdpNetListen {
    QuicListen(long channelId, SocketAddress listenAddr, int listenPort, AsyncServerChannel channel,//
            ProtoInitializer initializer, SoContextService context, SoConfig soConfig) {
        super(channelId, listenAddr, listenPort, channel, initializer, context, soConfig);
    }
}
