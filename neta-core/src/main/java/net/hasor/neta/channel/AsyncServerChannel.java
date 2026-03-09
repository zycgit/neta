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
package net.hasor.neta.channel;
import java.io.Closeable;
import java.io.IOException;

/**
 * Transport-specific listen-side endpoint used behind {@link NetListen}.
 * <p>Implementations cover several different listen models: a real socket acceptor for TCP/SCTP,
 * a bound datagram endpoint that demultiplexes peers for UDP/QUIC, or an in-memory registry-backed
 * listener for the virtual transport. Calling {@link #bind(ProtoInitializer)} creates the
 * corresponding {@link NetListen} facade and activates the transport-specific receive or accept path.
 * <p>The application layer does not interact with {@code AsyncServerChannel} directly; it works with
 * the higher-level {@link NetListen} returned by {@link #bind(ProtoInitializer)}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2025-08-06
 * @see AsyncChannelProvider#createServerChannel
 * @see NetListen
 */
public interface AsyncServerChannel extends Closeable {
    /**
     * Gets the unique identifier of this channel.
     * @return The channel ID as a long value
     */
    long getChannelId();

    /** return socket config. */
    SoConfig getSoConfig();

    /**
     * Checks if the channel is currently open.
     * @return true if the channel is open, false otherwise
     */
    boolean isOpen();

    /**
     * Binds the server channel to a specific network address and starts listening for connections.
     * @throws IOException If an I/O error occurs during binding
     */
    NetListen bind(ProtoInitializer initializer) throws IOException;
}
