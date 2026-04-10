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
package net.hasor.neta.channel.transport.quic;
import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.codec.ssl.SslCertConfig;
import net.hasor.neta.codec.ssl.SslContext;

/**
 * Public {@link SslContext} view exposed by a QUIC connection after the handshake completes.
 * <p>This object wraps the TLS metadata produced by {@link QuicTlsEngine}, such as certificate configuration,
 * negotiated ALPN, peer host information, and SNI. It is not a general online TLS controller like a streaming
 * SSL codec; QUIC packet protection is handled by the connection runtime, while this type mainly provides a
 * read-only access point for the application layer.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2025-01-01
 */
public class QuicSslContext implements SslContext {
    private final    SoChannel<?>  channel;
    private final    SslCertConfig certConfig;
    private final    boolean       clientMode;
    private final    String        negotiatedAlpn;
    private final    String        peerHost;
    private final    int           peerPort;
    private final    String        sniHostName;    // SNI server_name extracted during the TLS handshake phase
    private volatile boolean       ready;

    /**
     * Creates the SSL context view from a completed QUIC handshake result.
     */
    QuicSslContext(SoChannel<?> channel, SslCertConfig certConfig, boolean clientMode, String negotiatedAlpn, String peerHost, int peerPort, String sniHostName) {
        this.channel = channel;
        this.certConfig = certConfig;
        this.clientMode = clientMode;
        this.negotiatedAlpn = negotiatedAlpn;
        this.peerHost = peerHost;
        this.peerPort = peerPort;
        this.sniHostName = sniHostName;
        this.ready = true;
    }

    /**
     * Returns the certificate configuration.
     */
    @Override
    public SslCertConfig getConfig() {
        return this.certConfig;
    }

    /**
     * Returns the associated channel object.
     */
    @Override
    public SoChannel<?> getChannel() {
        return this.channel;
    }

    /**
     * Returns whether the current context is operating in server mode.
     */
    @Override
    public boolean isServer() {
        return !this.clientMode;
    }

    /**
     * Returns whether the current context is operating in client mode.
     */
    @Override
    public boolean isClient() {
        return this.clientMode;
    }

    /**
     * Returns whether the SSL context is in a usable state.
     */
    @Override
    public boolean isReady() {
        return this.ready;
    }

    /**
     * Returns the negotiated application-layer protocol.
     * <p>If the negotiation result is empty, it falls back to the default protocol in the certificate configuration.
     */
    @Override
    public String getApplicationProtocol() {
        if (this.negotiatedAlpn != null) {
            return this.negotiatedAlpn;
        }
        // Fall back to the default protocol from the configuration.
        return this.certConfig.resolveDefaultProtocol();
    }

    /**
     * Returns the peer host name.
     */
    @Override
    public String getPeerHost() {
        return this.peerHost;
    }

    /**
     * Returns the peer port.
     */
    @Override
    public int getPeerPort() {
        return this.peerPort;
    }

    /**
     * Returns the SNI host name from the TLS handshake.
     */
    @Override
    public String getSniHostName() {
        return this.sniHostName;
    }

    /**
     * Closes the SSL flag.
     * <p>For QUIC, this only updates the availability flag; encryption on a live connection cannot actually be turned off.
     */
    @Override
    public void closeSSL() {
        this.ready = false;
    }

    /**
     * Opens the SSL flag.
     * <p>For QUIC, this only restores the availability flag; the real encryption lifecycle is managed by the connection runtime.
     */
    @Override
    public void openSSL() {
        this.ready = true;
    }
}
