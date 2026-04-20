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
/**
 * Transport-provider SPI used to create underlying client and server channels.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2025-08-07
 * @see AsyncChannel
 * @see AsyncServerChannel
 */
public interface AsyncChannelProvider {

    /**
     * Create an asynchronous server channel.
     * @param channelId unique channel identifier
     * @param context socket configuration context
     * @return created server channel
     * @throws IOException thrown when an I/O error occurs during channel creation
     */
    AsyncServerChannel createServerChannel(long channelId, SoContext context, SocketAddress listenAddr, SoConfig soConfig) throws IOException;

    /**
     * Create an asynchronous client channel.
     * @param channelId unique channel identifier
     * @param context socket configuration context
     * @return created client channel
     * @throws IOException thrown when an I/O error occurs during channel creation
     */
    AsyncChannel createClientChannel(long channelId, SoContext context, SocketAddress remoteAddr, SoConfig soConfig) throws IOException;

    /** Release all provider resources and stop accepting new channels. */
    void shutdown();
}