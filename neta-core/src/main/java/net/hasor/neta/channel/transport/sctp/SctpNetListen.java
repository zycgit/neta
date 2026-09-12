/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.sctp;
import java.net.SocketAddress;
import net.hasor.neta.channel.*;
/**
 * Wrapper object for the listen handle bound by an SCTP server. The actual accept loop and the
 * lifecycle of the underlying socket are managed by {@link SctpAsyncServerChannel}.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
class SctpNetListen extends NetListen {
    SctpNetListen(long channelId, SocketAddress listenAddr, int listenPort, AsyncServerChannel channel,//
            ProtoInitializer initializer, SoContextService context, SoConfig soConfig) {
        super(channelId, listenAddr, listenPort, channel, initializer, context, soConfig);
    }
}
