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
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.Provider;
import java.util.Objects;
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.TrustManagerFactorySpi;

/**
 * A skeletal {@link javax.net.ssl.TrustManagerFactory} that simplifies the
 * creation of custom {@link javax.net.ssl.TrustManager} implementations.
 * <p>Adapted from {@code io.netty.handler.ssl.util.SimpleTrustManagerFactory}.
 * <p><b>Motivation:</b> the standard {@link javax.net.ssl.TrustManagerFactory} SPI
 * requires providing a concrete {@link javax.net.ssl.TrustManagerFactorySpi} at
 * construction time, but there is no way to obtain the {@code Spi} instance after
 * construction, making it impossible to wire callbacks back from the SPI to the
 * factory.  This class works around the limitation with a {@link ThreadLocal} hack:
 * the SPI is created in the {@code withInitial} supplier, stored in
 * {@code CURRENT_SPI}, and immediately retrieved by the constructor to call
 * {@link SimpleTrustManagerFactorySpi#init(SimpleTrustManagerFactory)}, which
 * registers the callback and then removes the {@code ThreadLocal} entry.
 * <p><b>Usage:</b> subclasses implement three abstract methods:
 * <ul>
 *   <li>{@link #engineInit(java.security.KeyStore)}: initialise from a KeyStore (may
 *       be a no-op if not applicable).</li>
 *   <li>{@link #engineInit(javax.net.ssl.ManagerFactoryParameters)}: initialise from
 *       provider-specific parameters (may be a no-op).</li>
 *   <li>{@link #engineGetTrustManagers()}: return the {@link javax.net.ssl.TrustManager}
 *       array to use.</li>
 * </ul>
 * <p>See {@link SslTmfWrapper} for a concrete subclass that wraps an existing
 * {@code TrustManager} array.
 * @see SslTmfWrapper
 * @see javax.net.ssl.TrustManagerFactory
 */
public abstract class SimpleTrustManagerFactory extends TrustManagerFactory {

    private static final Provider PROVIDER = new Provider("", 0.0, "") {
    };

    /**
     * {@link SimpleTrustManagerFactorySpi} must have a reference to {@link SimpleTrustManagerFactory}
     * to delegate its callbacks back to {@link SimpleTrustManagerFactory}.  However, it is impossible to do so,
     * because {@link TrustManagerFactory} requires {@link TrustManagerFactorySpi} at construction time and
     * does not provide a way to access it later.
     * To work around this issue, we use an ugly hack which uses a {@link ThreadLocal}.
     */
    private static final ThreadLocal<SimpleTrustManagerFactorySpi> CURRENT_SPI = ThreadLocal.withInitial(SimpleTrustManagerFactorySpi::new);

    /** Creates a new instance. */
    protected SimpleTrustManagerFactory() {
        this("");
    }

    /**
     * Creates a new instance.
     * @param name the name of this {@link TrustManagerFactory}
     */
    protected SimpleTrustManagerFactory(String name) {
        super(CURRENT_SPI.get(), PROVIDER, name);
        CURRENT_SPI.get().init(this);
        CURRENT_SPI.remove();
        Objects.requireNonNull(name, "name is null.");
    }

    /** Initializes this factory with a source of certificate authorities and related trust material. */
    protected abstract void engineInit(KeyStore keyStore) throws Exception;

    /** Initializes this factory with a source of provider-specific key material. */
    protected abstract void engineInit(ManagerFactoryParameters managerFactoryParameters) throws Exception;

    /** Returns one trust manager for each type of trust material. */
    protected abstract TrustManager[] engineGetTrustManagers();

    static final class SimpleTrustManagerFactorySpi extends TrustManagerFactorySpi {
        private          SimpleTrustManagerFactory parent;
        private volatile TrustManager[]            trustManagers;

        void init(SimpleTrustManagerFactory parent) {
            this.parent = parent;
        }

        @Override
        protected void engineInit(KeyStore keyStore) throws KeyStoreException {
            try {
                parent.engineInit(keyStore);
            } catch (KeyStoreException e) {
                throw e;
            } catch (Exception e) {
                throw new KeyStoreException(e);
            }
        }

        @Override
        protected void engineInit(ManagerFactoryParameters managerFactoryParameters) throws InvalidAlgorithmParameterException {
            try {
                parent.engineInit(managerFactoryParameters);
            } catch (InvalidAlgorithmParameterException e) {
                throw e;
            } catch (Exception e) {
                throw new InvalidAlgorithmParameterException(e);
            }
        }

        @Override
        protected TrustManager[] engineGetTrustManagers() {
            TrustManager[] trustManagers = this.trustManagers;
            if (trustManagers == null) {
                trustManagers = parent.engineGetTrustManagers();
                this.trustManagers = trustManagers;
            }
            return trustManagers.clone();
        }
    }
}