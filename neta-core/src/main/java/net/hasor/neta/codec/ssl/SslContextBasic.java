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
import javax.net.ssl.*;
import net.hasor.cobble.ArrayUtils;
import net.hasor.cobble.ResourcesUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;

/**
 * An implementation of the {@link SslContext} interface that provides SSL handshake support
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 */
public abstract class SslContextBasic implements SslContext {
    protected static final Logger        logger = Logger.getLogger(SslContextBasic.class);
    protected final        SoChannel<?>  channel;
    protected final        long          channelId;
    protected final        String        stackName;
    protected final        ProtoContext  protoCtx;
    protected final        SoContext     soContext;
    protected final        boolean       sslLog;
    protected final        boolean       netLog;
    //
    protected final        SslConfig     sslConfig;
    private final          boolean       clientMode;
    private final          SSLContext    sslContext;
    private final          SslEngineWrap sslEngine;
    private final          SslHandle     sslHandler;
    protected volatile     boolean       sslEnable;

    public SslContextBasic(SoChannel<?> channel, String stackName, SslConfig config, ProtoContext protoCtx, boolean clientMode) throws Exception {
        this.channel = channel;
        this.channelId = channel.getChannelId();
        this.stackName = stackName;
        this.protoCtx = protoCtx;
        this.soContext = protoCtx.getSoContext();
        this.clientMode = clientMode;
        this.sslLog = protoCtx.getSoContext().getConfig().isPrintLog();
        this.netLog = this.soContext.getConfig().isPrintLog();

        this.sslConfig = config;
        this.sslEnable = true;
        this.sslContext = this.createSSLContext(this.sslConfig.getProtocols());
        this.sslEngine = new SslEngineWrap(this.channelId, config, () -> this.configSslEngine(this.sslContext, this.sslContext.createSSLEngine()));
        this.sslHandler = new SslHandle(this.channelId, protoCtx, this, this.sslEngine, () -> {
            this.sslEnable = false;
        });
    }

    protected SslEngineWrap getEngine() {
        return this.sslEngine;
    }

    @Override
    public SoChannel<?> getChannel() {
        return this.channel;
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
    public boolean isReady() {
        return this.sslHandler.getHandshake() == SslHandshakeStatus.Finish;
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
    protected KeyStore createKeyStore() throws GeneralSecurityException {
        KeyStore ks = this.sslConfig.getKeyStore();
        if (ks == null) {
            String defaultType = KeyStore.getDefaultType();
            if (this.sslLog) {
                logger.info("ssl(" + this.channelId + ") create KeyStore using '" + defaultType + "'");
            }
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
            if (this.sslLog) {
                logger.info("ssl(" + this.channelId + ") loadKeyStore by JKS, " + jskResource);
            }

            try (InputStream in = ResourcesUtils.getResourceAsStream(jskResource)) {
                SslUtils.loadKeyStore(keyStore, in, passwordChars);
            }
        } else if (this.sslConfig.getAuthType() == SslAuthKeyType.PEM) {
            String pemPrivate = Objects.requireNonNull(this.sslConfig.getPemPrivate(), "key required for servers");
            String pemCertChain = Objects.requireNonNull(this.sslConfig.getPemCertChain(), "keyCertChain");
            if (this.sslLog) {
                logger.info("ssl(" + this.channelId + ") loadKeyStore by PEM pemPrivate = " + pemPrivate + ", pemCertChain = " + pemCertChain);
            }

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
            if (this.sslLog) {
                logger.info("ssl(" + this.channelId + ") loadKeyStore ignore.");
            }
        }

        KeyManagerFactory kmf = this.sslConfig.getKeyManagerFactory();
        return SslUtils.buildKeyManagerFactory(keyStore, passwordChars, kmf);
    }

    /** create TrustManagerFactory */
    protected TrustManagerFactory getTrustManagers(KeyStore keyStore) throws GeneralSecurityException, IOException {
        TrustManagerFactory tmf = this.sslConfig.getTrustManagerFactory();
        TrustManager[] tm = this.sslConfig.getTrustManagers();

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

    /** create SSLContext */
    protected abstract SSLContext createSSLContext(String[] protocol) throws GeneralSecurityException, IOException;

    /** create SSLEngine */
    protected abstract SSLEngine configSslEngine(SSLContext sslContext, SSLEngine engine) throws IOException;

    /** Receiving SSL data */
    public ProtoStatus handRcv(ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown, ProtoRcvQueue<ByteBuf> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws IOException {
        if (this.sslEnable) {
            if (!sndDown.hasSlot()) {
                if (this.netLog) {
                    logger.info("sslRcv(" + this.channelId + ") rcvDown or sndDown Buffer is full.");
                }
                return ProtoStatus.Next;
            }

            if (this.sslHandler.tryHandshake(true, rcvUp, rcvDown, sndUp, sndDown)) {
                this.sslHandler.handlerRcv(rcvUp, rcvDown, sndUp, sndDown);
            }
            return ProtoStatus.Next;
        } else {
            rcvDown.offerMessage(rcvUp.takeMessage(Math.min(rcvUp.queueSize(), rcvDown.slotSize())));
            sndDown.offerMessage(sndUp.takeMessage(Math.min(sndUp.queueSize(), sndDown.slotSize())));
            return ProtoStatus.Next;
        }
    }

    /** Sending SSL data */
    public ProtoStatus handSnd(ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown, ProtoRcvQueue<ByteBuf> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws IOException {
        if (this.sslEnable) {
            if (!sndDown.hasSlot()) {
                if (this.netLog) {
                    logger.info("sslSnd(" + this.channelId + ") rcvDown or sndDown Buffer is full.");
                }
                return ProtoStatus.Next;
            }

            if (this.sslHandler.tryHandshake(false, rcvUp, rcvDown, sndUp, sndDown)) {
                this.sslHandler.handlerSnd(rcvUp, rcvDown, sndUp, sndDown);
            }
            return ProtoStatus.Next;
        } else {
            rcvDown.offerMessage(rcvUp.takeMessage(Math.min(rcvUp.queueSize(), rcvDown.slotSize())));
            sndDown.offerMessage(sndUp.takeMessage(Math.min(sndUp.queueSize(), sndDown.slotSize())));
            return ProtoStatus.Next;
        }
    }

    @Override
    public void closeSSL() {
        if (!this.sslEnable) {
            return;
        }

        SslEngineWrap engine = this.getEngine();
        if (engine != null && !engine.isOutboundDone()) {
            engine.closeOutbound();
            this.protoCtx.flush();
        }

        this.sslEnable = false;
    }

    @Override
    public void openSSL() {
        this.sslEnable = true;
    }
}