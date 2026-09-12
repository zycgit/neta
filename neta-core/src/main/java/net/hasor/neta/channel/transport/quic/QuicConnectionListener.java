/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
/**
 * Callback interface invoked after a newly accepted server-side QUIC connection has finished initialization.
 * <p>This listener is triggered by {@link QuicAsyncServerChannel} after the handshake completes, the
 * {@link QuicChannel} is created, and its processing pipeline is initialized. It is only used for new
 * connections accepted by the server and does not participate in client-mode connections.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface QuicConnectionListener {
    /**
     * Invoked when a new QUIC connection has been fully established.
     * @param quicChannel the newly created connection-level channel
     */
    void onConnectionEstablished(QuicChannel quicChannel);
}
