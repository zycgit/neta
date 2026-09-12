/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server.connector;

import java.net.InetSocketAddress;
import net.hasor.neta.channel.NetManager;
import net.hasor.nhttp.server.ServerConfig;

/**
 * A connector binds to one or more network ports and accepts incoming connections,
 * translating them to protocol objects handed off via {@link RequestDispatchCallback}.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface Connector {

    /**
     * Returns a short protocol identifier, such as {@code "http"}, {@code "https"},
     * or {@code "http/3"}.
     */
    String getProtocol();

    /**
     * Starts the connector, binding to the specified address and beginning to accept connections.
     *
     * @param netManager neta transport layer
     * @param address    local address to bind
     * @param config     server configuration
     * @param callback   callback for handing off decoded requests to the container
     * @throws Exception if the connector cannot be started
     */
    void start(NetManager netManager, InetSocketAddress address, //
            ServerConfig config, RequestDispatchCallback callback) throws Exception;

    /** Stops the connector, closing the listening socket. In-flight requests are not interrupted. */
    void stop();

    /**
     * Returns the local address this connector is currently bound to,
     * or {@code null} if not yet started.
     */
    InetSocketAddress getLocalAddress();
}
