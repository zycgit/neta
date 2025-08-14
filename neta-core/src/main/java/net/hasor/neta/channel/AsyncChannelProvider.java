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
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousChannelGroup;

/**
 * Asynchronous channel provider interface for creating server and client asynchronous channels.
 * @author 赵永春 (zyc@hasor.net)
 * @version 2025-08-07
 */
public interface AsyncChannelProvider {

    /**
     * Creates an asynchronous server channel
     * @param channelId Unique identifier for the channel
     * @param context Socket configuration context
     * @param channelGroup Asynchronous channel group for managing channel lifecycle
     * @return The created server channel
     * @throws IOException If an I/O error occurs during channel creation
     */
    AsyncServerChannel createServerChannel(long channelId, SoContext context, AsynchronousChannelGroup channelGroup, SocketAddress listenAddr, SoConfig soConfig) throws IOException;

    /**
     * Creates an asynchronous client channel
     * @param channelId Unique identifier for the channel
     * @param context Socket configuration context
     * @param channelGroup Asynchronous channel group for managing channel lifecycle
     * @param remoteAddr
     * @return The created client channel
     * @throws IOException If an I/O error occurs during channel creation
     */
    AsyncChannel createClientChannel(long channelId, SoContext context, AsynchronousChannelGroup channelGroup, SocketAddress remoteAddr, SoConfig soConfig) throws IOException;
}