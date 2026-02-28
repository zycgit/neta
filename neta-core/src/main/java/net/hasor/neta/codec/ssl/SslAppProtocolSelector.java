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