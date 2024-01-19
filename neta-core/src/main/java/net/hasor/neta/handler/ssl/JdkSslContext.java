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
import net.hasor.cobble.ArrayUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.PipeContext;
import net.hasor.neta.channel.SoChannel;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.*;

/**
 * An {@link SslContext} which uses JDK's SSL/TLS implementation.
 * refer to `io.netty.handler.ssl.JdkSslContext` implementation
 * @version : 2023-10-18
 * @author Netty
 * @author 赵永春 (zyc@hasor.net)
 */
public class JdkSslContext extends SslContextBasic {
    private static final   Logger      logger   = Logger.getLogger(JdkSslContext.class);
    protected static final String      PROTOCOL = "TLS";
    private static final   String[]    DEFAULT_PROTOCOLS;
    private static final   Set<String> SUPPORTED_CIPHERS;
    private static final   String[]    DEFAULT_CIPHERS;
    private static final   String[]    DEFAULT_CIPHERS_NON_TLSV13;
    private static final   Set<String> SUPPORTED_CIPHERS_NON_TLSV13;

    static {
        SSLContext context;
        try {
            context = SSLContext.getInstance(PROTOCOL);
            context.init(null, null, null);
        } catch (Exception e) {
            throw new Error("failed to initialize the default SSL context", e);
        }

        SSLEngine engForTest = context.createSSLEngine();
        DEFAULT_PROTOCOLS = defaultProtocols(context, engForTest);
        SUPPORTED_CIPHERS = Collections.unmodifiableSet(supportedCiphers(engForTest));
        DEFAULT_CIPHERS = defaultCiphers(engForTest, SUPPORTED_CIPHERS);

        Set<String> ciphersNonTLSv13 = new LinkedHashSet<>(Arrays.asList(DEFAULT_CIPHERS));
        ciphersNonTLSv13.removeAll(Arrays.asList(SslUtils.DEFAULT_TLSV13_CIPHER_SUITES));
        DEFAULT_CIPHERS_NON_TLSV13 = ciphersNonTLSv13.toArray(ArrayUtils.EMPTY_STRING_ARRAY);

        Set<String> suppertedCiphersNonTLSv13 = new LinkedHashSet<>(SUPPORTED_CIPHERS);
        suppertedCiphersNonTLSv13.removeAll(Arrays.asList(SslUtils.DEFAULT_TLSV13_CIPHER_SUITES));
        SUPPORTED_CIPHERS_NON_TLSV13 = Collections.unmodifiableSet(suppertedCiphersNonTLSv13);

        if (logger.isDebugEnabled()) {
            logger.debug("Default protocols (JDK): " + StringUtils.join(DEFAULT_PROTOCOLS, ","));
            logger.debug("Default cipher suites (JDK): " + StringUtils.join(DEFAULT_CIPHERS, ","));
        }
    }

    public JdkSslContext(long channelID, PipeContext context, SslConfig config, boolean clientMode) throws Exception {
        super(channelID, config, context, clientMode);
    }

    @Override
    public String getApplicationProtocol() {
        SSLEngine unwrap = this.getEngine().unwrap();
        if (unwrap != null && JdkAlpnSslUtils.supportsAlpn()) {
            String appProtocol = JdkAlpnSslUtils.getApplicationProtocol(unwrap);
            if (appProtocol != null) {
                return appProtocol.isEmpty() ? null : appProtocol;
            }
        }
        return null;
    }

    @Override
    protected SSLContext createSSLContext() throws GeneralSecurityException, IOException {
        KeyStore ks = this.createKeyStore();
        KeyManagerFactory kmf = this.createKeyManagerFactory(ks);
        TrustManagerFactory tmf = this.getTrustManagers(ks);

        if (this.sslLog) {
            logger.info("ssl(" + this.channelID + ") create JdkSslContext.");
        }
        SSLContext context = SSLContext.getInstance(PROTOCOL);
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return context;
    }

    @Override
    protected SSLEngine configSslEngine(SSLContext sslContext, SSLEngine sslEngine) {
        if (this.sslLog) {
            logger.info("ssl(" + this.channelID + ") create SSLEngine on " + (isClient() ? "client" : "server"));
        }
        //        SSLParameters sslParameters = SSLContext.getDefault().getSupportedSSLParameters();
        //        sslParameters.setAlgorithmConstraints();
        sslEngine.setUseClientMode(this.isClient());

        // SSL Protocols
        String[] protocols = this.sslConfig.getProtocols();
        protocols = (protocols == null) ? DEFAULT_PROTOCOLS : protocols;
        sslEngine.setEnabledProtocols(protocols);

        // Ciphers
        String[] ciphers = this.sslConfig.getCiphers();
        if (isTlsV13Supported(protocols)) {
            ciphers = (ciphers == null) ? DEFAULT_CIPHERS : ciphers;
            ciphers = filterCipherSuites(Arrays.asList(ciphers), DEFAULT_CIPHERS, SUPPORTED_CIPHERS);
        } else {
            ciphers = (ciphers == null) ? DEFAULT_CIPHERS_NON_TLSV13 : ciphers;
            ciphers = filterCipherSuites(Arrays.asList(ciphers), DEFAULT_CIPHERS_NON_TLSV13, SUPPORTED_CIPHERS_NON_TLSV13);
        }
        if (this.sslLog) {
            logger.info("ssl(" + this.channelID + ") enabled CipherSuites [" + StringUtils.join(ciphers, ", ") + "]");
        }
        sslEngine.setEnabledCipherSuites(ciphers);

        // ClientAuth
        SslClientAuth clientAuth = this.sslConfig.getClientAuth();
        if (this.isServer() && clientAuth != null) {
            if (this.sslLog) {
                logger.info("ssl(" + this.channelID + ") clientAuth = " + clientAuth);
            }
            switch (clientAuth) {
                case OPTIONAL:
                    sslEngine.setWantClientAuth(true);
                    break;
                case REQUIRE:
                    sslEngine.setNeedClientAuth(true);
                case NONE:
                    break;
                default:
                    throw new Error("Unknown auth " + clientAuth);
            }
        }

        // NPN/ALPN
        String[] appProtocol = this.sslConfig.getAppProtocol();
        appProtocol = (appProtocol == null) ? ArrayUtils.EMPTY_STRING_ARRAY : appProtocol;
        JdkAlpnSslUtils.setApplicationProtocols(sslEngine, appProtocol);
        final SslAppProtocolSelector protocolSelector = this.sslConfig.getAppProtocolSelector();
        if (protocolSelector != null) {
            JdkAlpnSslUtils.setHandshakeApplicationProtocolSelector(sslEngine, (engine, strings) -> {
                SoChannel<?> channel = this.soContext.findChannel(this.channelID);
                return protocolSelector.selector(channel, engine, strings);
            });
        }

        return sslEngine;
    }

    private static String[] defaultProtocols(SSLContext context, SSLEngine engine) {
        // Choose the sensible default list of protocols that respects JDK flags, eg. jdk.tls.client.protocols
        String[] supportedProtocols = context.getDefaultSSLParameters().getProtocols();
        Set<String> supportedProtocolsSet = new LinkedHashSet<>(supportedProtocols.length);
        Collections.addAll(supportedProtocolsSet, supportedProtocols);
        Set<String> protocols = new LinkedHashSet<>();
        addIfSupported(supportedProtocolsSet, protocols, //
                SslProtocol.TLS_v1_3, SslProtocol.TLS_v1_2, SslProtocol.TLS_v1_1, SslProtocol.TLS_v1);

        if (!protocols.isEmpty()) {
            return protocols.toArray(ArrayUtils.EMPTY_STRING_ARRAY);
        }
        return engine.getEnabledProtocols();
    }

    private static Set<String> supportedCiphers(SSLEngine engine) {
        // Choose the sensible default list of cipher suites.
        final String[] supportedCiphers = engine.getSupportedCipherSuites();
        Set<String> supportedCiphersSet = new LinkedHashSet<>(supportedCiphers.length);
        for (String supportedCipher : supportedCiphers) {
            supportedCiphersSet.add(supportedCipher);
            // IBM's J9 JVM utilizes a custom naming scheme for ciphers and only returns ciphers with the "SSL_"
            // prefix instead of the "TLS_" prefix (as defined in the JSSE cipher suite names [1]). According to IBM's
            // documentation [2] the "SSL_" prefix is "interchangeable" with the "TLS_" prefix.
            // See the IBM forum discussion [3] and issue on IBM's JVM [4] for more details.
            //[1] https://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html#ciphersuites
            //[2] https://www.ibm.com/support/knowledgecenter/en/SSYKE2_8.0.0/com.ibm.java.security.component.80.doc/
            // security-component/jsse2Docs/ciphersuites.html
            //[3] https://www.ibm.com/developerworks/community/forums/html/topic?id=9b5a56a9-fa46-4031-b33b-df91e28d77c2
            //[4] https://www.ibm.com/developerworks/rfe/execute?use_case=viewRfe&CR_ID=71770
            if (supportedCipher.startsWith("SSL_")) {
                final String tlsPrefixedCipherName = "TLS_" + supportedCipher.substring("SSL_".length());
                try {
                    engine.setEnabledCipherSuites(new String[] { tlsPrefixedCipherName });
                    supportedCiphersSet.add(tlsPrefixedCipherName);
                } catch (IllegalArgumentException ignored) {
                    // The cipher is not supported ... move on to the next cipher.
                }
            }
        }
        return supportedCiphersSet;
    }

    private static String[] defaultCiphers(SSLEngine engine, Set<String> supportedCiphers) {
        Set<String> ciphers = new LinkedHashSet<>();
        addIfSupported(supportedCiphers, ciphers, SslUtils.DEFAULT_CIPHER_SUITES);

        if (ciphers.isEmpty()) {
            for (String cipher : engine.getEnabledCipherSuites()) {
                if (cipher.startsWith("SSL_") || cipher.contains("_RC4_")) {
                    continue;
                }
                ciphers.add(cipher);
            }
        }

        return ciphers.toArray(ArrayUtils.EMPTY_STRING_ARRAY);
    }

    private static void addIfSupported(Set<String> supported, Set<String> enabled, String... names) {
        for (String n : names) {
            if (supported.contains(n)) {
                enabled.add(n);
            }
        }
    }

    private static boolean isTlsV13Supported(String[] protocols) {
        for (String protocol : protocols) {
            if (SslProtocol.TLS_v1_3.equals(protocol)) {
                return true;
            }
        }
        return false;
    }

    private static String[] filterCipherSuites(Iterable<String> ciphers, String[] defaultCiphers, Set<String> supportedCiphers) {
        Objects.requireNonNull(defaultCiphers, "defaultCiphers");
        Objects.requireNonNull(supportedCiphers, "supportedCiphers");

        final List<String> newCiphers;
        if (ciphers == null) {
            newCiphers = new ArrayList<>(defaultCiphers.length);
            ciphers = Arrays.asList(defaultCiphers);
        } else {
            newCiphers = new ArrayList<>(supportedCiphers.size());
        }
        for (String c : ciphers) {
            if (c == null) {
                break;
            }
            if (supportedCiphers.contains(c)) {
                newCiphers.add(c);
            }
        }
        return newCiphers.toArray(new String[0]);
    }
}