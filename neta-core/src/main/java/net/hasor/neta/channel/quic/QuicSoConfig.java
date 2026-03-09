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
import net.hasor.neta.channel.udp.UdpSoConfig;
import net.hasor.neta.codec.ssl.SslCertConfig;

/**
 * QUIC-specific channel configuration layered on top of {@link UdpSoConfig}.
 * <p>
 * This object mixes two kinds of settings:
 * <ul>
 *   <li>transport parameters that are advertised to the peer during the QUIC handshake, such as
 *       {@code initial_max_data}, {@code initial_max_streams_*}, and the DATAGRAM limit</li>
 *   <li>local runtime policy such as certificate configuration, stream idle timeout,
 *       DATAGRAM administrative enablement, version selection, and the connection listener</li>
 * </ul>
 * <p>
 * Not every field becomes a negotiated wire parameter. Some options only affect local
 * behaviour after the connection is running.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicSoConfig extends UdpSoConfig {
    // ── SSL fields ─────────────────────────────────────────────────────
    private SslCertConfig          sslConfig;                                   // TLS certificate + private key config; null = TLS disabled
    // ── Connection-level fields ────────────────────────────────────────
    private int                    connectionIdLength               = 8;        // Connection ID length in bytes, range 0-20 (RFC 9000 §17.2)
    // ── Transport parameters (RFC 9000 §18.2) ─────────────────────────
    private long                   tpMaxIdleTimeout                 = 30000;    // connection idle timeout (ms); 0 = no timeout
    private long                   streamIdleTimeoutMs              = 0;        // stream idle timeout (ms); 0 = disabled (app-layer, not an RFC transport param)
    private long                   tpInitialFrameMaxData            = 1048576;  // connection-level initial receive window (initial_max_data)
    private long                   tpInitialMaxStreamDataBidiLocal  = 262144;   // initial receive window for locally-initiated bidi streams
    private long                   tpInitialMaxStreamDataBidiRemote = 262144;   // initial receive window for remotely-initiated bidi streams
    private long                   tpInitialMaxStreamDataUni        = 262144;   // initial receive window for remotely-initiated uni streams
    private long                   tpInitialMaxStreamsBidi          = 100;      // max concurrent bidi streams the peer may open
    private long                   tpInitialMaxStreamsUni           = 100;      // max concurrent uni streams the peer may open
    private long                   tpInitialDatagramFrameMaxData    = 0;        // max DATAGRAM frame payload size (RFC 9221); 0 = disabled
    private boolean                disableDatagram                  = false;
    private QuicVersion            quicVersion                      = QuicVersion.V1;
    private QuicConnectionListener connectionListener               = null;

    public QuicSoConfig() {
        super(QuicProvider.NAME);
        this.setRcvPacketSize(65535);
    }

    // ── SSL Configuration ──────────────────────────────────────────────

    public boolean isSslEnabled() {
        return this.sslConfig != null;
    }

    /** Returns the shared SSL certificate configuration. */
    public SslCertConfig getSslConfig() {
        return this.sslConfig;
    }

    public void setSslConfig(SslCertConfig sslConfig) {
        this.sslConfig = sslConfig;
    }

    // ── Connection-level Configuration ─────────────────────────────────

    /** Length of generated QUIC connection IDs in bytes (default 8, range 0-20). */
    public int getConnectionIdLength() {
        return this.connectionIdLength;
    }

    public QuicSoConfig setConnectionIdLength(int connectionIdLength) {
        if (connectionIdLength < 0 || connectionIdLength > 20) {
            throw new IllegalArgumentException("connectionIdLength must be 0-20 per RFC 9000 §17.2: " + connectionIdLength);
        }
        this.connectionIdLength = connectionIdLength;
        return this;
    }

    // ── Transport Parameters (RFC 9000 §18.2) ──────────────────────────

    public long getTpMaxIdleTimeout() {
        return this.tpMaxIdleTimeout;
    }

    public QuicSoConfig setTpMaxIdleTimeout(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("tp_max_idle_timeout must be non-negative: " + value);
        }
        this.tpMaxIdleTimeout = value;
        return this;
    }

    public long getTpInitialFrameMaxData() {
        return this.tpInitialFrameMaxData;
    }

    public QuicSoConfig setTpInitialFrameMaxData(long value) {
        this.tpInitialFrameMaxData = value;
        return this;
    }

    public long getTpInitialMaxStreamDataBidiLocal() {
        return this.tpInitialMaxStreamDataBidiLocal;
    }

    public QuicSoConfig setTpInitialMaxStreamDataBidiLocal(long value) {
        this.tpInitialMaxStreamDataBidiLocal = value;
        return this;
    }

    public long getTpInitialMaxStreamDataBidiRemote() {
        return this.tpInitialMaxStreamDataBidiRemote;
    }

    public QuicSoConfig setTpInitialMaxStreamDataBidiRemote(long value) {
        this.tpInitialMaxStreamDataBidiRemote = value;
        return this;
    }

    public long getTpInitialMaxStreamDataUni() {
        return this.tpInitialMaxStreamDataUni;
    }

    public QuicSoConfig setTpInitialMaxStreamDataUni(long value) {
        this.tpInitialMaxStreamDataUni = value;
        return this;
    }

    public long getTpInitialMaxStreamsBidi() {
        return this.tpInitialMaxStreamsBidi;
    }

    public QuicSoConfig setTpInitialMaxStreamsBidi(long value) {
        this.tpInitialMaxStreamsBidi = value;
        return this;
    }

    public long getTpInitialMaxStreamsUni() {
        return this.tpInitialMaxStreamsUni;
    }

    public QuicSoConfig setTpInitialMaxStreamsUni(long value) {
        this.tpInitialMaxStreamsUni = value;
        return this;
    }

    /** Returns the max DATAGRAM frame payload size (RFC 9221); 0 means DATAGRAM is disabled. */
    public long getTpInitialDatagramFrameMaxData() {
        return this.tpInitialDatagramFrameMaxData;
    }

    /** Sets the max DATAGRAM frame payload size (RFC 9221); 0 disables DATAGRAM support. */
    public QuicSoConfig setTpInitialDatagramFrameMaxData(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("tp_max_datagram_frame_size must be non-negative: " + value);
        }
        this.tpInitialDatagramFrameMaxData = value;
        return this;
    }

    /** Returns true if DATAGRAM is administratively disabled; all DATAGRAM usage will be blocked. */
    public boolean isDisableDatagram() {
        return this.disableDatagram;
    }

    /** Enables or disables the application-layer DATAGRAM gate for connections created from this config. */
    public QuicSoConfig setDisableDatagram(boolean disableDatagram) {
        this.disableDatagram = disableDatagram;
        return this;
    }

    // ── QUIC Version ─────────────────────────────────────────────────────────

    /** Returns the configured QUIC protocol version (default: V1). */
    public QuicVersion getQuicVersion() {
        return this.quicVersion;
    }

    /** Sets the QUIC protocol version to use (e.g. V1 or V2). */
    public QuicSoConfig setQuicVersion(QuicVersion quicVersion) {
        if (quicVersion == null) {
            throw new IllegalArgumentException("quicVersion must not be null");
        }
        this.quicVersion = quicVersion;
        return this;
    }

    // ── Connection Established Listener ──────────────────────────────────

    /** Returns the connection-established listener, or null if none is set. */
    public QuicConnectionListener getConnectionListener() {
        return this.connectionListener;
    }

    /** Registers a listener invoked when a QUIC connection handshake completes. */
    public QuicSoConfig setConnectionListener(QuicConnectionListener listener) {
        this.connectionListener = listener;
        return this;
    }

    // ── Stream Idle Timeout ────────────────────────────────────────────

    /** Returns the stream-level idle timeout in milliseconds (0 = disabled). */
    public long getStreamIdleTimeoutMs() {
        return this.streamIdleTimeoutMs;
    }

    /** Sets the stream-level idle timeout in milliseconds; streams inactive for this period are auto-reset (0 = disabled). */
    public QuicSoConfig setStreamIdleTimeoutMs(long streamIdleTimeoutMs) {
        if (streamIdleTimeoutMs < 0) {
            throw new IllegalArgumentException("streamIdleTimeoutMs must be non-negative: " + streamIdleTimeoutMs);
        }
        this.streamIdleTimeoutMs = streamIdleTimeoutMs;
        return this;
    }
}
