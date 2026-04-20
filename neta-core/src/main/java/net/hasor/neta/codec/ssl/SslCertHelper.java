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
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import net.hasor.cobble.ArrayUtils;
import net.hasor.cobble.ResourcesUtils;
import net.hasor.cobble.logging.Logger;
/**
 * Static helper for loading certificates, building KeyManagerFactory and
 * TrustManagerFactory from {@link SslCertConfig}.
 * <p>
 * These methods were extracted from {@link SslContextBasic} so that both
 * SSLEngine-based (TCP/TLS, UDP/DTLS) and QUIC custom TLS paths can share
 * the same certificate infrastructure.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2025-01-01
 */
public final class SslCertHelper {
    private static final Logger logger = Logger.getLogger(SslCertHelper.class);

    private SslCertHelper() {
    }

    /**
     * Creates or retrieves a {@link KeyStore} from the given config.
     * If the config already has a KeyStore set, returns it directly.
     * Otherwise creates a new empty KeyStore using the JVM default type.
     */
    public static KeyStore createKeyStore(SslCertConfig config) throws GeneralSecurityException {
        KeyStore ks = config.getKeyStore();
        if (ks == null) {
            String defaultType = KeyStore.getDefaultType();
            logger.debug("SslCertHelper: create KeyStore using '" + defaultType + "'");
            ks = KeyStore.getInstance(defaultType);
        }
        return ks;
    }

    /**
     * Builds a {@link KeyManagerFactory} from the given config and KeyStore.
     * <p>
     * Supports three modes:
     * <ul>
     *   <li>{@link SslAuthKeyType#JKS} — loads from JKS resource file</li>
     *   <li>{@link SslAuthKeyType#PEM} — loads from PEM certificate chain + private key files</li>
     *   <li>Direct — uses {@link SslCertConfig#getCertChainDirect()} and {@link SslCertConfig#getPrivateKeyDirect()}</li>
     * </ul>
     */
    public static KeyManagerFactory createKeyManagerFactory(SslCertConfig config, KeyStore keyStore) throws GeneralSecurityException, IOException {
        String password = config.getKeyPassword();
        char[] passwordChars = (password == null) ? ArrayUtils.EMPTY_CHAR_ARRAY : password.toCharArray();

        if (config.getAuthType() == SslAuthKeyType.JKS) {
            String jksResource = Objects.requireNonNull(config.getJksResource());
            logger.debug("SslCertHelper: loadKeyStore by JKS, " + jksResource);
            try (InputStream in = ResourcesUtils.getResourceAsStream(jksResource)) {
                SslUtils.loadKeyStore(keyStore, in, passwordChars);
            }
        } else if (config.getAuthType() == SslAuthKeyType.PEM) {
            String pemPrivate = Objects.requireNonNull(config.getPemPrivate(), "key required for servers");
            String pemCertChain = Objects.requireNonNull(config.getPemCertChain(), "keyCertChain");
            logger.debug("SslCertHelper: loadKeyStore by PEM pemPrivate = " + pemPrivate + ", pemCertChain = " + pemCertChain);

            X509Certificate[] certChain;
            PrivateKey privateKey;
            try (InputStream in = ResourcesUtils.getResourceAsStream(pemCertChain)) {
                certChain = SslUtils.toX509Certificates(in);
            }
            try (InputStream in = ResourcesUtils.getResourceAsStream(pemPrivate)) {
                privateKey = SslUtils.toPrivateKey(in, password);
            }
            SslUtils.loadKeyStore(keyStore, certChain, privateKey, passwordChars);
        } else if (config.getCertChainDirect() != null && config.getPrivateKeyDirect() != null) {
            // Direct mode — cert chain and private key provided programmatically
            logger.debug("SslCertHelper: loadKeyStore by direct cert/key");
            SslUtils.loadKeyStore(keyStore, config.getCertChainDirect(), config.getPrivateKeyDirect(), passwordChars);
        } else {
            logger.debug("SslCertHelper: loadKeyStore ignore (no auth type or direct certs).");
        }

        KeyManagerFactory kmf = config.getKeyManagerFactory();
        return SslUtils.buildKeyManagerFactory(keyStore, passwordChars, kmf);
    }

    /**
     * Builds a {@link TrustManagerFactory} from the given config and KeyStore.
     */
    public static TrustManagerFactory createTrustManagerFactory(SslCertConfig config, KeyStore keyStore) throws GeneralSecurityException, IOException {
        TrustManagerFactory tmf = config.getTrustManagerFactory();
        TrustManager[] tm = config.getTrustManagers();

        if (tmf == null) {
            if (tm != null && tm.length > 0) {
                tmf = new SslTmfWrapper(tm);
            } else {
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            }
        }
        SslUtils.buildTrustManagerFactory(keyStore, tmf);
        tmf.init(keyStore);
        return tmf;
    }

    /**
     * Convenience method to load certificate chain and private key from a {@link SslCertConfig}.
     * <p>
     * Priority: direct fields ({@code certChainDirect/privateKeyDirect}) &gt; PEM files.
     * @return a two-element array: [X509Certificate[], PrivateKey]; either element may be null
     * if the corresponding config is not set.
     */
    public static Object[] loadCertificateAndKey(SslCertConfig config) throws GeneralSecurityException, IOException {
        X509Certificate[] certChain = config.getCertChainDirect();
        PrivateKey privateKey = config.getPrivateKeyDirect();

        // Fall back to PEM files if direct values not set
        if (certChain == null && config.getPemCertChain() != null) {
            try (InputStream in = ResourcesUtils.getResourceAsStream(config.getPemCertChain())) {
                certChain = SslUtils.toX509Certificates(in);
            }
        }
        if (privateKey == null && config.getPemPrivate() != null) {
            try (InputStream in = ResourcesUtils.getResourceAsStream(config.getPemPrivate())) {
                privateKey = SslUtils.toPrivateKey(in, config.getKeyPassword());
            }
        }

        return new Object[] { certChain, privateKey };
    }
}
