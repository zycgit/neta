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
 * Network event fired on the <em>RCV</em> pipeline when a TLS {@code close_notify}
 * alert is received from the remote peer — the "goodbye handshake", symmetric
 * counterpart of {@link SslHandshakeEvent}.
 * <p>
 * After this event fires the SSL layer has already transitioned to
 * {@link SslHandshakeStatus#Closed}: {@link SslContext#isReady()} returns
 * {@code false}.
 * The TCP connection is <em>not</em> automatically torn down; higher-level
 * handlers decide whether to close the channel or continue communicating
 * (without encryption, in passthrough mode).
 * </p>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-06
 */
public class SslCloseNotifyEvent implements SoEventData {
    private final SslContext context;

    public SslCloseNotifyEvent(SslContext context) {
        this.context = context;
    }

    /** The {@link SslContext} whose session just received the {@code close_notify}. */
    public SslContext getContext() {
        return this.context;
    }
}
