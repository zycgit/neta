/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.ssl;
import java.util.List;
import net.hasor.neta.channel.SoChannel;
/**
 * Select a protocol that is supported in the TLS NPN/ALPN extension.
 * <p>
 * This selector is transport-agnostic: it works for both SSLEngine-based
 * TLS/DTLS and QUIC custom TLS handshakes.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
@FunctionalInterface
public interface SslAppProtocolSelector {
    String selector(SoChannel<?> channel, List<String> protocols);
}
