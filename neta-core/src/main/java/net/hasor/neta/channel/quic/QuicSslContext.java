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
package net.hasor.neta.channel.quic;
import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.codec.ssl.SslCertConfig;
import net.hasor.neta.codec.ssl.SslContext;

/**
 * Post-handshake {@link SslContext} view for a QUIC connection.
 * <p>
 * The object exposes the TLS metadata produced by {@link QuicTlsEngine}, such as
 * certificate configuration, negotiated ALPN, peer host information, and SNI.
 * It is not a general-purpose live TLS controller like the stream SSL codec layer:
 * QUIC packet protection is owned by the connection runtime, while this class mainly
 * serves as a read-oriented facade for application code.
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
    private final    String        sniHostName;    // SNI server_name from TLS handshake
    private volatile boolean       ready;

    /** Creates a QuicSslContext wrapping the results of a completed QUIC handshake. */
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

    @Override
    public SslCertConfig getConfig() {
        return this.certConfig;
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
        return this.ready;
    }

    @Override
    public String getApplicationProtocol() {
        if (this.negotiatedAlpn != null) {
            return this.negotiatedAlpn;
        }
        // fallback to configured default
        return this.certConfig.resolveDefaultProtocol();
    }

    @Override
    public String getPeerHost() {
        return this.peerHost;
    }

    @Override
    public int getPeerPort() {
        return this.peerPort;
    }

    @Override
    public String getSniHostName() {
        return this.sniHostName;
    }

    /** No-op: QUIC encryption cannot be switched off on a live connection. */
    @Override
    public void closeSSL() {
        this.ready = false;
    }

    /** No-op: QUIC encryption is managed by the connection lifecycle, not an on/off toggle. */
    @Override
    public void openSSL() {
        this.ready = true;
    }
}
