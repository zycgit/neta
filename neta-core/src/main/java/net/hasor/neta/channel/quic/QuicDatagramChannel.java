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
 * DATAGRAM-level QUIC channel (RFC 9221).
 * <p>
 * Represents the DATAGRAM transport on top of a QUIC connection. Unlike streams,
 * there is at most one {@code QuicDatagramChannel} per connection. It has its own
 * independent pipeline, just like {@link QuicStreamChannel}.
 * <p>
 * Obtain via {@link QuicChannel#openDatagramChannel()}, which creates (or returns
 * an existing) instance using the connection's default {@link ProtoInitializer}.
 * <p>
 * <b>Send path</b>: Data written through the pipeline is sent via
 * {@link QuicDatagramChannelAsync} as QUIC DATAGRAM frames.
 * <p>
 * <b>Receive path</b>: {@link QuicChannel} delivers received DATAGRAM frame data
 * to this channel's pipeline via its channel ID.
 * <p>
 * Implements {@link SoSubChannel}: the parent is the connection-level {@link QuicChannel}.
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

    /**
     * Returns the parent connection-level {@link QuicChannel} that owns this DATAGRAM channel.
     * @return the parent {@link QuicChannel}
     */
    @Override
    public QuicChannel getParent() {
        return this.parent;
    }

    /**
     * Returns the {@link SslContext} from the parent QUIC connection.
     * @return the connection-level SSL context, or {@code null} if SSL is disabled
     */
    public SslContext getSslContext() {
        return this.parent.getSslContext();
    }

}