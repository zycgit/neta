/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.ssl;
import net.hasor.neta.channel.SoEventData;
/**
 * User-event payload published when the TLS handshake finishes successfully.
 * <p>This event is emitted by {@link SslHandle} into the channel pipeline after the handshake
 * reaches {@link SslHandshakeStatus#Finish}. It is a {@link SoEventData} payload, so upper
 * handlers observe it through their {@code onEvent(...)} callbacks rather than via the
 * {@link net.hasor.neta.channel.PlayLoad} subscription bus.
 * <p>The embedded {@link SslContext} gives handlers access to the negotiated ALPN result, current
 * client/server role, peer host information, and other per-channel TLS state.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-18
 * @see SslContext
 * @see net.hasor.neta.channel.SoEvent
 */
public class SslHandshakeEvent implements SoEventData {
    private final SslContext context;

    public SslHandshakeEvent(SslContext context) {
        this.context = context;
    }

    public SslContext getContext() {
        return this.context;
    }
}
