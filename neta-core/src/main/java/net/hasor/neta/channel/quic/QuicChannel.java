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

/**
 * Connection-level QUIC channel.
 * <p>
 * Unlike the previous per-stream model, this channel represents the entire QUIC connection
 * (similar to {@link net.hasor.neta.channel.udp.UdpChannel} for UDP). There is exactly
 * <strong>one pipeline</strong> per QUIC connection, and the pipeline lifecycle
 * (onInit / onActive / onClose) maps to the connection lifecycle.
 * <p>
 * Stream creation and destruction are communicated via
 * {@link #fireUserEvent(Class, Object)} with {@link QuicStreamEvent}.
 * @author 赵永春 (zyc@hasor.net)
 * @see QuicConnection
 * @see QuicStreamEvent
 */
public class QuicChannel extends NetChannel {
    private final QuicConnection quicConnection;

    QuicChannel(long channelId, NetMonitor monitor, NetListen forListen, ProtoInitializer initializer, AsyncChannel asyncChannel, SoContextService context, QuicConnection quicConn) throws IOException {
        super(channelId, monitor, forListen, initializer, asyncChannel, context);
        this.quicConnection = quicConn;
    }

    /** Returns the underlying {@link QuicConnection} for stream-level operations. */
    public QuicConnection getQuicConnection() {
        return this.quicConnection;
    }
}
