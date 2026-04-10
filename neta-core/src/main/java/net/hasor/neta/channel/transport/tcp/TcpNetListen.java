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