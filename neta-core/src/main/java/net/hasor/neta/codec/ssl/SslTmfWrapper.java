/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package net.hasor.neta.codec.ssl;
import java.security.KeyStore;
import java.util.Objects;
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;
/**
 * A {@link SimpleTrustManagerFactory} that wraps an existing
 * {@link javax.net.ssl.TrustManager} (or array of them) into a
 * {@link javax.net.ssl.TrustManagerFactory} that can be passed to
 * {@link javax.net.ssl.SSLContext#init}.
 * <p>Typical usage is to wrap a custom or test {@code TrustManager}:
 * <pre>
 *   TrustManager tm = new X509TrustManager() {
 *       public void checkClientTrusted(X509Certificate[] c, String a) {}
 *       public void checkServerTrusted(X509Certificate[] c, String a) {}
 *       public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
 *   };
 *   SslConfig config = new SslConfig();
 *   config.setTrustManagerFactory(new SslTmfWrapper(tm));  // trust everything
 * </pre>
 * <p>The {@link #engineGetTrustManagers()} method returns a defensive copy of
 * the wrapped array so callers cannot mutate the internal state.
 * @see SimpleTrustManagerFactory
 * @see javax.net.ssl.TrustManager
 */
public final class SslTmfWrapper extends SimpleTrustManagerFactory {
    private final TrustManager[] tmArray;

    public SslTmfWrapper(TrustManager tm) {
        this.tmArray = new TrustManager[] { Objects.requireNonNull(tm, "tm") };
    }

    public SslTmfWrapper(TrustManager[] tmArray) {
        this.tmArray = Objects.requireNonNull(tmArray, "tm");
    }

    @Override
    protected void engineInit(KeyStore keyStore) {
    }

    @Override
    protected void engineInit(ManagerFactoryParameters managerFactoryParameters) {
    }

    @Override
    protected TrustManager[] engineGetTrustManagers() {
        return tmArray.clone();
    }
}