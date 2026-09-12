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
import java.net.SocketAddress;
import net.hasor.cobble.concurrent.future.Future;
/**
 * Transport-layer abstraction behind a client-side {@link NetChannel}.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public interface AsyncChannel extends Closeable {
    /**
     * Return the unique identifier of this channel.
     * @return channel ID as a long value
     */
    long getChannelId();

    /** Return the socket configuration. */
    SoConfig getSoConfig();

    /**
     * Return the local address bound to this channel.
     * @return local SocketAddress
     */
    SocketAddress getLocalAddress();

    /**
     * Return the remote address connected by this channel.
     * @return remote SocketAddress
     */
    SocketAddress getRemoteAddress();

    /**
     * Return whether this channel is currently open.
     * @return {@code true} if the channel is open, otherwise {@code false}
     */
    boolean isOpen();

    /**
     * Close this channel.
     * @throws IOException thrown when an I/O error occurs
     */
    @Override
    void close() throws IOException;

    /** Write the pending outbound data for the given NetChannel. */
    void write(NetChannel channel, SoSndContext wContext);

    /**
     * Start the connection flow using the given initializer.
     */
    void connectTo(ProtoInitializer initializer, Future<NetChannel> future) throws Throwable;
}
