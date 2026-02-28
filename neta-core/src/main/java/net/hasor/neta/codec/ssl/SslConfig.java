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

/**
 * SSL configuration for SSLEngine-based TLS/DTLS pipelines.
 * <p>
 * Extends {@link SslCertConfig} to inherit shared certificate and ALPN settings,
 * and adds SSLEngine-specific fields: provider, clientAuth, ciphers, protocols.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class SslConfig extends SslCertConfig {
    private SslProvider   provider   = SslProvider.JSSE;       // default is JDK
    private SslClientAuth clientAuth = SslClientAuth.NONE;
    private String[]      ciphers    = null;                   // JSSE Cipher Suite Names
    private String[]      protocols  = null;                   // TLS protocol versions to enable

    public SslProvider getProvider() {
        return this.provider;
    }

    public void setProvider(SslProvider provider) {
        this.provider = provider;
    }

    public SslClientAuth getClientAuth() {
        return this.clientAuth;
    }

    public void setClientAuth(SslClientAuth clientAuth) {
        this.clientAuth = clientAuth;
    }

    public String[] getCiphers() {
        return this.ciphers;
    }

    public void setCiphers(String[] ciphers) {
        this.ciphers = ciphers;
    }

    public String[] getProtocols() {
        return this.protocols;
    }

    public void setProtocols(String[] protocols) {
        this.protocols = protocols;
    }
}