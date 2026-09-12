/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
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
