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
import net.hasor.cobble.concurrent.future.Future;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;

/**
 * Asynchronous channel interface for network communication.
 * Represents a bidirectional communication channel that can perform operations asynchronously.
 * Extends Closeable to ensure proper resource cleanup.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
public interface AsyncChannel extends Closeable {

    /**
     * Gets the unique identifier of this channel.
     * @return The channel ID as a long value
     */
    long getChannelId();

    /** return socket config. */
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
     * Gets the target object associated with this channel.
     * @return The target object
     */
    Object getTarget();

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

    /**
     * Writes data from the given buffer to this channel.
     * @param channel The NetChannel instance
     * @param wContext The send context containing the data to be written
     */
    void write(NetChannel channel, SoSndContext wContext);

    /**
     * Connects this channel to the given remote address.
     * @throws IOException If an I/O error occurs
     */
    void connectTo(ProtoInitializer initializer, Future<NetChannel> future) throws Throwable;
}