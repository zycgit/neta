/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
/**
 * Controls how QUIC streams are exposed to the upper pipeline.
 * @author 赵永春 (zyc@hasor.net)
 */
public enum QuicChannelMode {
    /**
     * Expose every QUIC stream as an independent {@link QuicStreamChannel}.
     */
    STREAM,
    /**
     * Keep stream state inside the transport and deliver reassembled payload as {@link QuicMessage} on the connection pipeline.
     */
    CHANNEL
}
