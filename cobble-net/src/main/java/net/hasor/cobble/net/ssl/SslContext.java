package net.hasor.cobble.net.ssl;
import net.hasor.cobble.ArrayUtils;
import net.hasor.cobble.ResourcesUtils;
import net.hasor.cobble.bytebuf.ByteBuf;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.net.SoContext;
import net.hasor.cobble.net.SoResManager;

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

public abstract class SslContext {
    private static final Logger       logger = Logger.getLogger(SslContext.class);
    protected final      long         channelID;
    protected final      SoContext    soContext;
    protected final      SoResManager rm;
    private final        boolean      clientMode;
    //
    protected final      SslConfig    sslConfig;
    private final        SSLContext   sslContext;
    private final        SSLEngine    sslEngine;
    private volatile     SslHandle    sslHandler;
    private              boolean      enable;

    public SslContext(long channelID, SoContext soContext, SslConfig config, SoResManager rm, boolean clientMode) throws Exception {
        this.channelID = channelID;
        this.soContext = soContext;
        this.rm = rm;
        this.clientMode = clientMode;

        this.sslConfig = config;
        this.sslContext = this.createSSLContext();
        this.sslEngine = this.configSslEngine(this.sslContext, this.sslContext.createSSLEngine());
        this.enable = config.isEnable();
    }

    protected SSLEngine getEngine() {
        return this.sslEngine;
    }

    protected boolean isServer() {
        return !this.clientMode;
    }

    protected boolean isClient() {
        return this.clientMode;
    }

    protected boolean isEnable() {
        return this.enable;
    }

    /**
     * Returns the name of the negotiated application-level protocol.
     * @return the application-level protocol name or {@code null} if the negotiation failed or the client does not have ALPN/NPN extension
     */
    public abstract String getApplicationProtocol();

    /** 创建 KeyStore */
    protected KeyStore createKeyStore() throws GeneralSecurityException, IOException {
        KeyStore ks = this.sslConfig.getKeyStore();
        if (ks == null) {
            String defaultType = KeyStore.getDefaultType();
            logger.info("ssl (" + this.channelID + ") create KeyStore using '" + defaultType + "'");
            ks = KeyStore.getInstance(defaultType);
        }
        return ks;
    }

    /** 创建 KeyManagerFactory */
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

    /** 创建 TrustManagerFactory */
    protected TrustManagerFactory getTrustManagers(KeyStore keyStore) throws GeneralSecurityException, IOException {
        TrustManagerFactory tmf = this.sslConfig.getTrustManagerFactory();
        if (tmf == null) {
            tmf = TrustManagerFactory.getInstance("SunX509");
        }
        SslUtils.buildTrustManagerFactory(keyStore, tmf);
        tmf.init(keyStore);
        return tmf;
    }

    /** 创建 SSLContext */
    protected abstract SSLContext createSSLContext() throws GeneralSecurityException, IOException;

    /** 创建 SSLEngine */
    protected abstract SSLEngine configSslEngine(SSLContext sslContext, SSLEngine engine) throws GeneralSecurityException;

    private synchronized boolean tryHandshake(ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        if (this.sslHandler != null && this.sslHandler.isHandshake()) {
            return true; // 已经握手成功，处理后续 SSL 数据解密/加密
        }

        // 启动握手
        if (this.sslHandler == null) {
            this.sslHandler = new SslHandle(this.channelID, this.soContext, this.sslEngine, this.rm);
            this.sslHandler.beginHandshake();
        }

        // 处理握手请求
        this.sslHandler.handshake(rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);

        // 握手成功
        if (this.sslHandler.isHandshake()) {
            logger.info("sslHandshake(" + this.channelID + ") finish.");
            return true; // 刚刚握手成功，处理后续 SSL 数据解密/加密
        } else {
            return false;
        }
    }

    /** 接收SSL数据 */
    public void handRcv(ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        if (this.enable) {
            if (this.tryHandshake(rcvUpstream, rcvDownstream, sndUpstream, sndDownstream)) {
                this.sslHandler.handlerRcv(rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
            }
        } else {
            // not use ssl
            rcvUpstream.read(rcvDownstream);
            rcvUpstream.markReader();
            rcvDownstream.markWriter();
        }
    }

    /** 发送SSL数据 */
    public void handSnd(ByteBuf rcvUpstream, ByteBuf rcvDownstream, ByteBuf sndUpstream, ByteBuf sndDownstream) throws IOException {
        if (this.enable) {
            if (this.tryHandshake(rcvUpstream, rcvDownstream, sndUpstream, sndDownstream)) {
                this.sslHandler.handlerSnd(rcvUpstream, rcvDownstream, sndUpstream, sndDownstream);
            }
        } else {
            // not use ssl
            sndUpstream.read(sndDownstream);
            sndUpstream.markReader();
            sndDownstream.markWriter();
        }
    }
}