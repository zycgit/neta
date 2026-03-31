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
 * Transport-layer abstraction behind a server-side channel, typically exposed as a {@link NetListen} listener.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2025-08-06
 */
public interface AsyncServerChannel extends Closeable {
    /**
     * Return the unique identifier of this channel.
     * @return channel ID as a long value
     */
    long getChannelId();

    /** Return the socket configuration. */
    SoConfig getSoConfig();

    /**
     * Return whether the channel is currently open.
     * @return {@code true} if the channel is open, otherwise {@code false}
     */
    boolean isOpen();

    /**
     * Bind the server channel to the target network address and start listening for connections.
     * @throws IOException thrown when an I/O error occurs during bind
     */
    NetListen bind(ProtoInitializer initializer) throws IOException;
}
