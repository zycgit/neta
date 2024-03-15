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
package net.hasor.neta.handler.ssl;
import net.hasor.neta.handler.ProtoDuplex;

/**
 * A status for {@link ProtoDuplex}
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-18
 */
public interface SslContext {

    /** return ssl config. */
    SslConfig getConfig();

    /** SSL server side */
    boolean isServer();

    /** SSL Client side */
    boolean isClient();

    /** return SSL status Active */
    boolean isActive();

    /**
     * Returns the name of the negotiated application-level protocol.
     * @return the application-level protocol name or {@code null} if the negotiation failed or the client does not have ALPN/NPN extension
     */
    String getApplicationProtocol();

    /** Returns the host name of the peer of this session. The host name is not authenticated. */
    String getPeerHost();

    /** Returns the host name of the peer of this session. The host port is not authenticated. */
    int getPeerPort();

    /** switch to no encryption, there is keep connect, close SSL. */
    void closeSSL();

    /** switch to encryption, there is keep connect, close SSL. */
    void openSSL();

    //    /** switch to no encryption, there is keep connect, close SSL. */
    //    void renegotiate();
}