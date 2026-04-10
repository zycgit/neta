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
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.List;
import javax.net.ssl.*;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.channel.SoContext;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/**
 * Base implementation shared by concrete TLS context variants.
 * <p>This class combines the channel-facing {@link SslContext} API with three operational pieces:
 * the provider-specific {@link javax.net.ssl.SSLContext}, the lazy {@link SslEngineWrap}, and the
 * per-channel {@link SslHandle} state machine.
 * <p><b>Lifecycle:</b>
 * <pre>
 *   constructor
 *       -> resolve/create SSLContext (or reuse user-supplied one)
 *       -> build SslEngineWrap factory
 *       -> build SslHandle
 *   first RCV/SND pass
 *       -> SslHandle.tryHandshake(...)
 *       -> SslEngineWrap.beginHandshake()
 *       -> NotHandshaking -> Handshaking -> Finish
 *   steady state
 *       -> handRcv()/handSnd() delegate encrypted traffic to SslHandle
 *   TLS shutdown
 *       -> signal close_notify if requested
 *       -> sslEnable becomes false after TLS close handling completes
 * </pre>
 * <p><b>Subclass responsibility:</b> concrete subclasses (for example {@link JdkSslContext})
 * must implement:
 * <ul>
 *   <li>{@link #createSSLContext(String[])} — create the
 *       {@link javax.net.ssl.SSLContext} with the appropriate key/trust material.</li>
 *   <li>{@link #configSslEngine(javax.net.ssl.SSLContext, javax.net.ssl.SSLEngine)} —
 *       configure cipher suites, protocols, and SNI on the freshly created engine.</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-10-20
 * @see JdkSslContext
 * @see SslHandle
 * @see SslConfig
 */
public abstract class SslContextBasic implements SslContext {
    protected static final Logger        logger = Logger.getLogger(SslContextBasic.class);
    protected final        SoChannel<?>  channel;
    protected final        long          channelId;
    protected final        String        stackName;
    protected final        ProtoContext  protoCtx;
    protected final        SoContext     soContext;
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
        return this.sslEnable && this.sslHandler.getHandshake() == SslHandshakeStatus.Finish;
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
    public String getSniHostName() {
        // On server side: read the SNI from the client's TLS handshake via ExtendedSSLSession
        SSLEngine engine = this.sslEngine.unwrap();
        if (engine != null) {
            SSLSession session = engine.getHandshakeSession();
            if (session == null) {
                session = engine.getSession();
            }
            if (session instanceof ExtendedSSLSession) {
                List<SNIServerName> serverNames = ((ExtendedSSLSession) session).getRequestedServerNames();
                if (serverNames != null) {
                    for (SNIServerName sn : serverNames) {
                        if (sn.getType() == StandardConstants.SNI_HOST_NAME && sn instanceof SNIHostName) {
                            return ((SNIHostName) sn).getAsciiName();
                        }
                    }
                }
            }
        }
        // Fallback: return configured SNI host name
        String configured = this.sslConfig.getSniHostName();
        if (StringUtils.isNotBlank(configured)) {
            return configured;
        }
        return null;
    }

    @Override
    public SslCertConfig getConfig() {
        return this.sslConfig;
    }

    /** create KeyStore */
    protected KeyStore createKeyStore() throws GeneralSecurityException {
        return SslCertHelper.createKeyStore(this.sslConfig);
    }

    /** create KeyManagerFactory */
    protected KeyManagerFactory createKeyManagerFactory(KeyStore keyStore) throws GeneralSecurityException, IOException {
        return SslCertHelper.createKeyManagerFactory(this.sslConfig, keyStore);
    }

    /** create TrustManagerFactory */
    protected TrustManagerFactory getTrustManagers(KeyStore keyStore) throws GeneralSecurityException, IOException {
        return SslCertHelper.createTrustManagerFactory(this.sslConfig, keyStore);
    }

    /** create SSLContext */
    protected abstract SSLContext createSSLContext(String[] protocol) throws GeneralSecurityException, IOException;

    /** create SSLEngine */
    protected abstract SSLEngine configSslEngine(SSLContext sslContext, SSLEngine engine) throws IOException;

    /** Receiving SSL data */
    public ProtoStatus handRcv(ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown, ProtoRcvQueue<ByteBuf> sndUp, ProtoSndQueue<ByteBuf> sndDown) throws IOException {
        if (this.sslEnable) {
            if (!sndDown.hasSlot()) {
                if (this.protoCtx.getConfig().isPrintLog()) {
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
                if (this.protoCtx.getConfig().isPrintLog()) {
                    logger.info("sslSnd(" + this.channelId + ") rcvDown or sndDown Buffer is full.");
                }
                return ProtoStatus.Next;
            }

            boolean hsReady = this.sslHandler.tryHandshake(false, rcvUp, rcvDown, sndUp, sndDown);
            if (hsReady) {
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
        if (!this.sslEngine.isOutboundDone()) {
            this.sslHandler.signalCloseNotify();
            this.protoCtx.flush();
        }
        this.sslEnable = false;
    }

    @Override
    public void openSSL() {
        this.sslEnable = true;
    }

    /** Signal the {@link SslHandle} to produce a TLS {@code close_notify} alert */
    void signalCloseNotify() {
        if (this.sslEnable) {
            this.sslHandler.signalCloseNotify();
        }
    }
}