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
import java.net.SocketAddress;
import net.hasor.cobble.concurrent.future.Future;

/**
 * Transport-specific outbound endpoint used behind {@link NetChannel}.
 * <p>This interface abstracts the actual carrier used by Neta transports: an OS socket
 * for TCP/SCTP, a datagram endpoint for UDP/QUIC, or an in-memory transport endpoint for
 * the virtual channel family. Application code does not use it directly; it is created by
 * {@link AsyncChannelProvider} and driven through {@link NetChannel}.
 * <p><b>Lifecycle:</b>
 * <ol>
 *   <li>Created by {@link AsyncChannelProvider#createClientChannel(long, SoContext, SocketAddress, SoConfig)}.</li>
 *   <li>{@link #connectTo(ProtoInitializer, Future)} materializes the public-facing
 *       {@link NetChannel} and completes the connect future when that channel is ready.</li>
 *   <li>{@link #write(NetChannel, SoSndContext)} drains encoded outbound data from the
 *       channel's send queue using the transport's own execution model.</li>
 *   <li>{@link #close()} releases the underlying transport resources.</li>
 * </ol>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 * @see AsyncChannelProvider
 * @see NetChannel
 */
public interface AsyncChannel extends Closeable {

    /**
     * Gets the unique identifier of this channel.
     * @return The channel ID as a long value
     */
    long getChannelId();

    /** Returns the socket configuration. */
    SoConfig getSoConfig();

    /**
     * Gets the local address to which this channel is bound.
     * @return The local SocketAddress
     */
    SocketAddress getLocalAddress();

    /**
     * Gets the remote address to which this channel is connected.
     * @return The remote SocketAddress
     */
    SocketAddress getRemoteAddress();

    /**
     * Checks if this channel is open.
     * @return true if the channel is open, false otherwise
     */
    boolean isOpen();

    /**
     * Closes this channel.
     * @throws IOException If an I/O error occurs
     */
    @Override
    void close() throws IOException;

    /** Writes pending outbound data for the given NetChannel. */
    void write(NetChannel channel, SoSndContext wContext);

    /**
     * Starts the connection flow using the given initializer.
     */
    void connectTo(ProtoInitializer initializer, Future<NetChannel> future) throws Throwable;
}