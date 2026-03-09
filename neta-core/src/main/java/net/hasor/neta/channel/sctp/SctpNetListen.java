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
import java.net.SocketAddress;
import net.hasor.neta.channel.*;

/**
 * Listen-handle wrapper for an SCTP server binding.
 * <p>This class is only the framework-facing {@link NetListen} descriptor for an
 * SCTP listen socket. The actual accept loop and socket lifecycle are managed by
 * {@link SctpAsyncServerChannel}.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
class SctpNetListen extends NetListen {
    SctpNetListen(long channelId, SocketAddress listenAddr, int listenPort, AsyncServerChannel channel,//
            ProtoInitializer initializer, SoContextService context, SoConfig soConfig) {
        super(channelId, listenAddr, listenPort, channel, initializer, context, soConfig);
    }
}