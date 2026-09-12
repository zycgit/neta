/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
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
