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
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.codec.ssl.SslCertConfig;
import net.hasor.neta.codec.ssl.SslCertHelper;

/**
 * Shared QUIC handshake runtime for both client and server flows.
 * <p>This class owns the pre-establishment state machine: Initial/Handshake/1-RTT
 * packet numbers, version-specific Initial keys, optional TLS 1.3 integration,
 * CRYPTO frame reassembly, transport-parameter exchange, and the transition to
 * the post-handshake {@link QuicChannelAsync} world.
 * <p><b>High-level progression:</b>
 * <pre>
 *   client/server setup
 *        |
 *        +--> derive Initial keys from DCID
 *        +--> exchange Initial packets
 *        +--> reassemble CRYPTO data
 *        +--> drive QuicTlsEngine if TLS is enabled
 *        +--> derive Handshake keys
 *        +--> derive 1-RTT keys
 *        +--> build QuicInitConfigData
 *        +--> hand off to QuicChannelAsync / QuicChannel
 * </pre>
 * <p>The implementation also supports non-TLS mode, where CRYPTO payloads are
 * still used as the handshake carrier but no TLS engine is created.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicAsyncChannelHandshake {
    /** Encryption level constants for external callers (maps to internal enum). */
    static final         int             LEVEL_INITIAL                             = 0;
    static final         int             LEVEL_HANDSHAKE                           = 1;
    static final         int             LEVEL_APP                                 = 2;
    // ── Transport Parameter IDs (RFC 9000 §18.2, RFC 9221) ─────────────
    /** Transport parameter IDs (RFC 9000 §18.2) used when encoding/decoding QUIC transport params. */
    static final         int             PARAM_ORIGINAL_DESTINATION_CID            = 0x00; // server MUST include
    static final         int             PARAM_MAX_IDLE_TIMEOUT                    = 0x01;
    static final         int             PARAM_MAX_UDP_PAYLOAD_SIZE                = 0x03;
    static final         int             PARAM_INITIAL_MAX_DATA                    = 0x04;
    static final         int             PARAM_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL  = 0x05;
    static final         int             PARAM_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE = 0x06;
    static final         int             PARAM_INITIAL_MAX_STREAM_DATA_UNI         = 0x07;
    static final         int             PARAM_INITIAL_MAX_STREAMS_BIDI            = 0x08;
    static final         int             PARAM_INITIAL_MAX_STREAMS_UNI             = 0x09;
    static final         int             PARAM_ACK_DELAY_EXPONENT                  = 0x0a;
    static final         int             PARAM_MAX_ACK_DELAY                       = 0x0b;
    static final         int             PARAM_ACTIVE_CONNECTION_ID_LIMIT          = 0x0e;
    static final         int             PARAM_INITIAL_SOURCE_CID                  = 0x0f; // both sides MUST include
    /** RFC 9221: max_datagram_frame_size transport parameter. 0 = DATAGRAM not supported. */
    static final         int             PARAM_MAX_DATAGRAM_FRAME_SIZE             = 0x20;
    private static final Logger          logger                                    = Logger.getLogger(QuicAsyncChannelHandshake.class);
    // ── Identity ───────────────────────────────────────────────────────
    private final        boolean         clientMode;
    private final        boolean         printLog;
    private final        QuicSoConfig    soConfig;
    private final        DatagramChannel udpChannel;
    private final        SocketAddress   remoteAddress;

    // ── Connection IDs (RFC 9000 §5.1) ─────────────────────────────────
    private final    byte[]                  localCid;   // our Connection ID
    // ── QUIC version ───────────────────────────────────────────────────
    private final    QuicVersion             quicVersion;
    // ── Packet numbers (monotonic per encryption level) ────────────────
    private final    AtomicLong              initialPacketNumber      = new AtomicLong(0);
    private final    AtomicLong              handshakePacketNumber    = new AtomicLong(0);
    private final    AtomicLong              appPacketNumber          = new AtomicLong(0);
    // ── max received packet numbers (for ACK / PN decoding) ────────
    private final    AtomicLong              maxInitialPacketNumber   = new AtomicLong(-1);
    private final    AtomicLong              maxHandshakePacketNumber = new AtomicLong(-1);
    private final    AtomicLong              maxAppPacketNumber       = new AtomicLong(-1);
    /** Buffered 0-RTT packets received before handshake completes. */
    private final    List<byte[]>            bufferedRtt0Data         = new ArrayList<byte[]>();
    /** Seen 0-RTT packet numbers for anti-replay protection (RFC 9001 §8.4). */
    private final    Set<Long>               seenRtt0PacketNumbers    = new HashSet<>();
    private          byte[]                  originalDcid; // client's DCID from first Initial (server-side only, for transport params)
    private          byte[]                  remoteCid;  // peer's Connection ID (DCID when sending)
    // ── Packet protection keys [key, iv, hp] per level ─────────────────
    private          byte[][]                clientInitialKeys;
    private          byte[][]                serverInitialKeys;
    private          byte[][]                clientHandshakeKeys;
    private          byte[][]                serverHandshakeKeys;
    private          byte[][]                clientAppKeys;
    private          byte[][]                serverAppKeys;
    // ── TLS engine (null if sslEnabled=false) ──────────────────────────
    private          QuicTlsEngine           tlsEngine;
    // ── Handshake state ────────────────────────────────────────────────
    private volatile QuicAsyncHandshakeState state                    = QuicAsyncHandshakeState.INITIAL;
    /** Key generation counter for key updates. Starts at 0 after handshake. */
    private          int                     keyUpdateGeneration      = 0;

    // ── CRYPTO frame reassembly (Initial level, server-side: receiving ClientHello) ────────────
    /** Reassembly buffer for fragmented CRYPTO stream data (e.g. ClientHello). */
    private byte[]        cryptoReassemblyBuf;
    /** Tracks which bytes have been received (prevents double-counting on retransmissions). */
    private boolean[]     cryptoReassemblyBitmap;
    /** Number of unique bytes received so far into the reassembly buffer. */
    private int           cryptoReassemblyReceived;
    /** Expected total length of the TLS handshake message (derived from TLS header). -1 = unknown. */
    private int           cryptoReassemblyExpected = -1;
    /** Address of the client that started this handshake (for deferred TLS processing). */
    private SocketAddress pendingClientAddr;

    // ── CRYPTO frame reassembly (Handshake level, client-side: receiving server Handshake) ─────
    /** Reassembly buffer for fragmented server Handshake CRYPTO data (EE+Cert+CertVerify+Finished). */
    private byte[]    hsReassemblyBuf;
    /** Tracks which bytes of the server Handshake CRYPTO stream have been received. */
    private boolean[] hsReassemblyBitmap;
    /** Unique bytes received into the server Handshake CRYPTO reassembly buffer. */
    private int       hsReassemblyReceived;

    /** Creates a new handshake handler for the given side, config, and UDP transport. */
    QuicAsyncChannelHandshake(boolean clientMode, QuicSoConfig soConfig,//
            DatagramChannel udpChannel, SocketAddress remoteAddress, boolean printLog) {
        this.clientMode = clientMode;
        this.soConfig = soConfig;
        this.udpChannel = udpChannel;
        this.remoteAddress = remoteAddress;
        this.quicVersion = soConfig.getQuicVersion();
        this.printLog = printLog;

        // Generate local Connection ID
        int cidLen = soConfig.getConnectionIdLength();
        this.localCid = new byte[cidLen];
        new SecureRandom().nextBytes(this.localCid);
        this.remoteCid = new byte[0]; // will be set during handshake
    }

    /**
     * Creates a new handshake handler with an explicit QUIC version override.
     * Used when the client receives a Version Negotiation packet (RFC 9000 §6) and must
     * re-initiate the handshake with a version selected from the server's advertised list.
     */
    QuicAsyncChannelHandshake(boolean clientMode, QuicSoConfig soConfig, QuicVersion overrideVersion,//
            DatagramChannel udpChannel, SocketAddress remoteAddress, boolean printLog) {
        this.clientMode = clientMode;
        this.soConfig = soConfig;
        this.udpChannel = udpChannel;
        this.remoteAddress = remoteAddress;
        this.quicVersion = overrideVersion;
        this.printLog = printLog;

        // Generate a fresh local Connection ID for the new attempt
        int cidLen = soConfig.getConnectionIdLength();
        this.localCid = new byte[cidLen];
        new SecureRandom().nextBytes(this.localCid);
        this.remoteCid = new byte[0]; // will be set during handshake
    }

    /** Concatenates two byte arrays. */
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /** Maps LEVEL_* integer constants to internal enum values. */
    private static QuicAsyncHandshakeState levelToState(int level) {
        switch (level) {
            case LEVEL_INITIAL:
                return QuicAsyncHandshakeState.INITIAL;
            case LEVEL_HANDSHAKE:
                return QuicAsyncHandshakeState.HANDSHAKE;
            case LEVEL_APP:
                return QuicAsyncHandshakeState.ESTABLISHED;
            default:
                throw new IllegalArgumentException("Unknown level: " + level);
        }
    }

    /**
     * Scans buf[0..received) for TLS handshake messages and returns total bytes through the Finished message; -1 if not yet received.
     */
    private static int calcHandshakeMessagesLength(byte[] buf, int received) {
        int pos = 0;
        while (pos + 4 <= received) {
            int msgType = buf[pos] & 0xFF;
            int msgLen = ((buf[pos + 1] & 0xFF) << 16) | ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
            int msgEnd = pos + 4 + msgLen;
            if (msgType == 0x14) { // Finished
                return msgEnd;
            }
            if (msgEnd > received) {
                return -1; // current message not yet fully received
            }
            pos = msgEnd;
        }
        return -1; // haven't seen Finished yet
    }

    /** Returns a human-readable name for common QUIC transport error codes. */
    private static String quicTransportErrorName(long code) {
        switch ((int) code) {
            case 0x00:
                return "NO_ERROR";
            case 0x01:
                return "INTERNAL_ERROR";
            case 0x02:
                return "CONNECTION_REFUSED";
            case 0x03:
                return "FLOW_CONTROL_ERROR";
            case 0x04:
                return "STREAM_LIMIT_ERROR";
            case 0x05:
                return "STREAM_STATE_ERROR";
            case 0x06:
                return "FINAL_SIZE_ERROR";
            case 0x07:
                return "FRAME_ENCODING_ERROR";
            case 0x08:
                return "TRANSPORT_PARAMETER_ERROR";
            case 0x09:
                return "CONNECTION_ID_LIMIT_ERROR";
            case 0x0a:
                return "PROTOCOL_VIOLATION";
            case 0x0b:
                return "INVALID_TOKEN";
            case 0x0c:
                return "APPLICATION_ERROR";
            case 0x0d:
                return "CRYPTO_BUFFER_EXCEEDED";
            case 0x0e:
                return "KEY_UPDATE_ERROR";
            case 0x0f:
                return "AEAD_LIMIT_REACHED";
            case 0x10:
                return "NO_VIABLE_PATH";
            default:
                return "unknown(0x" + Long.toHexString(code) + ")";
        }
    }

    private static String tlsAlertName(long code) {
        switch ((int) code) {
            case 42:
                return "bad_certificate";
            case 44:
                return "certificate_revoked";
            case 45:
                return "certificate_expired";
            case 46:
                return "certificate_unknown";
            case 47:
                return "illegal_parameter";
            case 48:
                return "unknown_ca";
            case 20:
                return "bad_record_mac";
            case 21:
                return "decryption_failed";
            case 22:
                return "record_overflow";
            case 40:
                return "handshake_failure";
            case 70:
                return "protocol_version";
            case 71:
                return "insufficient_security";
            case 80:
                return "internal_error";
            case 86:
                return "inappropriate_fallback";
            case 90:
                return "user_canceled";
            case 109:
                return "missing_extension";
            case 110:
                return "unsupported_extension";
            case 112:
                return "unrecognized_name";
            case 116:
                return "certificate_required";
            case 120:
                return "no_application_protocol";
            default:
                return "unknown (" + code + ")";
        }
    }

    public boolean isClientMode() {
        return this.clientMode;
    }

    public QuicAsyncHandshakeState getState() {
        return this.state;
    }

    public boolean isEstablished() {
        return this.state == QuicAsyncHandshakeState.ESTABLISHED;
    }

    /** Returns true if the connection was explicitly closed during the handshake; caller should discard this handler. */
    public boolean isAborted() {
        return this.state == QuicAsyncHandshakeState.CLOSED;
    }

    // ── Packet number management ───────────────────────────────────────

    /** Returns the current handshake phase mapped to the public {@link QuicHandshakeState} API. */
    public QuicHandshakeState getHandshakeState() {
        if (this.state == null) {
            return QuicHandshakeState.INITIAL;
        }
        switch (this.state) {
            case INITIAL:
                return QuicHandshakeState.INITIAL;
            case HANDSHAKE:
                return QuicHandshakeState.HANDSHAKE;
            case ESTABLISHED:
                return QuicHandshakeState.ESTABLISHED;
            case CLOSED:
                return QuicHandshakeState.CLOSED;
            default:
                return QuicHandshakeState.INITIAL;
        }
    }

    public byte[] getLocalCid() {
        return this.localCid;
    }

    public byte[] getRemoteCid() {
        return this.remoteCid;
    }

    public QuicVersion getQuicVersion() {
        return this.quicVersion;
    }

    public QuicTlsEngine getTlsEngine() {
        return this.tlsEngine;
    }

    public boolean isSslEnabled() {
        return this.soConfig.isSslEnabled();
    }

    public long nextInitialPacketNumber() {
        return this.initialPacketNumber.getAndIncrement();
    }

    public long nextHandshakePacketNumber() {
        return this.handshakePacketNumber.getAndIncrement();
    }

    public long nextAppPacketNumber() {
        return this.appPacketNumber.getAndIncrement();
    }

    // ── Key accessors ──────────────────────────────────────────────────

    public void updateMaxInitialPacketNumber(long pn) {
        this.maxInitialPacketNumber.updateAndGet(cur -> Math.max(cur, pn));
    }

    public void updateMaxHandshakePacketNumber(long pn) {
        this.maxHandshakePacketNumber.updateAndGet(cur -> Math.max(cur, pn));
    }

    // ── Initialization ─────────────────────────────────────────────────

    public void updateMaxAppPacketNumber(long pn) {
        this.maxAppPacketNumber.updateAndGet(cur -> Math.max(cur, pn));
    }

    public long getMaxInitialPacketNumber() {
        return this.maxInitialPacketNumber.get();
    }

    public long getMaxHandshakePacketNumber() {
        return this.maxHandshakePacketNumber.get();
    }

    // ── Server-side handshake processing ───────────────────────────────

    /** Returns the largest 1-RTT packet number <em>received</em> from the peer (for ACK generation and PN decoding). */
    public long getLastRcvAppPacketNumber() {
        return this.maxAppPacketNumber.get();
    }

    /** Returns the packet number of the most recently <em>sent</em> 1-RTT packet. */
    public long getLastSndAppPacketNumber() {
        return this.appPacketNumber.get() - 1;
    }

    /** Returns the keys we use to SEND at the given level. client mode → client keys; server mode → server keys. */
    public byte[][] getSendKeys(QuicAsyncHandshakeState level) {
        switch (level) {
            case INITIAL:
                return this.clientMode ? this.clientInitialKeys : this.serverInitialKeys;
            case HANDSHAKE:
                return this.clientMode ? this.clientHandshakeKeys : this.serverHandshakeKeys;
            case ESTABLISHED:
                return this.clientMode ? this.clientAppKeys : this.serverAppKeys;
            default:
                return null;
        }
    }

    /** Returns the keys we use to RECEIVE at the given level. client mode → server keys; server mode → client keys. */
    public byte[][] getRecvKeys(QuicAsyncHandshakeState level) {
        switch (level) {
            case INITIAL:
                return this.clientMode ? this.serverInitialKeys : this.clientInitialKeys;
            case HANDSHAKE:
                return this.clientMode ? this.serverHandshakeKeys : this.clientHandshakeKeys;
            case ESTABLISHED:
                return this.clientMode ? this.serverAppKeys : this.clientAppKeys;
            default:
                return null;
        }
    }

    // ── Client-side handshake processing ──────────────────────────────

    /**
     * Derives Initial encryption keys from the given Destination Connection ID; must be called before any Initial packet.
     */
    public void deriveInitialKeys(byte[] originalDcid) throws Exception {
        this.originalDcid = originalDcid; // store for transport parameters
        byte[][] secrets = QuicCrypto.deriveInitialSecrets(originalDcid, this.quicVersion);
        this.clientInitialKeys = QuicCrypto.derivePacketKeys(secrets[0], this.quicVersion);
        this.serverInitialKeys = QuicCrypto.derivePacketKeys(secrets[1], this.quicVersion);
    }

    /**
     * Initializes the TLS engine: server mode creates QuicTlsEngine with cert/key; client mode creates client-side engine.
     */
    public void initTlsEngine() throws Exception {
        if (!this.soConfig.isSslEnabled()) {
            this.tlsEngine = null;
            return;
        }
        if (!this.clientMode) {
            // Server mode: resolve certificate chain and private key via SslCertHelper
            SslCertConfig certConfig = this.soConfig.getSslConfig();
            if (certConfig.getCertChainDirect() == null || certConfig.getPrivateKeyDirect() == null) {
                // Load from PEM/JKS files if direct values not set
                Object[] certAndKey = SslCertHelper.loadCertificateAndKey(certConfig);
                if (certAndKey[0] != null) {
                    certConfig.setCertChainDirect((X509Certificate[]) certAndKey[0]);
                }
                if (certAndKey[1] != null) {
                    certConfig.setPrivateKeyDirect((PrivateKey) certAndKey[1]);
                }
            }
            this.tlsEngine = new QuicTlsEngine(certConfig, this.soConfig, null);
        } else {
            // Client mode: create TLS engine for client handshake
            SslCertConfig certConfig = this.soConfig.getSslConfig();
            this.tlsEngine = new QuicTlsEngine(certConfig, this.soConfig, null, true);
        }
    }

    /**
     * Processes a received Initial packet (server side): extracts CRYPTO frame, feeds it to the TLS engine, and sends ServerHello + server handshake messages.
     */
    public boolean processServerInitial(QuicPacket.ParsedPacket parsed, SocketAddress clientAddr) throws Exception {
        if (this.printLog) {
            logger.info("[QUIC-HS] processServerInitial state=" + this.state + " pn=" + parsed.packetNumber + " payLen=" + (parsed.payload != null ? parsed.payload.length : -1) + " from " + clientAddr);
        }
        if (this.state != QuicAsyncHandshakeState.INITIAL) {
            logger.debug("processServerInitial: state=" + this.state + " (not INITIAL), ignoring retransmitted Initial pn=" + parsed.packetNumber);
            return false;
        }

        // Extract peer's SCID as our remote CID
        this.remoteCid = parsed.scid != null ? parsed.scid : new byte[0];

        // Update largest received PN
        updateMaxInitialPacketNumber(parsed.packetNumber);

        // Extract CRYPTO frame from payload
        if (parsed.payload == null || parsed.payload.length == 0) {
            logger.warn("processServerInitial: empty/null payload, packet may have no CRYPTO frame");
            return false;
        }
        // ── CRYPTO frame reassembly (RFC 9000 §19.6) ──────────────────
        // A QUIC Initial packet may contain MULTIPLE CRYPTO frames in a single payload
        // (e.g. Firefox places different stream-offset fragments in the same packet).
        // We must scan ALL frames in the payload, not just the first CRYPTO frame.
        int scanOffset = 0;
        boolean hasCrypto = false;
        while (true) {
            long[] cryptoInfo = QuicPacket.parseCryptoFrame(parsed.payload, scanOffset);
            if (cryptoInfo == null) {
                break; // no more CRYPTO frames in this payload
            }
            hasCrypto = true;
            long cryptoStreamOffset = cryptoInfo[0];
            int dataOffset = (int) cryptoInfo[1];
            int dataLength = (int) cryptoInfo[2];
            // Advance scan position past this CRYPTO frame's data so we find the next frame
            scanOffset = dataOffset + dataLength;

            int fragStart = (int) cryptoStreamOffset;
            int fragEnd = fragStart + dataLength;

            if (this.cryptoReassemblyBuf == null) {
                // First fragment seen — allocate; may grow later
                int initSize = Math.max(fragEnd, 2048);
                this.cryptoReassemblyBuf = new byte[initSize];
                this.cryptoReassemblyBitmap = new boolean[initSize];
                this.cryptoReassemblyReceived = 0;
                this.cryptoReassemblyExpected = -1;
                this.pendingClientAddr = clientAddr;
            }

            // Grow buffer + bitmap if needed
            if (fragEnd > this.cryptoReassemblyBuf.length) {
                int newSize = fragEnd + 512;
                byte[] biggerBuf = new byte[newSize];
                System.arraycopy(this.cryptoReassemblyBuf, 0, biggerBuf, 0, this.cryptoReassemblyBuf.length);
                this.cryptoReassemblyBuf = biggerBuf;
                boolean[] biggerMap = new boolean[newSize];
                System.arraycopy(this.cryptoReassemblyBitmap, 0, biggerMap, 0, this.cryptoReassemblyBitmap.length);
                this.cryptoReassemblyBitmap = biggerMap;
            }

            // Copy fragment data and count only NEW (non-duplicate) bytes
            int newBytes = 0;
            for (int i = 0; i < dataLength; i++) {
                int bpos = fragStart + i;
                if (!this.cryptoReassemblyBitmap[bpos]) {
                    this.cryptoReassemblyBitmap[bpos] = true;
                    newBytes++;
                }
            }
            System.arraycopy(parsed.payload, dataOffset, this.cryptoReassemblyBuf, fragStart, dataLength);
            this.cryptoReassemblyReceived += newBytes;

            if (this.printLog) {
                logger.info("[QUIC-HS] CRYPTO fragment: offset=" + cryptoStreamOffset + " len=" + dataLength + " new=" + newBytes + " received=" + this.cryptoReassemblyReceived + (this.cryptoReassemblyExpected > 0 ? " expected=" + this.cryptoReassemblyExpected : ""));
            }

            // Once we have bytes 0-3 (TLS handshake header), compute the total expected length
            if (this.cryptoReassemblyExpected < 0 && this.cryptoReassemblyBitmap[0] && this.cryptoReassemblyBitmap[1] && this.cryptoReassemblyBitmap[2] && this.cryptoReassemblyBitmap[3]) {
                int tlsMsgLen = ((this.cryptoReassemblyBuf[1] & 0xFF) << 16) | ((this.cryptoReassemblyBuf[2] & 0xFF) << 8) | (this.cryptoReassemblyBuf[3] & 0xFF);
                this.cryptoReassemblyExpected = 4 + tlsMsgLen;
                if (this.printLog) {
                    logger.info("[QUIC-HS] TLS expected total=" + this.cryptoReassemblyExpected + " type=0x" + String.format("%02x", this.cryptoReassemblyBuf[0] & 0xFF));
                }
            }
        } // end while — all CRYPTO frames in this payload processed

        if (!hasCrypto) {
            // Payload has no CRYPTO frame (ACK-only or PADDING-only Initial — valid per RFC 9000)
            StringBuilder dbg = new StringBuilder("No CRYPTO frame in payload[" + parsed.payload.length + "]:");
            for (int i = 0; i < Math.min(parsed.payload.length, 16); i++) {
                dbg.append(String.format(" %02x", parsed.payload[i] & 0xFF));
            }
            logger.warn(dbg.toString());
            return false;
        }

        // Not yet complete?
        // Non-TLS mode: there is no TLS message header to determine expected length, so any
        // packet with at least one CRYPTO frame (even 0-byte) is treated as complete immediately.
        if (!this.soConfig.isSslEnabled()) {
            // Non-TLS: treat whatever we have as the complete payload
            byte[] cryptoData = new byte[this.cryptoReassemblyReceived];
            if (this.cryptoReassemblyReceived > 0) {
                System.arraycopy(this.cryptoReassemblyBuf, 0, cryptoData, 0, cryptoData.length);
            }
            return processServerInitialNoTls(cryptoData, this.pendingClientAddr, parsed);
        }
        if (this.cryptoReassemblyExpected < 0 || this.cryptoReassemblyReceived < this.cryptoReassemblyExpected) {
            return false; // wait for more fragments
        }

        // ── Reassembly complete — extract the full TLS message ─────────
        byte[] cryptoData = new byte[this.cryptoReassemblyExpected];
        System.arraycopy(this.cryptoReassemblyBuf, 0, cryptoData, 0, cryptoData.length);
        if (this.printLog) {
            logger.info("[QUIC-HS] CRYPTO reassembly complete: " + cryptoData.length + " bytes");
        }

        if (this.soConfig.isSslEnabled()) {
            return processServerInitialWithTls(cryptoData, this.pendingClientAddr);
        } else {
            return processServerInitialNoTls(cryptoData, this.pendingClientAddr, parsed);
        }
    }

    private boolean processServerInitialWithTls(byte[] clientHello, SocketAddress clientAddr) throws Exception {
        // Dump first bytes for diagnosis
        StringBuilder sb = new StringBuilder("ClientHello data[" + clientHello.length + "]: ");
        for (int i = 0; i < Math.min(clientHello.length, 16); i++) {
            sb.append(String.format("%02x ", clientHello[i] & 0xFF));
        }
        if (this.printLog) {
            logger.info("[QUIC-HS] ClientHello data[" + clientHello.length + "]: " + sb.toString().trim());
        }
        // Process ClientHello through TLS engine
        // RFC 9000 §7.3: server MUST include original_destination_connection_id and
        // initial_source_connection_id in EncryptedExtensions transport parameters.
        this.tlsEngine.setConnectionIds(this.localCid, this.originalDcid);
        if (!this.tlsEngine.processClientHello(clientHello)) {
            logger.error("Failed to process ClientHello");
            return false;
        }

        // Install handshake keys from TLS engine
        this.clientHandshakeKeys = this.tlsEngine.getClientHandshakeKeys();
        this.serverHandshakeKeys = this.tlsEngine.getServerHandshakeKeys();

        // ── Diagnostic: log TLS handshake details for debugging ───────
        if (this.printLog) {
            logger.info("[QUIC-HS] TLS negotiation: ALPN=" + this.tlsEngine.getNegotiatedAlpn() + " SNI=" + this.tlsEngine.getPeerSniHost());
        }

        // ── Send ServerHello in an Initial packet ─────────────────────
        byte[] serverHello = this.tlsEngine.getServerHelloBytes();
        byte[] cryptoFrame = QuicPacket.buildCryptoFrame(0, serverHello);
        byte[] ackFrame = QuicPacket.buildAckFrame(this.maxInitialPacketNumber.get(), 0);
        byte[] payload = concat(ackFrame, cryptoFrame);

        byte[][] sendKeys = getSendKeys(QuicAsyncHandshakeState.INITIAL);
        long pn = nextInitialPacketNumber();
        byte[] packet = QuicPacket.buildLongHeaderPacket(this.quicVersion, QuicPacket.TYPE_INITIAL,//
                this.remoteCid, this.localCid, new byte[0], pn, payload,//
                sendKeys[0], sendKeys[1], sendKeys[2], 1200);
        sendPacket(packet, clientAddr);

        // ── Send Handshake messages in Handshake packet(s) ────────────
        // Fragmentation: QUIC packets must fit within PMTU (RFC 9000 §13).
        // PMTU starts at 1200 bytes; with ~35 bytes QUIC header + 16 bytes AEAD tag,
        // max CRYPTO payload per packet ≈ 1140 bytes.
        byte[] hsBytes = this.tlsEngine.getHandshakeBytes();
        if (this.printLog) {
            logger.info("[QUIC-HS] sending Handshake " + hsBytes.length + " bytes to " + clientAddr);
        }
        byte[][] hsSendKeys = getSendKeys(QuicAsyncHandshakeState.HANDSHAKE);
        final int MAX_CRYPTO_CHUNK = 1140; // safe max CRYPTO data per QUIC packet
        int hsOffset = 0;
        while (hsOffset < hsBytes.length) {
            int chunkLen = Math.min(MAX_CRYPTO_CHUNK, hsBytes.length - hsOffset);
            byte[] chunk = new byte[chunkLen];
            System.arraycopy(hsBytes, hsOffset, chunk, 0, chunkLen);
            byte[] hsCryptoFrame = QuicPacket.buildCryptoFrame(hsOffset, chunk);
            long hsPn = nextHandshakePacketNumber();
            byte[] hsPacket = QuicPacket.buildLongHeaderPacket(this.quicVersion, QuicPacket.TYPE_HANDSHAKE,//
                    this.remoteCid, this.localCid, null, hsPn, hsCryptoFrame,//
                    hsSendKeys[0], hsSendKeys[1], hsSendKeys[2], 0);
            sendPacket(hsPacket, clientAddr);
            hsOffset += chunkLen;
        }

        this.state = QuicAsyncHandshakeState.HANDSHAKE;
        return true;
    }

    // ── 1-RTT packet building ──────────────────────────────────────────

    private boolean processServerInitialNoTls(byte[] cryptoData, SocketAddress clientAddr, QuicPacket.ParsedPacket parsed) throws Exception {
        // Non-TLS mode: echo back a simple ServerHello-like Initial + transition to ESTABLISHED
        byte[] ackFrame = QuicPacket.buildAckFrame(this.maxInitialPacketNumber.get(), 0);
        byte[] cryptoFrame = QuicPacket.buildCryptoFrame(0, new byte[0]);
        byte[] payload = concat(ackFrame, cryptoFrame);

        long pn = nextInitialPacketNumber();
        byte[] packet = QuicPacket.buildRawLongHeaderPacket(this.quicVersion, QuicPacket.TYPE_INITIAL,//
                this.remoteCid, this.localCid, new byte[0], pn, payload);
        sendPacket(packet, clientAddr);

        this.state = QuicAsyncHandshakeState.ESTABLISHED;
        return true;
    }

    // ── Packet sending ─────────────────────────────────────────────────

    /**
     * Processes a received Handshake packet (server side): verifies client Finished and transitions to ESTABLISHED.
     */
    public boolean processServerHandshake(QuicPacket.ParsedPacket parsed, SocketAddress clientAddr) throws Exception {
        if (this.state != QuicAsyncHandshakeState.HANDSHAKE) {
            return false;
        }

        updateMaxHandshakePacketNumber(parsed.packetNumber);

        // Extract CRYPTO frame (client Finished) — parseCryptoFrame already skips ACK/PADDING/CONNECTION_CLOSE
        long[] cryptoInfo = QuicPacket.parseCryptoFrame(parsed.payload, 0);
        if (cryptoInfo == null) {
            long closeCode = logHandshakeFrameTypes(parsed.payload);
            if (closeCode >= 0) {
                // Client explicitly closed the connection: mark this handshake as aborted
                this.state = QuicAsyncHandshakeState.CLOSED;
                logger.warn("Client sent CONNECTION_CLOSE (0x" + Long.toHexString(closeCode) + ") during handshake — aborting");
            }
            // ACK-only or CONNECTION_CLOSE: nothing to process, caller will clean up if aborted
            return false;
        }
        int dataOffset = (int) cryptoInfo[1];
        int dataLength = (int) cryptoInfo[2];
        byte[] clientFinished = new byte[dataLength];
        System.arraycopy(parsed.payload, dataOffset, clientFinished, 0, dataLength);

        if (this.soConfig.isSslEnabled()) {
            if (!this.tlsEngine.verifyClientFinished(clientFinished)) {
                logger.error("Client Finished verification failed");
                return false;
            }
            // Install 1-RTT application keys
            this.clientAppKeys = this.tlsEngine.getClientAppKeys();
            this.serverAppKeys = this.tlsEngine.getServerAppKeys();
        }

        // ── Send ACK for Handshake + HANDSHAKE_DONE in 1-RTT ─────────
        // ACK the client's Handshake packet
        byte[] hsAck = QuicPacket.buildAckFrame(this.maxHandshakePacketNumber.get(), 0);
        byte[][] hsSendKeys = getSendKeys(QuicAsyncHandshakeState.HANDSHAKE);
        long hsAckPn = nextHandshakePacketNumber();
        byte[] hsAckPacket = QuicPacket.buildLongHeaderPacket(this.quicVersion, QuicPacket.TYPE_HANDSHAKE,//
                this.remoteCid, this.localCid, null, hsAckPn, hsAck,//
                hsSendKeys[0], hsSendKeys[1], hsSendKeys[2], 0);
        sendPacket(hsAckPacket, clientAddr);

        // Send HANDSHAKE_DONE in a 1-RTT Short Header packet
        byte[] handshakeDone = QuicPacket.buildHandshakeDoneFrame();
        if (this.soConfig.isSslEnabled()) {
            byte[][] appSendKeys = getSendKeys(QuicAsyncHandshakeState.ESTABLISHED);
            long appPn = nextAppPacketNumber();
            byte[] appPacket = QuicPacket.buildShortHeaderPacket(this.remoteCid, appPn, handshakeDone,//
                    appSendKeys[0], appSendKeys[1], appSendKeys[2]);
            sendPacket(appPacket, clientAddr);
        } else {
            // Non-TLS: send raw short header (no encryption)
            sendRaw1RttPacket(handshakeDone, clientAddr);
        }

        this.state = QuicAsyncHandshakeState.ESTABLISHED;
        if (this.printLog) {
            logger.info("[QUIC-HS] complete (server mode), remote=" + clientAddr);
        }
        return true;
    }

    /**
     * Initiates a client-side QUIC handshake by sending an Initial packet with a TLS ClientHello CRYPTO frame.
     */
    public void initiateClientHandshake(SocketAddress serverAddr) throws Exception {
        // Client generates DCID for the server
        int cidLen = this.soConfig.getConnectionIdLength();
        this.remoteCid = new byte[cidLen];
        new SecureRandom().nextBytes(this.remoteCid);

        // Derive Initial keys from the client-chosen DCID
        deriveInitialKeys(this.remoteCid);

        if (this.soConfig.isSslEnabled()) {
            // Generate ClientHello via TLS engine
            byte[] clientHello = this.tlsEngine.generateClientHello();
            byte[] cryptoFrame = QuicPacket.buildCryptoFrame(0, clientHello);
            long pn = nextInitialPacketNumber();

            // Encrypt and send Initial packet (padded to 1200 bytes)
            byte[][] sendKeys = getSendKeys(QuicAsyncHandshakeState.INITIAL);
            byte[] packet = QuicPacket.buildLongHeaderPacket(this.quicVersion, QuicPacket.TYPE_INITIAL,//
                    this.remoteCid, this.localCid, new byte[0], pn, cryptoFrame,//
                    sendKeys[0], sendKeys[1], sendKeys[2], 1200);
            sendPacket(packet, serverAddr);
        } else {
            // Non-TLS mode: send a minimal Initial packet with empty CRYPTO
            byte[] cryptoFrame = QuicPacket.buildCryptoFrame(0, new byte[0]);
            long pn = nextInitialPacketNumber();
            byte[] packet = QuicPacket.buildRawLongHeaderPacket(this.quicVersion, QuicPacket.TYPE_INITIAL,//
                    this.remoteCid, this.localCid, new byte[0], pn, cryptoFrame);
            sendPacket(packet, serverAddr);
        }

        this.state = QuicAsyncHandshakeState.HANDSHAKE;
    }

    /**
     * Processes the server's Initial response (client side): extracts ServerHello and derives handshake keys.
     */
    public boolean processClientInitialResponse(QuicPacket.ParsedPacket parsed) throws Exception {
        if (this.state != QuicAsyncHandshakeState.HANDSHAKE || !this.clientMode) {
            return false;
        }

        // Update our remoteCid to server's SCID (now used as DCID in all our outgoing packets)
        this.remoteCid = parsed.scid != null ? parsed.scid : new byte[0];
        updateMaxInitialPacketNumber(parsed.packetNumber);

        if (!this.soConfig.isSslEnabled()) {
            // Non-TLS: server goes directly to ESTABLISHED after Initial
            this.state = QuicAsyncHandshakeState.ESTABLISHED;
            if (this.printLog) {
                logger.info("[QUIC-HS] complete (client mode, non-TLS)");
            }
            return true;
        }

        // TLS mode: extract CRYPTO frame (ServerHello) and process
        long[] cryptoInfo = QuicPacket.parseCryptoFrame(parsed.payload, 0);
        if (cryptoInfo == null) {
            logger.error("No CRYPTO frame in server Initial packet");
            return false;
        }
        int dataOffset = (int) cryptoInfo[1];
        int dataLength = (int) cryptoInfo[2];
        byte[] serverHello = new byte[dataLength];
        System.arraycopy(parsed.payload, dataOffset, serverHello, 0, dataLength);

        if (!this.tlsEngine.processServerHello(serverHello)) {
            logger.error("Failed to process ServerHello");
            return false;
        }

        // Install handshake keys from TLS engine
        this.clientHandshakeKeys = this.tlsEngine.getClientHandshakeKeys();
        this.serverHandshakeKeys = this.tlsEngine.getServerHandshakeKeys();
        if (this.printLog) {
            logger.info("[QUIC-HS] client handshake keys installed, waiting for server Handshake");
        }
        return true;
    }

    // ── Packet receiving helpers ───────────────────────────────────────

    /**
     * Processes the server's Handshake packet (client side): extracts server messages and sends client Finished.
     */
    public boolean processClientHandshakeResponse(QuicPacket.ParsedPacket parsed) throws Exception {
        if (this.state != QuicAsyncHandshakeState.HANDSHAKE || !this.clientMode) {
            return false;
        }

        updateMaxHandshakePacketNumber(parsed.packetNumber);

        if (!this.soConfig.isSslEnabled()) {
            // Non-TLS: shouldn't receive Handshake packet — ignore
            return false;
        }

        // ── CRYPTO frame reassembly for server Handshake messages (RFC 9000 §19.6) ──
        // The server sends EE+Cert+CertVerify+Finished in Handshake packets which may be
        // fragmented across multiple QUIC packets with consecutive CRYPTO frame offsets.
        int scanOffset = 0;
        boolean hasCrypto = false;
        while (true) {
            long[] cryptoInfo = QuicPacket.parseCryptoFrame(parsed.payload, scanOffset);
            if (cryptoInfo == null) {
                break;
            }
            hasCrypto = true;
            long cryptoStreamOffset = cryptoInfo[0];
            int dataOffset = (int) cryptoInfo[1];
            int dataLength = (int) cryptoInfo[2];
            scanOffset = dataOffset + dataLength;

            int fragStart = (int) cryptoStreamOffset;
            int fragEnd = fragStart + dataLength;

            if (this.hsReassemblyBuf == null) {
                int initSize = Math.max(fragEnd, 4096);
                this.hsReassemblyBuf = new byte[initSize];
                this.hsReassemblyBitmap = new boolean[initSize];
                this.hsReassemblyReceived = 0;
            }

            if (fragEnd > this.hsReassemblyBuf.length) {
                int newSize = fragEnd + 512;
                byte[] biggerBuf = new byte[newSize];
                System.arraycopy(this.hsReassemblyBuf, 0, biggerBuf, 0, this.hsReassemblyBuf.length);
                this.hsReassemblyBuf = biggerBuf;
                boolean[] biggerMap = new boolean[newSize];
                System.arraycopy(this.hsReassemblyBitmap, 0, biggerMap, 0, this.hsReassemblyBitmap.length);
                this.hsReassemblyBitmap = biggerMap;
            }

            int newBytes = 0;
            for (int i = 0; i < dataLength; i++) {
                int bpos = fragStart + i;
                if (!this.hsReassemblyBitmap[bpos]) {
                    this.hsReassemblyBitmap[bpos] = true;
                    newBytes++;
                }
            }
            System.arraycopy(parsed.payload, dataOffset, this.hsReassemblyBuf, fragStart, dataLength);
            this.hsReassemblyReceived += newBytes;

            if (this.printLog) {
                logger.info("[QUIC-HS] HS CRYPTO fragment: offset=" + cryptoStreamOffset + " len=" + dataLength + " new=" + newBytes + " received=" + this.hsReassemblyReceived);
            }
        }

        if (!hasCrypto) {
            logger.warn("No CRYPTO frame in server Handshake packet (may be ACK-only)");
            return false;
        }

        // Check if we have a complete sequence of TLS handshake messages ending with Finished (0x14)
        int expectedTotal = calcHandshakeMessagesLength(this.hsReassemblyBuf, this.hsReassemblyReceived);
        if (expectedTotal < 0 || this.hsReassemblyReceived < expectedTotal) {
            return false; // wait for more fragments
        }

        byte[] hsData = new byte[expectedTotal];
        System.arraycopy(this.hsReassemblyBuf, 0, hsData, 0, expectedTotal);
        if (this.printLog) {
            logger.info("[QUIC-HS] HS CRYPTO reassembly complete: " + hsData.length + " bytes");
        }

        if (!this.tlsEngine.processServerHandshakeMessages(hsData)) {
            logger.error("Failed to process server handshake messages");
            return false;
        }

        // Install 1-RTT application keys
        this.clientAppKeys = this.tlsEngine.getClientAppKeys();
        this.serverAppKeys = this.tlsEngine.getServerAppKeys();

        // ── Send ACK for server Handshake + client Finished ───────────
        byte[] hsAck = QuicPacket.buildAckFrame(this.maxHandshakePacketNumber.get(), 0);
        byte[] clientFinished = this.tlsEngine.getClientFinishedBytes();
        byte[] clientFinCrypto = QuicPacket.buildCryptoFrame(0, clientFinished);
        byte[] finPayload = concat(hsAck, clientFinCrypto);

        byte[][] hsSendKeys = getSendKeys(QuicAsyncHandshakeState.HANDSHAKE);
        long hsPn = nextHandshakePacketNumber();
        byte[] finPacket = QuicPacket.buildLongHeaderPacket(this.quicVersion, QuicPacket.TYPE_HANDSHAKE,//
                this.remoteCid, this.localCid, null, hsPn, finPayload,//
                hsSendKeys[0], hsSendKeys[1], hsSendKeys[2], 0);
        sendPacket(finPacket, this.remoteAddress);

        if (this.printLog) {
            logger.info("[QUIC-HS] Client Finished sent, waiting for HANDSHAKE_DONE");
        }
        return true;
    }

    /**
     * Processes a HANDSHAKE_DONE frame in a 1-RTT packet (client side); transitions handshake to ESTABLISHED.
     */
    public boolean processHandshakeDone() {
        if (this.state != QuicAsyncHandshakeState.HANDSHAKE || !this.clientMode) {
            return false;
        }
        this.state = QuicAsyncHandshakeState.ESTABLISHED;
        if (this.printLog) {
            logger.info("[QUIC-HS] complete (client mode, received HANDSHAKE_DONE)");
        }
        return true;
    }

    // ── Internal helpers ───────────────────────────────────────────────

    /**
     * Builds a 1-RTT Short Header QUIC packet: encrypted with application keys if TLS is enabled, raw otherwise.
     */
    public byte[] build1RttPacket(byte[] payload) throws Exception {
        if (this.soConfig.isSslEnabled()) {
            byte[][] sendKeys = getSendKeys(QuicAsyncHandshakeState.ESTABLISHED);
            if (sendKeys == null) {
                throw new IllegalStateException("Application keys not yet derived; handshake incomplete");
            }
            long pn = nextAppPacketNumber();
            return QuicPacket.buildShortHeaderPacket(this.remoteCid, pn, payload,//
                    sendKeys[0], sendKeys[1], sendKeys[2]);
        } else {
            return buildRaw1RttPacket(payload);
        }
    }

    /**
     * Sends a raw QUIC packet over UDP to the specified address; returns bytes sent.
     */
    int sendPacket(byte[] packet, SocketAddress target) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(packet);
        if (target != null) {
            return this.udpChannel.send(buf, target);
        } else {
            return this.udpChannel.write(buf);
        }
    }

    /**
     * Sends a raw QUIC packet over UDP to the configured remote address; returns bytes sent.
     */
    int sendPacket(byte[] packet) throws Exception {
        return sendPacket(packet, this.remoteAddress);
    }

    /**
     * Decrypts a received Long Header packet using the appropriate keys for the given level.
     * @param data the raw packet data
     * @param offset offset into the data array
     * @param parsed the pre-parsed packet header
     * @param level the encryption level ({@link #LEVEL_INITIAL} or {@link #LEVEL_HANDSHAKE})
     * @return {@code true} if decryption succeeded
     */
    public boolean decryptLongHeaderPacket(byte[] data, int offset, QuicPacket.ParsedPacket parsed, int level) {
        if (!this.soConfig.isSslEnabled()) {
            return true; // no encryption
        }
        QuicAsyncHandshakeState hsLevel = levelToState(level);
        byte[][] recvKeys = getRecvKeys(hsLevel);
        if (recvKeys == null) {
            logger.error("decryptLongHeaderPacket: recvKeys==null for level=" + hsLevel + " (keys not yet derived?) offset=" + offset);
            return false;
        }
        long largestPn = (hsLevel == QuicAsyncHandshakeState.INITIAL) ? this.maxInitialPacketNumber.get() : this.maxHandshakePacketNumber.get();
        boolean ok = QuicPacket.decryptLongHeaderPacket(data, offset, parsed, recvKeys[0], recvKeys[1], recvKeys[2], Math.max(largestPn, 0));
        if (!ok) {
            logger.error("decryptLongHeaderPacket: AEAD failed for level=" + hsLevel + " offset=" + offset + " pn=" + parsed.packetNumber);
        }
        return ok;
    }

    /**
     * Decrypts a received Short Header (1-RTT) packet.
     * @param data the raw packet data
     * @param offset offset into the data array
     * @param length length of the packet data
     * @return the decrypted parsed packet, or {@code null} if decryption fails
     */
    public QuicPacket.ParsedPacket decrypt1RttPacket(byte[] data, int offset, int length) {
        if (!this.soConfig.isSslEnabled()) {
            return QuicAsyncServerChannel.parseRawShortHeader(data, this.localCid.length);
        }
        byte[][] recvKeys = getRecvKeys(QuicAsyncHandshakeState.ESTABLISHED);
        if (recvKeys == null) {
            return null;
        }
        return QuicPacket.decryptShortHeaderPacket(data, offset, length, this.localCid.length,//
                recvKeys[0], recvKeys[1], recvKeys[2], Math.max(this.maxAppPacketNumber.get(), 0));
    }

    /** Builds a raw (unencrypted) 1-RTT short header packet for non-TLS mode. */
    private byte[] buildRaw1RttPacket(byte[] payload) {
        int pnLength = 1;
        long pn = nextAppPacketNumber();
        byte[] pnBytes = QuicPacket.encodePacketNumber(pn, pnLength);

        int totalSize = 1 + this.remoteCid.length + pnLength + payload.length;
        byte[] packet = new byte[totalSize];
        int pos = 0;
        packet[pos++] = (byte) (0x40 | (pnLength - 1));
        System.arraycopy(this.remoteCid, 0, packet, pos, this.remoteCid.length);
        pos += this.remoteCid.length;
        System.arraycopy(pnBytes, 0, packet, pos, pnLength);
        pos += pnLength;
        System.arraycopy(payload, 0, packet, pos, payload.length);
        return packet;
    }

    // ── 0-RTT data buffering and draining ────────────────────────────

    /** Sends a raw 1-RTT packet with the given payload to the specified address. */
    private void sendRaw1RttPacket(byte[] payload, SocketAddress target) throws Exception {
        byte[] packet = buildRaw1RttPacket(payload);
        sendPacket(packet, target);
    }

    /**
     * Builds a {@link QuicInitConfigData} from peer transport parameters extracted during TLS handshake; falls back to local soConfig defaults.
     */
    public QuicInitConfigData buildInitConfigData(SocketAddress localAddr, SocketAddress remoteAddr) {
        QuicInitConfigData data = new QuicInitConfigData();
        data.setLocalAddr(localAddr);
        data.setRemoteAddr(remoteAddr);

        if (this.tlsEngine != null) {
            byte[] peerParams = this.tlsEngine.getPeerTransportParams();
            if (peerParams != null && peerParams.length > 0) {
                // Parse QUIC transport parameters (RFC 9000 §18): each param is varint(id) + varint(len) + value
                int pos = 0;
                while (pos < peerParams.length) {
                    long[] idResult = QuicVarInt.decode(peerParams, pos);
                    int paramId = (int) idResult[0];
                    pos += (int) idResult[1];

                    long[] lenResult = QuicVarInt.decode(peerParams, pos);
                    int paramLen = (int) lenResult[0];
                    pos += (int) lenResult[1];

                    // Decode the value as a varint (most transport params are varint-encoded integers)
                    long paramValue = 0;
                    if (paramLen > 0 && pos < peerParams.length) {
                        long[] valResult = QuicVarInt.decode(peerParams, pos);
                        paramValue = valResult[0];
                    }
                    pos += paramLen;

                    switch (paramId) {
                        case PARAM_INITIAL_MAX_DATA:
                            data.setPeerMaxData(paramValue);
                            break;
                        case PARAM_INITIAL_MAX_STREAMS_BIDI:
                            data.setPeerMaxStreamsBidi(paramValue);
                            break;
                        case PARAM_INITIAL_MAX_STREAMS_UNI:
                            data.setPeerMaxStreamsUni(paramValue);
                            break;
                        case PARAM_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL:
                            // Peer's bidi local = limits our sends on peer-initiated bidi streams
                            data.setPeerStreamMaxDataBidiLocal(paramValue);
                            break;
                        case PARAM_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE:
                            // Peer's bidi remote = limits our sends on our-initiated bidi streams
                            data.setPeerStreamMaxDataBidiRemote(paramValue);
                            break;
                        case PARAM_INITIAL_MAX_STREAM_DATA_UNI:
                            data.setPeerStreamMaxDataUni(paramValue);
                            break;
                        case PARAM_MAX_DATAGRAM_FRAME_SIZE:
                            data.setDatagramMaxDataSize(paramValue);
                            break;
                        default:
                            // Unknown or unsupported transport parameter — skip
                            break;
                    }
                }
                return data;
            }
        }

        // Fallback: use local soConfig defaults when no peer params available
        data.setPeerMaxData(this.soConfig.getTpInitialFrameMaxData());
        data.setPeerMaxStreamsBidi(this.soConfig.getTpInitialMaxStreamsBidi());
        data.setPeerMaxStreamsUni(this.soConfig.getTpInitialMaxStreamsUni());
        data.setPeerStreamMaxDataBidiLocal(this.soConfig.getTpInitialMaxStreamDataBidiLocal());
        data.setPeerStreamMaxDataBidiRemote(this.soConfig.getTpInitialMaxStreamDataBidiRemote());
        data.setPeerStreamMaxDataUni(this.soConfig.getTpInitialMaxStreamDataUni());
        data.setDatagramMaxDataSize(this.soConfig.getTpInitialDatagramFrameMaxData());
        return data;
    }

    /**
     * Buffers a raw 0-RTT packet (max 64) received before handshake completes, to be delivered post-handshake.
     */
    public void buffer0RttData(byte[] rawData) {
        synchronized (this.bufferedRtt0Data) {
            if (this.bufferedRtt0Data.size() < 64) { // limit buffer to prevent memory issues
                this.bufferedRtt0Data.add(rawData);
            } else {
                if (this.printLog) {
                    logger.info("[QUIC-HS] 0-RTT buffer full, discarding packet");
                }
            }
        }
    }

    /**
     * Drains any buffered 0-RTT data. Returns the list of buffered packets and clears the buffer.
     */
    public List<byte[]> drain0RttData() {
        synchronized (this.bufferedRtt0Data) {
            if (this.bufferedRtt0Data.isEmpty()) {
                return new ArrayList<byte[]>();
            }
            List<byte[]> result = new ArrayList<byte[]>(this.bufferedRtt0Data);
            this.bufferedRtt0Data.clear();
            return result;
        }
    }

    /** Returns true if there are buffered 0-RTT packets. */
    public boolean has0RttData() {
        return !this.bufferedRtt0Data.isEmpty();
    }

    // ── Key Update (RFC 9001 §6, RFC 8446 §7.2) ───────────────────────

    /** Returns the number of currently buffered 0-RTT packets (non-destructive). */
    public int getBuffered0RttCount() {
        synchronized (this.bufferedRtt0Data) {
            return this.bufferedRtt0Data.size();
        }
    }

    /** Anti-replay check for 0-RTT packet numbers (RFC 9001 §8.4). */
    public boolean checkAndMarkRtt0Pn(long pn) {
        synchronized (this.seenRtt0PacketNumbers) {
            return this.seenRtt0PacketNumbers.add(pn);
        }
    }

    /**
     * Rotates the READ keys (for decrypting incoming packets) per RFC 9001 §6 using HKDF-Expand-Label with "quic ku" label.
     */
    public void rotateReadKeys() throws Exception {
        // Determine which keys are our read keys
        byte[][] currentRecvKeys = getRecvKeys(QuicAsyncHandshakeState.ESTABLISHED);
        if (currentRecvKeys == null || currentRecvKeys.length < 3) {
            throw new IllegalStateException("No application read keys available for rotation");
        }
        // Derive new traffic secret using HKDF-Expand-Label with "quic ku" label
        // RFC 9001 §6.1: uses "quic ku" label for QUIC key update
        byte[] currentSecret = currentRecvKeys[0]; // key as secret (simplified)
        byte[] newKey = QuicCrypto.hkdfExpandLabel(currentSecret, "quic ku", new byte[0], 16, "quic ");
        byte[] newIv = QuicCrypto.hkdfExpandLabel(currentSecret, "quic iv", new byte[0], 12, "quic ");
        // HP key remains the same after key update (RFC 9001 §6.6)
        byte[] hp = currentRecvKeys[2];
        byte[][] newRecvKeys = new byte[][] { newKey, newIv, hp };
        // Install new read keys
        if (this.clientMode) {
            this.serverAppKeys = newRecvKeys;
        } else {
            this.clientAppKeys = newRecvKeys;
        }
        if (this.printLog) {
            logger.info("[QUIC-HS] read keys rotated (generation " + (this.keyUpdateGeneration + 1) + ")");
        }
    }

    /**
     * Rotates the WRITE keys (for encrypting outgoing packets) when initiating or responding to a key update (RFC 9001 §6).
     */
    public void rotateWriteKeys() throws Exception {
        byte[][] currentSendKeys = getSendKeys(QuicAsyncHandshakeState.ESTABLISHED);
        if (currentSendKeys == null || currentSendKeys.length < 3) {
            throw new IllegalStateException("No application write keys available for rotation");
        }
        byte[] currentSecret = currentSendKeys[0];
        byte[] newKey = QuicCrypto.hkdfExpandLabel(currentSecret, "quic ku", new byte[0], 16, "quic ");
        byte[] newIv = QuicCrypto.hkdfExpandLabel(currentSecret, "quic iv", new byte[0], 12, "quic ");
        byte[] hp = currentSendKeys[2];
        byte[][] newSendKeys = new byte[][] { newKey, newIv, hp };
        if (this.clientMode) {
            this.clientAppKeys = newSendKeys;
        } else {
            this.serverAppKeys = newSendKeys;
        }
        this.keyUpdateGeneration++;
        if (this.printLog) {
            logger.info("[QUIC-HS] write keys rotated (generation " + this.keyUpdateGeneration + ")");
        }
    }

    /** Returns the current key update generation number. */
    public int getKeyUpdateGeneration() {
        return this.keyUpdateGeneration;
    }

    /**
     * Scans Handshake payload frames for diagnostic purposes; returns CONNECTION_CLOSE error code or -1 if none.
     */
    private long logHandshakeFrameTypes(byte[] payload) {
        StringBuilder sb = new StringBuilder("Handshake packet has no CRYPTO frame. Frame types:");
        boolean hasConnectionClose = false;
        long closeErrorCode = -1;
        String closeReason = "";
        int pos = 0;
        while (pos < payload.length) {
            try {
                long[] typeResult = QuicVarInt.decode(payload, pos);
                int ft = (int) typeResult[0];
                pos += (int) typeResult[1];
                sb.append(" 0x").append(Integer.toHexString(ft));
                if (ft == QuicFrameType.PADDING || ft == QuicFrameType.PING) {
                    continue;
                } else if (ft == QuicFrameType.ACK || ft == QuicFrameType.ACK_ECN) {
                    long[] tmp = QuicVarInt.decode(payload, pos);
                    pos += (int) tmp[1]; // Largest Acked
                    tmp = QuicVarInt.decode(payload, pos);
                    pos += (int) tmp[1]; // ACK Delay
                    tmp = QuicVarInt.decode(payload, pos);
                    long rangeCount = tmp[0];
                    pos += (int) tmp[1];
                    tmp = QuicVarInt.decode(payload, pos);
                    pos += (int) tmp[1]; // First ACK Range
                    for (long i = 0; i < rangeCount; i++) {
                        tmp = QuicVarInt.decode(payload, pos);
                        pos += (int) tmp[1];
                        tmp = QuicVarInt.decode(payload, pos);
                        pos += (int) tmp[1];
                    }
                    if (ft == QuicFrameType.ACK_ECN) {
                        for (int i = 0; i < 3; i++) {
                            tmp = QuicVarInt.decode(payload, pos);
                            pos += (int) tmp[1];
                        }
                    }
                } else if (ft == QuicFrameType.CONNECTION_CLOSE || ft == QuicFrameType.CONNECTION_CLOSE_APP) {
                    hasConnectionClose = true;
                    long[] tmp = QuicVarInt.decode(payload, pos);
                    closeErrorCode = tmp[0];
                    pos += (int) tmp[1];
                    long triggerFrameType = -1;
                    if (ft == QuicFrameType.CONNECTION_CLOSE) {
                        tmp = QuicVarInt.decode(payload, pos);
                        triggerFrameType = tmp[0];
                        pos += (int) tmp[1]; // triggering frame type
                    }
                    tmp = QuicVarInt.decode(payload, pos);
                    int reasonLen = (int) tmp[0];
                    pos += (int) tmp[1];
                    if (reasonLen > 0 && pos + reasonLen <= payload.length) {
                        closeReason = new String(payload, pos, reasonLen, java.nio.charset.StandardCharsets.UTF_8);
                        pos += reasonLen;
                    }
                    sb.append("(errorCode=0x").append(Long.toHexString(closeErrorCode));
                    if (triggerFrameType >= 0) {
                        sb.append(" triggerFrame=0x").append(Long.toHexString(triggerFrameType));
                    }
                    if (!closeReason.isEmpty()) {
                        sb.append(" reason='").append(closeReason).append("'");
                    }
                    sb.append(")");
                } else {
                    break; // unknown frame type — cannot determine length, stop
                }
            } catch (Exception e) {
                sb.append(" [parse-error:" + e.getMessage() + "]");
                break;
            }
        }
        if (hasConnectionClose) {
            // QUIC CRYPTO_ERROR codes are 0x100-0x1ff; lower codes are QUIC transport errors
            if (closeErrorCode >= 0x100L && closeErrorCode <= 0x1ffL) {
                long tlsAlertCode = closeErrorCode - 0x100L;
                String alertName = tlsAlertName(tlsAlertCode);
                logger.warn(sb + " | TLS CRYPTO_ERROR alert=" + tlsAlertCode + " (" + alertName + ")");
            } else {
                String errName = quicTransportErrorName(closeErrorCode);
                logger.warn(sb + " | QUIC transport error 0x" + Long.toHexString(closeErrorCode) + " (" + errName + ")");
            }
            return closeErrorCode;
        } else {
            // ACK-only Handshake packets are perfectly normal (RFC 9000); log at DEBUG only
            logger.debug(sb.toString());
            return -1;
        }
    }

    /** Handshake state machine */
    private enum QuicAsyncHandshakeState {
        INITIAL,
        HANDSHAKE,
        ESTABLISHED,
        CLOSED
    }
}
