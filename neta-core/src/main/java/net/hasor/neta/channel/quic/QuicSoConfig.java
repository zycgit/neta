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
 * QUIC-specific configuration options, extending {@link UdpSoConfig} since
 * QUIC is built on top of UDP.
 * <p>
 * This class consolidates all configuration into a single place:
 * <ul>
 *   <li><b>SSL</b>: sslEnabled, certChain, privateKey</li>
 *   <li><b>Connection</b>: connectionIdLength, selectorPollMs</li>
 *   <li><b>Transport parameters</b> (RFC 9000 §18.2): all fields prefixed with {@code tp},
 *       e.g. {@link #getTpInitialFrameMaxData()}, {@link #setTpMaxIdleTimeout(long)}.
 *       Transport parameter <em>wire IDs</em> ({@code PARAM_*}) are in
 *       {@link QuicAsyncChannelHandshake}.</li>
 * </ul>
 * <p>All fields in this class are <em>startup-only</em> &mdash; they must be set before the
 * connection is established and must not be modified at runtime.
 * Runtime state (flow control, datagram enablement, peer advertised limits, etc.)
 * is tracked internally by {@code QuicChannel}.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicSoConfig extends UdpSoConfig {
    // ── SSL fields ─────────────────────────────────────────────────────
    private SslCertConfig sslConfig;                                   // TLS certificate + private key config; null = TLS disabled
    // ── Connection-level fields ────────────────────────────────────────
    private int           connectionIdLength               = 8;        // Connection ID length in bytes, range 0-20 (RFC 9000 §17.2)
    // ── Transport parameters (RFC 9000 §18.2) ─────────────────────────
    private long          tpMaxIdleTimeout                 = 30000;    // connection idle timeout (ms); 0 = no timeout
    private long          streamIdleTimeoutMs              = 0;        // stream idle timeout (ms); 0 = disabled (app-layer, not an RFC transport param)
    private long          tpInitialFrameMaxData            = 1048576;  // connection-level initial receive window (initial_max_data)
    private long          tpInitialMaxStreamDataBidiLocal  = 262144;   // initial receive window for locally-initiated bidi streams
    private long          tpInitialMaxStreamDataBidiRemote = 262144;   // initial receive window for remotely-initiated bidi streams
    private long          tpInitialMaxStreamDataUni        = 262144;   // initial receive window for remotely-initiated uni streams
    private long          tpInitialMaxStreamsBidi          = 100;      // max concurrent bidi streams the peer may open
    private long          tpInitialMaxStreamsUni           = 100;      // max concurrent uni streams the peer may open
    private long          tpInitialDatagramFrameMaxData    = 0;        // max DATAGRAM frame payload size (RFC 9221); 0 = disabled
    private boolean       disableDatagram                  = false;
    private QuicVersion   quicVersion                      = QuicVersion.V1;

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

    /**
     * Returns the maximum DATAGRAM frame size (RFC 9221).
     * 0 means DATAGRAM frames are not supported; a positive value enables DATAGRAM support.
     */
    public long getTpInitialDatagramFrameMaxData() {
        return this.tpInitialDatagramFrameMaxData;
    }

    /**
     * Sets the maximum DATAGRAM frame size (RFC 9221).
     * Set to 0 to disable DATAGRAM support, or a positive value to enable it.
     * @param value maximum DATAGRAM frame payload size in bytes (0 = disabled)
     */
    public QuicSoConfig setTpInitialDatagramFrameMaxData(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("tp_max_datagram_frame_size must be non-negative: " + value);
        }
        this.tpInitialDatagramFrameMaxData = value;
        return this;
    }

    /**
     * Returns whether DATAGRAM support is administratively disabled.
     * When {@code true}, {@link QuicChannel#openDatagramChannel()} always throws and
     * incoming DATAGRAM frames are silently dropped.
     */
    public boolean isDisableDatagram() {
        return this.disableDatagram;
    }

    /**
     * Enables or disables the application-layer DATAGRAM gate.
     * @param disableDatagram {@code true} to block all DATAGRAM usage on connections
     * created from this config; {@code false} (default) to allow it
     */
    public QuicSoConfig setDisableDatagram(boolean disableDatagram) {
        this.disableDatagram = disableDatagram;
        return this;
    }

    // ── QUIC Version ─────────────────────────────────────────────────────────

    /**
     * Returns the QUIC protocol version to use.
     * @return the configured {@link QuicVersion}, defaults to {@link QuicVersion#V1}
     */
    public QuicVersion getQuicVersion() {
        return this.quicVersion;
    }

    /**
     * Sets the QUIC protocol version to use.
     * @param quicVersion the target QUIC version (e.g. {@link QuicVersion#V1}, {@link QuicVersion#V2})
     * @return this config instance for method chaining
     * @throws IllegalArgumentException if {@code quicVersion} is {@code null}
     */
    public QuicSoConfig setQuicVersion(QuicVersion quicVersion) {
        if (quicVersion == null) {
            throw new IllegalArgumentException("quicVersion must not be null");
        }
        this.quicVersion = quicVersion;
        return this;
    }

    // ── Stream Idle Timeout ────────────────────────────────────────────

    /**
     * Returns the stream-level idle timeout in milliseconds.
     * 0 means stream idle timeout is disabled.
     * @return stream idle timeout in ms, or 0 if disabled
     */
    public long getStreamIdleTimeoutMs() {
        return this.streamIdleTimeoutMs;
    }

    /**
     * Sets the stream-level idle timeout in milliseconds.
     * If no data is sent or received on a stream within this period, the stream is
     * automatically reset and a {@link QuicIdleTimeoutException} is propagated
     * through the stream's pipeline.
     * <p>Set to 0 to disable stream-level idle timeout (default).
     * @param streamIdleTimeoutMs stream idle timeout in ms (0 = disabled)
     * @return this config instance for method chaining
     * @throws IllegalArgumentException if {@code streamIdleTimeoutMs} is negative
     */
    public QuicSoConfig setStreamIdleTimeoutMs(long streamIdleTimeoutMs) {
        if (streamIdleTimeoutMs < 0) {
            throw new IllegalArgumentException("streamIdleTimeoutMs must be non-negative: " + streamIdleTimeoutMs);
        }
        this.streamIdleTimeoutMs = streamIdleTimeoutMs;
        return this;
    }
}
