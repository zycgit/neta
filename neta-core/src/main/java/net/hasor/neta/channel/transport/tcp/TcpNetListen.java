/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.tcp;
import java.net.SocketAddress;
import net.hasor.neta.channel.*;
/**
 * Wrapper object for the listen handle of a bound TCP server socket.
 * <p>This class is the framework-visible {@link NetListen} descriptor returned by bind operations.
 * The actual listening socket lifecycle and accept loop are implemented by
 * {@link TcpAsyncServerChannel}, not by this wrapper.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
class TcpNetListen extends NetListen {
    TcpNetListen(long channelId, SocketAddress listenAddr, int listenPort, AsyncServerChannel channel,//
            ProtoInitializer initializer, SoContextService context, SoConfig soConfig) {
        super(channelId, listenAddr, listenPort, channel, initializer, context, soConfig);
    }
}
