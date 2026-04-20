/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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