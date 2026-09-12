/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.client;

import java.io.IOException;
import java.net.ServerSocket;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

import net.hasor.neta.codec.ssl.SslAuthKeyType;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.neta.codec.ssl.SslProtocol;
import net.hasor.neta.codec.ssl.SslTmfWrapper;

/**
 * Shared helpers for nhttp client integration tests.
 */
public abstract class ClientTestSupport {
    protected static int findFreePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

    protected static void waitForServer() throws InterruptedException {
        Thread.sleep(300L);
    }

    protected static SslConfig serverSslConfig() {
        SslConfig sslConfig = new SslConfig();
        sslConfig.setAuthType(SslAuthKeyType.PEM);
        sslConfig.setPemCertChain("ssl/ca/server.crt");
        sslConfig.setPemPrivate("ssl/ca/server.pem");
        sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_2 });
        return sslConfig;
    }

    protected static SslConfig trustAllClientSslConfig() {
        X509TrustManager trustManager = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        SslConfig sslConfig = new SslConfig();
        sslConfig.setTrustManagerFactory(new SslTmfWrapper(trustManager));
        sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_2 });
        return sslConfig;
    }
}
