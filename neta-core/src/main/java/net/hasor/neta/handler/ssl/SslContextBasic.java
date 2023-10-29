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
import net.hasor.cobble.ResourcesUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.SoContext;
import net.hasor.neta.handler.PipeRcvQueue;
import net.hasor.neta.handler.PipeSndQueue;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Objects;

/**
 * An implementation of the {@link SslContext} interface that provides SSL handshake support
 * @version : 2023-10-20
 * @author 赵永春 (zyc@hasor.net)
 */
public abstract class SslContextBasic implements SslContext {
    private static final Logger     logger = Logger.getLogger(SslContextBasic.class);
    protected final      long       channelID;
    protected final      SoContext  soContext;
    private final        boolean    clientMode;
    //
    protected final      SslConfig  sslConfig;
    private final        SSLContext sslContext;
    private final        SSLEngine  sslEngine;
    private volatile     SslHandle  sslHandler;

    public SslContextBasic(long channelID, SslConfig config, SoContext soContext, boolean clientMode) throws Exception {
        this.channelID = channelID;
        this.soContext = soContext;
        this.clientMode = clientMode;

        this.sslConfig = config;
        this.sslContext = this.createSSLContext();
        this.sslEngine = this.configSslEngine(this.sslContext, this.sslContext.createSSLEngine());
    }

    protected SSLEngine getEngine() {
        return this.sslEngine;
    }

    @Override
    public boolean isServer() {
        return !this.clientMode;
    }

    @Override
    public boolean isClient() {
        return this.clientMode;
    }

    @Override
    public String getPeerHost() {
        return this.sslEngine.getPeerHost();
    }

    @Override
    public int getPeerPort() {
        return this.sslEngine.getPeerPort();
    }

    @Override
    public SslConfig getConfig() {
        return this.sslConfig;
    }

    /** create KeyStore */
    protected KeyStore createKeyStore() throws GeneralSecurityException, IOException {
        KeyStore ks = this.sslConfig.getKeyStore();
        if (ks == null) {
            String defaultType = KeyStore.getDefaultType();
            logger.info("ssl (" + this.channelID + ") create KeyStore using '" + defaultType + "'");
            ks = KeyStore.getInstance(defaultType);
        }
        return ks;
    }

    /** create KeyManagerFactory */
    protected KeyManagerFactory createKeyManagerFactory(KeyStore keyStore) throws GeneralSecurityException, IOException {
        String password = this.sslConfig.getKeyPassword();
        char[] passwordChars = (password == null) ? ArrayUtils.EMPTY_CHAR_ARRAY : password.toCharArray();

        if (this.sslConfig.getAuthType() == SslAuthKeyType.JKS) {
            String jskResource = Objects.requireNonNull(this.sslConfig.getJksResource());
            logger.info("ssl (" + this.channelID + ") loadKeyStore by JKS, " + jskResource);

            try (InputStream in = ResourcesUtils.getResourceAsStream(jskResource)) {
                SslUtils.loadKeyStore(keyStore, in, passwordChars);
            }
        } else if (this.sslConfig.getAuthType() == SslAuthKeyType.PEM) {
            String pemPrivate = Objects.requireNonNull(this.sslConfig.getPemPrivate(), "key required for servers");
            String pemCertChain = Objects.requireNonNull(this.sslConfig.getPemCertChain(), "keyCertChain");
            logger.info("ssl (" + this.channelID + ") loadKeyStore by PEM pemPrivate = " + pemPrivate + ", pemCertChain = " + pemCertChain);

            X509Certificate[] certChain;
            PrivateKey privateKey;
            try (InputStream in = ResourcesUtils.getResourceAsStream(pemCertChain)) {
                certChain = SslUtils.toX509Certificates(in);
            }
            try (InputStream in = ResourcesUtils.getResourceAsStream(pemPrivate)) {
                privateKey = SslUtils.toPrivateKey(in, password);
            }

            SslUtils.loadKeyStore(keyStore, certChain, privateKey, passwordChars);
        } else {
            logger.info("ssl (" + this.channelID + ") loadKeyStore ignore.");
        }

        KeyManagerFactory kmf = this.sslConfig.getKeyManagerFactory();
        return SslUtils.buildKeyManagerFactory(keyStore, passwordChars, kmf);
    }

    /** create TrustManagerFactory */
    protected TrustManagerFactory getTrustManagers(KeyStore keyStore) throws GeneralSecurityException, IOException {
        TrustManagerFactory tmf = this.sslConfig.getTrustManagerFactory();
        if (tmf == null) {
            tmf = TrustManagerFactory.getInstance("SunX509");
        }
        SslUtils.buildTrustManagerFactory(keyStore, tmf);
        tmf.init(keyStore);
        return tmf;
    }

    /** create SSLContext */
    protected abstract SSLContext createSSLContext() throws GeneralSecurityException, IOException;

    /** create SSLEngine */
    protected abstract SSLEngine configSslEngine(SSLContext sslContext, SSLEngine engine) throws GeneralSecurityException;

    private synchronized boolean tryHandshake(PipeRcvQueue<ByteBuf> rcvUp, PipeSndQueue<ByteBuf> rcvDown, PipeRcvQueue<ByteBuf> sndUp, PipeSndQueue<ByteBuf> sndDown) throws IOException {
        if (this.sslHandler != null && this.sslHandler.isHandshake()) {
            return true; // The handshake has been successful, and the SSL data decryption/encryption is processed
        }

        // start handshake
        if (this.sslHandler == null) {
            this.sslHandler = new SslHandle(this.channelID, this.sslConfig, this.soContext, this.sslEngine);
            this.sslHandler.beginHandshake();
        }

        // handshake requests
        this.sslHandler.handshake(rcvUp, rcvDown, sndUp, sndDown);

        // Handshake successful
        if (this.sslHandler.isHandshake()) {
            logger.info("sslHandshake(" + this.channelID + ") finish.");
            return true; // We've just completed the handshake, and we'll handle the SSL decryption/encryption
        } else {
            return false;
        }
    }

    /** Receiving SSL data */
    public void handRcv(PipeRcvQueue<ByteBuf> rcvUp, PipeSndQueue<ByteBuf> rcvDown, PipeRcvQueue<ByteBuf> sndUp, PipeSndQueue<ByteBuf> sndDown) throws IOException {
        if (!rcvDown.hasSlot() || !sndDown.hasSlot()) {
            logger.info("sslRcv(" + this.channelID + ") rcvDown or sndDown Buffer is full.");
            return;
        }

        if (this.tryHandshake(rcvUp, rcvDown, sndUp, sndDown)) {
            this.sslHandler.handlerRcv(rcvUp, rcvDown, sndUp, sndDown);
        }
    }

    /** Sending SSL data */
    public void handSnd(PipeRcvQueue<ByteBuf> rcvUp, PipeSndQueue<ByteBuf> rcvDown, PipeRcvQueue<ByteBuf> sndUp, PipeSndQueue<ByteBuf> sndDown) throws IOException {
        if (!rcvDown.hasSlot() || !sndDown.hasSlot()) {
            logger.info("sslRcv(" + this.channelID + ") rcvDown or sndDown Buffer is full.");
            return;
        }

        if (this.tryHandshake(rcvUp, rcvDown, sndUp, sndDown)) {
            this.sslHandler.handlerSnd(rcvUp, rcvDown, sndUp, sndDown);
        }
    }
}