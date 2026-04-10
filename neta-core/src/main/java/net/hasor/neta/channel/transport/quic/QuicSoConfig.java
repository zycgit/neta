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
import net.hasor.neta.channel.transport.udp.UdpSoConfig;
import net.hasor.neta.codec.ssl.SslCertConfig;

/**
 * QUIC-specific channel configuration built on top of {@link UdpSoConfig}.
 * <p>This object carries two categories of settings:
 * <ul>
 *   <li>transport parameters advertised to the peer during the QUIC handshake, such as {@code initial_max_data},
 *       {@code initial_max_streams_*}, and DATAGRAM limits</li>
 *   <li>settings that only affect local runtime policy, such as certificate configuration, stream idle timeout,
 *       DATAGRAM enablement, version selection, and connection-established listeners</li>
 * </ul>
 * <p>Not every field becomes a negotiated wire-level parameter; some options only affect local behavior after the
 * connection is established.
 * @author 赵永春 (zyc@hasor.net)
 */
public class QuicSoConfig extends UdpSoConfig {
    // ── SSL configuration ─────────────────────────────────────────────
    private SslCertConfig          sslConfig;                                   // TLS certificate and private-key configuration; null means TLS is disabled
    // ── Connection-level configuration ────────────────────────────────
    private int                    connectionIdLength               = 8;        // Connection ID length in bytes, range 0-20 (RFC 9000 §17.2)
    // ── Transport parameters (RFC 9000 §18.2) ────────────────────────
    private long                   tpMaxIdleTimeout                 = 30000;    // Connection idle timeout in milliseconds; 0 means disabled
    private long                   streamIdleTimeoutMs              = 0;        // Stream idle timeout in milliseconds; 0 means disabled (application policy, not a transport parameter)
    private long                   tpInitialFrameMaxData            = 1048576;  // Initial connection-level receive window, mapped to initial_max_data
    private long                   tpInitialMaxStreamDataBidiLocal  = 262144;   // Initial receive window for locally initiated bidirectional streams
    private long                   tpInitialMaxStreamDataBidiRemote = 262144;   // Initial receive window for peer-initiated bidirectional streams
    private long                   tpInitialMaxStreamDataUni        = 262144;   // Initial receive window for peer-initiated unidirectional streams
    private long                   tpInitialMaxStreamsBidi          = 100;      // Maximum number of bidirectional streams the peer may open concurrently
    private long                   tpInitialMaxStreamsUni           = 100;      // Maximum number of unidirectional streams the peer may open concurrently
    private long                   tpInitialDatagramFrameMaxData    = 0;        // Maximum DATAGRAM frame payload length; 0 means disabled (RFC 9221)
    private boolean                disableDatagram                  = false;
    private QuicVersion            quicVersion                      = QuicVersion.V1;
    private QuicConnectionListener connectionListener               = null;

    /**
     * Creates a QUIC configuration object with default settings.
     */
    public QuicSoConfig() {
        super(QuicProvider.NAME);
        this.setRcvPacketSize(65535);
    }

    // ── SSL configuration ─────────────────────────────────────────────

    /**
     * Returns whether SSL configuration is enabled.
     */
    public boolean isSslEnabled() {
        return this.sslConfig != null;
    }

    /**
     * Returns the shared SSL certificate configuration.
     */
    public SslCertConfig getSslConfig() {
        return this.sslConfig;
    }

    /**
     * Sets the shared SSL certificate configuration.
     */
    public void setSslConfig(SslCertConfig sslConfig) {
        this.sslConfig = sslConfig;
    }

    // ── Connection-level configuration ────────────────────────────────

    /**
     * Returns the generated QUIC Connection ID length in bytes.
     */
    public int getConnectionIdLength() {
        return this.connectionIdLength;
    }

    /**
     * Sets the generated QUIC Connection ID length.
     * @param connectionIdLength valid lengths range from 0 to 20
     */
    public QuicSoConfig setConnectionIdLength(int connectionIdLength) {
        if (connectionIdLength < 0 || connectionIdLength > 20) {
            throw new IllegalArgumentException("connectionIdLength must be 0-20 per RFC 9000 §17.2: " + connectionIdLength);
        }
        this.connectionIdLength = connectionIdLength;
        return this;
    }

    // ── Transport parameters (RFC 9000 §18.2) ────────────────────────

    /**
     * Returns the connection idle timeout in milliseconds.
     */
    public long getTpMaxIdleTimeout() {
        return this.tpMaxIdleTimeout;
    }

    /**
     * Sets the connection idle timeout in milliseconds.
     */
    public QuicSoConfig setTpMaxIdleTimeout(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("tp_max_idle_timeout must be non-negative: " + value);
        }
        this.tpMaxIdleTimeout = value;
        return this;
    }

    /**
     * Returns the initial connection-level receive window, mapped to initial_max_data.
     */
    public long getTpInitialFrameMaxData() {
        return this.tpInitialFrameMaxData;
    }

    /**
     * Sets the initial connection-level receive window, mapped to initial_max_data.
     */
    public QuicSoConfig setTpInitialFrameMaxData(long value) {
        this.tpInitialFrameMaxData = value;
        return this;
    }

    /**
     * Returns the initial receive window for locally initiated bidirectional streams.
     */
    public long getTpInitialMaxStreamDataBidiLocal() {
        return this.tpInitialMaxStreamDataBidiLocal;
    }

    /**
     * Sets the initial receive window for locally initiated bidirectional streams.
     */
    public QuicSoConfig setTpInitialMaxStreamDataBidiLocal(long value) {
        this.tpInitialMaxStreamDataBidiLocal = value;
        return this;
    }

    /**
     * Returns the initial receive window for peer-initiated bidirectional streams.
     */
    public long getTpInitialMaxStreamDataBidiRemote() {
        return this.tpInitialMaxStreamDataBidiRemote;
    }

    /**
     * Sets the initial receive window for peer-initiated bidirectional streams.
     */
    public QuicSoConfig setTpInitialMaxStreamDataBidiRemote(long value) {
        this.tpInitialMaxStreamDataBidiRemote = value;
        return this;
    }

    /**
     * Returns the initial receive window for peer-initiated unidirectional streams.
     */
    public long getTpInitialMaxStreamDataUni() {
        return this.tpInitialMaxStreamDataUni;
    }

    /**
     * Sets the initial receive window for peer-initiated unidirectional streams.
     */
    public QuicSoConfig setTpInitialMaxStreamDataUni(long value) {
        this.tpInitialMaxStreamDataUni = value;
        return this;
    }

    /**
     * Returns the maximum number of bidirectional streams the peer may open concurrently.
     */
    public long getTpInitialMaxStreamsBidi() {
        return this.tpInitialMaxStreamsBidi;
    }

    /**
     * Sets the maximum number of bidirectional streams the peer may open concurrently.
     */
    public QuicSoConfig setTpInitialMaxStreamsBidi(long value) {
        this.tpInitialMaxStreamsBidi = value;
        return this;
    }

    /**
     * Returns the maximum number of unidirectional streams the peer may open concurrently.
     */
    public long getTpInitialMaxStreamsUni() {
        return this.tpInitialMaxStreamsUni;
    }

    /**
     * Sets the maximum number of unidirectional streams the peer may open concurrently.
     */
    public QuicSoConfig setTpInitialMaxStreamsUni(long value) {
        this.tpInitialMaxStreamsUni = value;
        return this;
    }

    /**
     * Returns the maximum payload size allowed for DATAGRAM frames.
     * <p>0 means DATAGRAM capability is not enabled.
     */
    public long getTpInitialDatagramFrameMaxData() {
        return this.tpInitialDatagramFrameMaxData;
    }

    /**
     * Sets the maximum payload size allowed for DATAGRAM frames.
     * <p>0 means DATAGRAM capability is disabled.
     */
    public QuicSoConfig setTpInitialDatagramFrameMaxData(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("tp_max_datagram_frame_size must be non-negative: " + value);
        }
        this.tpInitialDatagramFrameMaxData = value;
        return this;
    }

    /**
     * Returns whether DATAGRAM is explicitly disabled by administrative policy.
     */
    public boolean isDisableDatagram() {
        return this.disableDatagram;
    }

    /**
     * Sets whether application-level DATAGRAM capability is disabled.
     */
    public QuicSoConfig setDisableDatagram(boolean disableDatagram) {
        this.disableDatagram = disableDatagram;
        return this;
    }

    // ── QUIC version ──────────────────────────────────────────────────

    /**
     * Returns the QUIC protocol version used by the current configuration.
     */
    public QuicVersion getQuicVersion() {
        return this.quicVersion;
    }

    /**
     * Sets the QUIC protocol version to use, such as V1 or V2.
     */
    public QuicSoConfig setQuicVersion(QuicVersion quicVersion) {
        if (quicVersion == null) {
            throw new IllegalArgumentException("quicVersion must not be null");
        }
        this.quicVersion = quicVersion;
        return this;
    }

    // ── Connection-established listener ───────────────────────────────

    /**
     * Returns the listener invoked after the connection is established.
     */
    public QuicConnectionListener getConnectionListener() {
        return this.connectionListener;
    }

    /**
     * Sets the listener invoked after the connection is established.
     */
    public QuicSoConfig setConnectionListener(QuicConnectionListener listener) {
        this.connectionListener = listener;
        return this;
    }

    // ── Stream idle timeout ───────────────────────────────────────────

    /**
     * Returns the stream-level idle timeout in milliseconds.
     * <p>0 means this policy is disabled.
     */
    public long getStreamIdleTimeoutMs() {
        return this.streamIdleTimeoutMs;
    }

    /**
     * Sets the stream-level idle timeout in milliseconds.
     * <p>A stream is reset automatically after remaining idle for this duration; 0 means disabled.
     */
    public QuicSoConfig setStreamIdleTimeoutMs(long streamIdleTimeoutMs) {
        if (streamIdleTimeoutMs < 0) {
            throw new IllegalArgumentException("streamIdleTimeoutMs must be non-negative: " + streamIdleTimeoutMs);
        }
        this.streamIdleTimeoutMs = streamIdleTimeoutMs;
        return this;
    }
}
