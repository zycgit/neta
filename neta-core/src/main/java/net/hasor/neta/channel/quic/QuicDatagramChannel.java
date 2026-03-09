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
package net.hasor.neta.channel.quic;
import java.io.IOException;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.ssl.SslContext;

/**
 * QUIC DATAGRAM sub-channel for RFC 9221 unreliable messages.
 * <p>
 * Each QUIC connection owns at most one DATAGRAM channel instance. It has its own
 * pipeline and can be created explicitly through {@link QuicChannel#openDatagramChannel()},
 * or lazily on first inbound DATAGRAM delivery when the peer sends datagram data.
 * <pre>
 *   QuicChannel
 *      |
 *      +--> QuicDatagramChannel (0 or 1 instance per connection)
 *               |
 *               +--> unreliable message pipeline
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @see QuicChannel#openDatagramChannel()
 */
public class QuicDatagramChannel extends NetChannel implements SoSubChannel {
    private final QuicChannel parent;

    QuicDatagramChannel(long channelId, NetMonitor monitor, NetListen forListen, ProtoInitializer initializer,//
            QuicDatagramChannelAsync asyncChannel, SoContextService soContext, QuicChannel parent) throws IOException {
        super(channelId, monitor, forListen, initializer, asyncChannel, soContext);
        this.parent = parent;
    }

    /** Returns the parent connection-level {@link QuicChannel} that owns this DATAGRAM channel. */
    @Override
    public QuicChannel getParent() {
        return this.parent;
    }

    /** Returns the {@link SslContext} from the parent QUIC connection, or null if SSL is disabled. */
    public SslContext getSslContext() {
        return this.parent.getSslContext();
    }

}