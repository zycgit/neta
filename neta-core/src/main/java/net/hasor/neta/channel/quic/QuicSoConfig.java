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
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import net.hasor.neta.channel.udp.UdpSoConfig;

/**
 * QUIC-specific configuration options, extending {@link UdpSoConfig} since
 * QUIC is built on top of UDP.
 * <ul>
 *   <li>sslEnabled: when true, full TLS 1.3 + QUIC encryption is used;
 *       when false, raw unencrypted QUIC frames are sent (useful for testing).</li>
 *   <li>certChain / privateKey: X.509 certificate chain and private key for SSL mode.</li>
 *   <li>transportParams: QUIC transport parameters (RFC 9000 §18.2).</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicSoConfig extends UdpSoConfig {
    private boolean           sslEnabled         = true;
    private X509Certificate[] certChain;
    private PrivateKey        privateKey;
    private QuicSettings      transportParams;
    private int               maxStreams         = 100;
    private int               connectionIdLength = 8;
    /** Selector poll interval in milliseconds (default 100). Reduce for low-latency tests. */
    private int               selectorPollMs     = 100;

    public QuicSoConfig() {
        super(QuicProvider.NAME);
        this.transportParams = defaultSettings();
        // QUIC default rcvPacketSize
        this.setRcvPacketSize(65535);
    }

    private static QuicSettings defaultSettings() {
        QuicSettings s = new QuicSettings();
        s.maxIdleTimeout(30000);
        s.initialMaxData(1048576);
        s.initialMaxStreamDataBidiLocal(262144);
        s.initialMaxStreamDataBidiRemote(262144);
        s.initialMaxStreamDataUni(262144);
        s.initialMaxStreamsBidi(100);
        s.initialMaxStreamsUni(100);
        s.activeConnectionIdLimit(8);
        return s;
    }

    // ── SSL Configuration ──────────────────────────────────────────────

    public boolean isSslEnabled() {
        return this.sslEnabled;
    }

    public QuicSoConfig setSslEnabled(boolean sslEnabled) {
        this.sslEnabled = sslEnabled;
        return this;
    }

    public X509Certificate[] getCertChain() {
        return this.certChain;
    }

    public QuicSoConfig setCertChain(X509Certificate[] certChain) {
        this.certChain = certChain;
        return this;
    }

    public PrivateKey getPrivateKey() {
        return this.privateKey;
    }

    public QuicSoConfig setPrivateKey(PrivateKey privateKey) {
        this.privateKey = privateKey;
        return this;
    }

    // ── Transport Parameters ───────────────────────────────────────────

    public QuicSettings getTransportParams() {
        return this.transportParams;
    }

    public QuicSoConfig setTransportParams(QuicSettings transportParams) {
        this.transportParams = transportParams;
        return this;
    }

    public int getMaxStreams() {
        return this.maxStreams;
    }

    public QuicSoConfig setMaxStreams(int maxStreams) {
        this.maxStreams = maxStreams;
        return this;
    }

    // ── Connection ID Settings ─────────────────────────────────────────

    /** Length of generated QUIC connection IDs in bytes (default 8, range 0-20). */
    public int getConnectionIdLength() {
        return this.connectionIdLength;
    }

    public QuicSoConfig setConnectionIdLength(int connectionIdLength) {
        this.connectionIdLength = connectionIdLength;
        return this;
    }

    // ── Selector Poll Settings ─────────────────────────────────────────

    /**
     * UDP selector poll interval in milliseconds (default 100ms).
     * Reducing this value (e.g. to 5ms) makes the QUIC receive loop check for
     * new packets more frequently, which reduces handshake and RTT latency at
     * the cost of slightly higher CPU usage. Useful for unit tests.
     */
    public int getSelectorPollMs() {
        return this.selectorPollMs;
    }

    public QuicSoConfig setSelectorPollMs(int selectorPollMs) {
        this.selectorPollMs = selectorPollMs;
        return this;
    }
}
