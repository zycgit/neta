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
import java.nio.channels.NetworkChannel;

/**
 * Asynchronous server channel interface for network communication.
 * Represents a server-side channel that can accept incoming connections asynchronously.
 * Extends Closeable to ensure proper resource cleanup.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-06
 */
public interface AsyncServerChannel extends Closeable {
    /**
     * Gets the unique identifier of this channel.
     * @return The channel ID as a long value
     */
    long getChannelID();

    /**
     * Checks if the channel is currently open.
     * @return true if the channel is open, false otherwise
     */
    boolean isOpen();

    /**
     * Gets the underlying network channel implementation.
     * @return The NetworkChannel instance
     */
    NetworkChannel getChannel();

    /**
     * Binds the server channel to a specific network address and starts listening for connections.
     * @param listen The network listening configuration
     * @param context The socket context for this connection
     * @param options
     * @throws IOException If an I/O error occurs during binding
     */
    void bind(NetListen listen, SoContext context, SoConfig options) throws IOException;
}
