/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.udp;
import java.net.SocketAddress;
import net.hasor.neta.channel.*;
/**
 * Listen-handle wrapper for a bound UDP server socket.
 * <p>This class is only the framework-visible {@link NetListen} descriptor. The actual datagram
 * receive loop and logical channel creation per remote address are handled by
 * {@link UdpAsyncServerChannel}.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
public class UdpNetListen extends NetListen {
    /**
     * Create a UDP listen handle.
     * @param channelId the channel ID
     * @param listenAddr the listen address
     * @param listenPort the listen port
     * @param channel the underlying server channel
     * @param initializer the protocol initializer
     * @param context the runtime context service
     * @param soConfig the listen configuration
     */
    protected UdpNetListen(long channelId, SocketAddress listenAddr, int listenPort, AsyncServerChannel channel,//
            ProtoInitializer initializer, SoContextService context, SoConfig soConfig) {
        super(channelId, listenAddr, listenPort, channel, initializer, context, soConfig);
    }
}
