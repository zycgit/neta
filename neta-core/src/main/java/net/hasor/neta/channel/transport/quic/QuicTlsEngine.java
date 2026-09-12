/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.KeyAgreement;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.codec.ssl.SslCertConfig;
/**
 * Minimal pure-Java TLS 1.3 engine for QUIC, following RFC 8446 and RFC 9001, supporting both
 * client and server roles and providing X25519/P-256 key exchange plus AES-128-GCM encryption.
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicTlsEngine {
    private static final Logger logger = Logger.getLogger(QuicTlsEngine.class);
    // ── TLS Constants ──────────────────────────────────────────────────
    private static final int TLS_VERSION_12 = 0x0303; // legacy
    private static final int TLS_VERSION_13 = 0x0304;
    // Cipher suite
    private static final int TLS_AES_128_GCM_SHA256 = 0x1301;
    // Handshake types
    private static final int HT_CLIENT_HELLO         = 0x01;
    private static final int HT_SERVER_HELLO         = 0x02;
    private static final int HT_ENCRYPTED_EXTENSIONS = 0x08;
    private static final int HT_CERTIFICATE          = 0x0B;
    private static final int HT_CERTIFICATE_VERIFY   = 0x0F;
    private static final int HT_FINISHED             = 0x14;
    // Extension types
    private static final int EXT_SERVER_NAME           = 0x0000;
    private static final int EXT_SUPPORTED_GROUPS      = 0x000A;
    private static final int EXT_SIGNATURE_ALGORITHMS  = 0x000D;
    private static final int EXT_ALPN                  = 0x0010;
    private static final int EXT_SUPPORTED_VERSIONS    = 0x002B;
    private static final int EXT_KEY_SHARE             = 0x0033;
    private static final int EXT_QUIC_TRANSPORT_PARAMS = 0x0039;
    // Named groups
    private static final int GROUP_X25519    = 0x001d;
    private static final int GROUP_SECP256R1 = 0x0017;

    // ── Pure-Java X25519 constants (RFC 7748 §5) — no JDK version restriction ──
    /** p = 2^255 - 19, the finite-field prime used by Curve25519. */
    private static final BigInteger P25519 = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    /** a24 = (486662 - 2) / 4 = 121665, a constant used by the Montgomery curve. */
    private static final BigInteger A24    = BigInteger.valueOf(121665);
    /** Base-point u coordinate of Curve25519. */
    private static final BigInteger BASE_U = BigInteger.valueOf(9);

    // Signature algorithms
    private static final int SIG_RSA_PSS_RSAE_SHA256    = 0x0804;
    private static final int SIG_ECDSA_SECP256R1_SHA256 = 0x0403;

    // ── State ──────────────────────────────────────────────────────────

    private final X509Certificate[] certChain;
    private final PrivateKey        privateKey;
    private final QuicSoConfig      localTransportParams;
    private final SslCertConfig     sslCertConfig;
    private final SoChannel<?>      channel;
    private final QuicVersion       quicVersion;
    // ── Client mode state ──────────────────────────────────────────────
    private final boolean clientMode;
    // TLS handshake state
    private byte[]       clientRandom;
    private byte[]       serverRandom;
    private byte[]       clientSessionId; // legacy session_id echo
    private byte[]       peerKeyShareP256;   // client's P-256 public key (65 bytes uncompressed)
    private byte[]       peerKeyShareX25519; // client's X25519 public key (32 bytes, little-endian)
    private int          selectedGroup = GROUP_SECP256R1; // negotiated key exchange group
    private byte[]       x25519EphemeralPrivKey; // our X25519 scalar (clamped, 32 bytes)
    private byte[]       x25519EphemeralPubKey;  // our X25519 public key in wire format (32 bytes LE)
    private byte[]       peerQuicTransportParams;
    private List<String> peerAlpnProtocols; // parsed from ClientHello EXT_ALPN
    private String       negotiatedAlpn;    // result of ALPN negotiation
    private String       peerSniHost;       // server_name from ClientHello SNI extension
    // Generated during handshake
    private KeyPair serverEphemeralKeyPair;
    private byte[]  sharedSecret;
    private byte[]  masterSecret;   // TLS master_secret, derived separately from sharedSecret.
    // Transcript hash (incremental SHA-256)
    private MessageDigest transcriptHash;
    // Derived keys
    private byte[] clientHandshakeTrafficSecret;
    private byte[] serverHandshakeTrafficSecret;
    private byte[] clientAppTrafficSecret;
    private byte[] serverAppTrafficSecret;
    // QUIC packet protection keys: [key, iv, hp]
    private byte[][] clientHandshakeKeys;
    private byte[][] serverHandshakeKeys;
    private byte[][] clientAppKeys;
    private byte[][] serverAppKeys;
    // Generated TLS messages
    private byte[] serverHelloMsg;
    // CID transport parameters required by RFC 9000 §7.3.
    private byte[]            sourceConnectionId;       // The server or client localCid, corresponding to initial_source_connection_id.
    private byte[]            originalDestinationCid;   // Original DCID from the client's first Initial, used only by the server.
    private byte[]            encryptedExtensionsMsg;
    private byte[]            certificateMsg;
    private byte[]            certificateVerifyMsg;
    private byte[]            serverFinishedMsg;
    private byte[]            clientHelloMsg;           // client mode: the generated ClientHello
    private byte[]            clientFinishedMsg;        // client mode: the generated client Finished
    private X509Certificate[] peerCertChain;  // server's certificate chain received during handshake

    /**
     * Creates a TLS 1.3/QUIC engine for the given role, where clientMode=true means client and
     * false means server.
     */
    public QuicTlsEngine(SslCertConfig certConfig, QuicSoConfig soConfig, SoChannel<?> channel, boolean clientMode) {
        this.sslCertConfig = (certConfig != null) ? certConfig : new SslCertConfig();
        this.certChain = this.sslCertConfig.getCertChainDirect();
        this.privateKey = this.sslCertConfig.getPrivateKeyDirect();
        this.localTransportParams = (soConfig != null) ? soConfig : new QuicSoConfig();
        this.channel = channel;
        this.quicVersion = this.localTransportParams.getQuicVersion();
        this.clientMode = clientMode;
    }

    /**
     * Creates a TLS 1.3/QUIC engine in server mode for compatibility with older call sites.
     */
    public QuicTlsEngine(SslCertConfig certConfig, QuicSoConfig soConfig, SoChannel<?> channel) {
        this(certConfig, soConfig, channel, false);
    }

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Encodes an EC public key as an uncompressed P-256 point, i.e. 0x04 || x || y, totaling 65 bytes.
     */
    static byte[] encodeP256PublicKey(ECPublicKey pubKey) {
        ECPoint w = pubKey.getW();
        byte[] x = toFixedLengthUnsigned(w.getAffineX(), 32);
        byte[] y = toFixedLengthUnsigned(w.getAffineY(), 32);

        byte[] result = new byte[65];
        result[0] = 0x04;
        System.arraycopy(x, 0, result, 1, 32);
        System.arraycopy(y, 0, result, 33, 32);
        return result;
    }

    private static byte[] toFixedLengthUnsigned(BigInteger value, int length) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == length) {
            return bytes;
        } else if (bytes.length > length) {
            // Remove the leading 0.
            return Arrays.copyOfRange(bytes, bytes.length - length, bytes.length);
        } else {
            // Pad with 0 at the front.
            byte[] padded = new byte[length];
            System.arraycopy(bytes, 0, padded, length - bytes.length, bytes.length);
            return padded;
        }
    }

    // ── Getters for derived keys ───────────────────────────────────────

    private static byte[] wrapHandshakeMessage(int type, byte[] body) {
        byte[] msg = new byte[4 + body.length];
        msg[0] = (byte) type;
        msg[1] = (byte) ((body.length >> 16) & 0xFF);
        msg[2] = (byte) ((body.length >> 8) & 0xFF);
        msg[3] = (byte) (body.length & 0xFF);
        System.arraycopy(body, 0, msg, 4, body.length);
        return msg;
    }

    /**
     * Generates a new clamped X25519 private scalar of 32 bytes according to RFC 7748 §5.
     */
    private static byte[] x25519GenScalar() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        k[0] &= 0xF8; // Clear bits 0 through 2.
        k[31] &= 0x7F; // Clear bit 255.
        k[31] |= 0x40; // Set bit 254.
        return k;
    }

    /**
     * Performs scalar x BASE_U on Curve25519 to compute an X25519 public key, output as a 32-byte
     * little-endian wire-format value.
     */
    private static byte[] x25519KeyGen(byte[] scalar) {
        BigInteger result = x25519MontgomeryLadder(decodeLe32(scalar), BASE_U);
        return encodeLe32(result);
    }

    /**
     * Computes the X25519 shared secret from the local clamped scalar and the peer's 32-byte
     * little-endian wire-format public key, in pure Java and compatible with Java 8+.
     */
    private static byte[] x25519SharedSecret(byte[] scalar, byte[] peerKeyWire) {
        BigInteger u = decodeLe32(peerKeyWire);
        BigInteger result = x25519MontgomeryLadder(decodeLe32(scalar), u);
        return encodeLe32(result);
    }

    /**
     * Montgomery ladder implementation for scalar multiplication on Curve25519, see RFC 7748 §5;
     * all operations are performed in GF(p), where p=2^255-19.
     */
    private static BigInteger x25519MontgomeryLadder(BigInteger k, BigInteger u) {
        BigInteger x1 = u;
        BigInteger x2 = BigInteger.ONE;
        BigInteger z2 = BigInteger.ZERO;
        BigInteger x3 = u;
        BigInteger z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int kt = k.testBit(t) ? 1 : 0;
            swap ^= kt;
            if (swap != 0) {
                BigInteger tmp;
                tmp = x2;
                x2 = x3;
                x3 = tmp;
                tmp = z2;
                z2 = z3;
                z3 = tmp;
            }
            swap = kt;
            BigInteger A = x2.add(z2).mod(P25519);
            BigInteger AA = A.multiply(A).mod(P25519);
            BigInteger B = x2.subtract(z2).mod(P25519);
            BigInteger BB = B.multiply(B).mod(P25519);
            BigInteger E = AA.subtract(BB).mod(P25519);
            BigInteger C = x3.add(z3).mod(P25519);
            BigInteger D = x3.subtract(z3).mod(P25519);
            BigInteger DA = D.multiply(A).mod(P25519);
            BigInteger CB = C.multiply(B).mod(P25519);
            BigInteger sum = DA.add(CB).mod(P25519);
            BigInteger diff = DA.subtract(CB).mod(P25519);
            x3 = sum.multiply(sum).mod(P25519);
            z3 = x1.multiply(diff.multiply(diff).mod(P25519)).mod(P25519);
            x2 = AA.multiply(BB).mod(P25519);
            z2 = E.multiply(AA.add(A24.multiply(E).mod(P25519)).mod(P25519)).mod(P25519);
        }
        if (swap != 0) {
            BigInteger tmp;
            tmp = x2;
            x2 = x3;
            x3 = tmp;
            tmp = z2;
            z2 = z3;
            z3 = tmp;
        }
        // result = x2 / z2 mod p; inversion is computed using the Fermat inverse z2^(p-2) mod p.
        BigInteger inv = z2.modPow(P25519.subtract(BigInteger.valueOf(2)), P25519);
        return x2.multiply(inv).mod(P25519);
    }

    /**
     * Decodes a 32-byte little-endian value as an unsigned BigInteger.
     */
    private static BigInteger decodeLe32(byte[] b) {
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++) {
            be[i] = b[31 - i];
        }
        return new BigInteger(1, be);
    }

    /**
     * Encodes a field element as 32-byte little-endian data.
     */
    private static byte[] encodeLe32(BigInteger v) {
        v = v.mod(P25519);
        byte[] be = v.toByteArray(); // Big-endian, possibly with a leading 0x00 sign byte.
        // Normalize to exactly 32 bytes of big-endian representation.
        byte[] be32 = new byte[32];
        if (be.length >= 32) {
            System.arraycopy(be, be.length - 32, be32, 0, 32);
        } else {
            System.arraycopy(be, 0, be32, 32 - be.length, be.length);
        }
        // Reverse again into little-endian form.
        byte[] le = new byte[32];
        for (int i = 0; i < 32; i++) {
            le[i] = be32[31 - i];
        }
        return le;
    }

    /**
     * Processes a TLS ClientHello from a QUIC CRYPTO frame; returns true if parsing succeeds and
     * server response messages have been generated.
     */
    public boolean processClientHello(byte[] clientHello) throws Exception {
        this.transcriptHash = MessageDigest.getInstance("SHA-256");

        // Parse ClientHello.
        if (!parseClientHello(clientHello)) {
            return false;
        }

        // Add ClientHello to the transcript.
        transcriptHash.update(clientHello);

        // Generate the server ephemeral key pair and compute the shared secret.
        // Prefer X25519 when available because it is pure Java and Java 8+ compatible; otherwise fall back to P-256.
        if (peerKeyShareX25519 != null) {
            this.x25519EphemeralPrivKey = x25519GenScalar();
            this.x25519EphemeralPubKey = x25519KeyGen(x25519EphemeralPrivKey);
            this.sharedSecret = x25519SharedSecret(x25519EphemeralPrivKey, peerKeyShareX25519);
            this.selectedGroup = GROUP_X25519;
        } else {
            // Fall back to P-256.
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
            this.serverEphemeralKeyPair = kpg.generateKeyPair();
            this.sharedSecret = computeECDHSharedSecret(serverEphemeralKeyPair.getPrivate(), peerKeyShareP256);
            this.selectedGroup = GROUP_SECP256R1;
        }

        // Generate ServerHello.
        this.serverRandom = new byte[32];
        new SecureRandom().nextBytes(serverRandom);
        this.serverHelloMsg = buildServerHello();

        // Add ServerHello to the transcript.
        transcriptHash.update(serverHelloMsg);

        // Derive Handshake-level keys.
        deriveHandshakeSecrets();

        // Generate EncryptedExtensions.
        this.encryptedExtensionsMsg = buildEncryptedExtensions();
        transcriptHash.update(encryptedExtensionsMsg);

        // Generate Certificate.
        this.certificateMsg = buildCertificate();
        transcriptHash.update(certificateMsg);

        // Generate CertificateVerify.
        byte[] transcriptSoFar = ((MessageDigest) transcriptHash.clone()).digest();
        this.certificateVerifyMsg = buildCertificateVerify(transcriptSoFar);
        transcriptHash.update(certificateVerifyMsg);

        // Generate Finished.
        byte[] transcriptBeforeFinished = ((MessageDigest) transcriptHash.clone()).digest();
        this.serverFinishedMsg = buildFinished(serverHandshakeTrafficSecret, transcriptBeforeFinished);
        transcriptHash.update(serverFinishedMsg);

        // Derive application-level keys.
        deriveApplicationSecrets();

        return true;
    }

    /**
     * Verifies the client's Finished message using the derived client_handshake_traffic_secret;
     * returns true on success.
     */
    public boolean verifyClientFinished(byte[] clientFinished) throws Exception {
        if (clientFinished == null || clientFinished.length < 4) {
            return false;
        }

        // Extract verify_data from the client Finished message.
        int verifyLen = ((clientFinished[1] & 0xFF) << 16) | ((clientFinished[2] & 0xFF) << 8) | (clientFinished[3] & 0xFF);
        if (clientFinished.length < 4 + verifyLen) {
            return false;
        }
        byte[] clientVerifyData = Arrays.copyOfRange(clientFinished, 4, 4 + verifyLen);

        // Compute the expected verify_data.
        byte[] transcriptBeforeClientFinished = ((MessageDigest) transcriptHash.clone()).digest();
        byte[] finishedKey = QuicCrypto.tlsExpandLabel(clientHandshakeTrafficSecret, "finished", new byte[0], 32);

        javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
        hmac.init(new javax.crypto.spec.SecretKeySpec(finishedKey, "HmacSHA256"));
        byte[] expectedVerifyData = hmac.doFinal(transcriptBeforeClientFinished);

        if (!MessageDigest.isEqual(clientVerifyData, expectedVerifyData)) {
            return false;
        }

        // Add the client Finished message to the transcript.
        transcriptHash.update(clientFinished);
        return true;
    }

    /**
     * Returns TLS message bytes from Initial through ServerHello, used to wrap the CRYPTO frame
     * carried in an Initial packet.
     */
    public byte[] getServerHelloBytes() {
        return serverHelloMsg;
    }

    /**
     * Returns the EncryptedExtensions message bytes, primarily for diagnostic logging.
     */
    public byte[] getEncryptedExtensionsMsg() {
        return encryptedExtensionsMsg;
    }

    // ── ClientHello Parsing ────────────────────────────────────────────

    /**
     * Returns the Certificate message bytes, primarily for diagnostic logging.
     */
    public byte[] getCertificateMsg() {
        return certificateMsg;
    }

    // ── TLS Message Building ───────────────────────────────────────────

    /**
     * Returns the CertificateVerify message bytes, primarily for diagnostic logging.
     */
    public byte[] getCertificateVerifyMsg() {
        return certificateVerifyMsg;
    }

    /**
     * Returns the server Finished message bytes, primarily for diagnostic logging.
     */
    public byte[] getServerFinishedMsg() {
        return serverFinishedMsg;
    }

    /**
     * Returns the concatenated encrypted handshake messages, i.e. EE + Cert + CertVerify + Finished,
     * for building CRYPTO frames.
     */
    public byte[] getHandshakeBytes() {
        int totalLen = encryptedExtensionsMsg.length + certificateMsg.length + certificateVerifyMsg.length + serverFinishedMsg.length;
        byte[] result = new byte[totalLen];
        int pos = 0;
        System.arraycopy(encryptedExtensionsMsg, 0, result, pos, encryptedExtensionsMsg.length);
        pos += encryptedExtensionsMsg.length;
        System.arraycopy(certificateMsg, 0, result, pos, certificateMsg.length);
        pos += certificateMsg.length;
        System.arraycopy(certificateVerifyMsg, 0, result, pos, certificateVerifyMsg.length);
        pos += certificateVerifyMsg.length;
        System.arraycopy(serverFinishedMsg, 0, result, pos, serverFinishedMsg.length);
        return result;
    }

    public byte[][] getClientHandshakeKeys() {
        return clientHandshakeKeys;
    }

    public byte[][] getServerHandshakeKeys() {
        return serverHandshakeKeys;
    }

    public byte[][] getClientAppKeys() {
        return clientAppKeys;
    }

    public byte[][] getServerAppKeys() {
        return serverAppKeys;
    }

    /**
     * Returns the {@code client_application_traffic_secret_0} established after the TLS handshake
     * (TLS 1.3 §7.1). This is the initial secret used as the seed for RFC 9001 §6.1 Key Update
     * HKDF chains; {@code null} until {@link #deriveApplicationSecrets()} has completed.
     */
    public byte[] getClientAppTrafficSecret() {
        return clientAppTrafficSecret;
    }

    /**
     * Returns the {@code server_application_traffic_secret_0} established after the TLS handshake
     * (TLS 1.3 §7.1). This is the initial secret used as the seed for RFC 9001 §6.1 Key Update
     * HKDF chains; {@code null} until {@link #deriveApplicationSecrets()} has completed.
     */
    public byte[] getServerAppTrafficSecret() {
        return serverAppTrafficSecret;
    }

    /**
     * Returns the raw QUIC transport parameters received from the peer from the
     * quic_transport_parameters TLS extension; may be null.
     */
    public byte[] getPeerTransportParams() {
        return peerQuicTransportParams;
    }

    /**
     * Returns the ALPN protocol negotiated during the TLS handshake; available after
     * processClientHello completes.
     */
    public String getNegotiatedAlpn() {
        return negotiatedAlpn;
    }

    /**
     * Sets the SCID and original DCID that need to be written into QUIC transport parameters
     * according to RFC 9000 §7.3.
     */
    public void setConnectionIds(byte[] sourceConnectionId, byte[] originalDestinationCid) {
        this.sourceConnectionId = sourceConnectionId;
        this.originalDestinationCid = originalDestinationCid;
    }

    // ── Key Schedule (RFC 8446 §7.1) ───────────────────────────────────

    /**
     * Returns the SNI server_name from ClientHello; available after processClientHello completes,
     * or null if it was not present.
     */
    public String getPeerSniHost() {
        return peerSniHost;
    }

    private boolean parseClientHello(byte[] msg) {
        if (msg.length < 4) {
            return false;
        }

        int type = msg[0] & 0xFF;
        if (type != HT_CLIENT_HELLO) {
            StringBuilder dbg = new StringBuilder("parseClientHello: bad type=0x" + Integer.toHexString(type) + " len=" + msg.length + " bytes:");
            for (int ii = 0; ii < Math.min(msg.length, 32); ii++) {
                dbg.append(String.format(" %02x", msg[ii] & 0xFF));
            }
            logger.error(dbg.toString());
            return false;
        }

        int length = ((msg[1] & 0xFF) << 16) | ((msg[2] & 0xFF) << 8) | (msg[3] & 0xFF);
        int pos = 4;
        int end = 4 + length;
        if (end > msg.length) {
            return false;
        }

        // legacy_version (0x0303).
        pos += 2;

        // random, fixed at 32 bytes.
        this.clientRandom = new byte[32];
        System.arraycopy(msg, pos, clientRandom, 0, 32);
        pos += 32;

        // legacy_session_id。
        int sidLen = msg[pos++] & 0xFF;
        this.clientSessionId = new byte[sidLen];
        if (sidLen > 0) {
            System.arraycopy(msg, pos, clientSessionId, 0, sidLen);
        }
        pos += sidLen;

        // cipher_suites
        int csLen = ((msg[pos] & 0xFF) << 8) | (msg[pos + 1] & 0xFF);
        pos += 2;
        boolean foundAes128 = false;
        for (int i = 0; i < csLen; i += 2) {
            int cs = ((msg[pos + i] & 0xFF) << 8) | (msg[pos + i + 1] & 0xFF);
            if (cs == TLS_AES_128_GCM_SHA256) {
                foundAes128 = true;
                break;
            }
        }
        pos += csLen;
        if (!foundAes128) {
            return false;
        }

        // legacy_compression_methods
        int compLen = msg[pos++] & 0xFF;
        pos += compLen;

        // extensions
        if (pos + 2 > end) {
            return false;
        }
        int extTotalLen = ((msg[pos] & 0xFF) << 8) | (msg[pos + 1] & 0xFF);
        pos += 2;
        int extEnd = pos + extTotalLen;

        boolean foundTls13 = false;
        boolean foundKeyShare = false;

        while (pos + 4 <= extEnd) {
            int extType = ((msg[pos] & 0xFF) << 8) | (msg[pos + 1] & 0xFF);
            int extLen = ((msg[pos + 2] & 0xFF) << 8) | (msg[pos + 3] & 0xFF);
            pos += 4;
            int extDataEnd = pos + extLen;

            switch (extType) {
                case EXT_SUPPORTED_VERSIONS:
                    // supported_versions list
                    if (pos < extDataEnd) {
                        int versionsLen = msg[pos++] & 0xFF;
                        for (int i = 0; i < versionsLen; i += 2) {
                            int ver = ((msg[pos + i] & 0xFF) << 8) | (msg[pos + i + 1] & 0xFF);
                            if (ver == TLS_VERSION_13) {
                                foundTls13 = true;
                                break;
                            }
                        }
                    }
                    break;
                case EXT_KEY_SHARE:
                    // key_share_entry list
                    if (pos + 2 <= extDataEnd) {
                        int ksLen = ((msg[pos] & 0xFF) << 8) | (msg[pos + 1] & 0xFF);
                        int ksPos = pos + 2;
                        int ksEnd = ksPos + ksLen;
                        while (ksPos + 4 <= ksEnd) {
                            int group = ((msg[ksPos] & 0xFF) << 8) | (msg[ksPos + 1] & 0xFF);
                            int keyLen = ((msg[ksPos + 2] & 0xFF) << 8) | (msg[ksPos + 3] & 0xFF);
                            ksPos += 4;
                            if (group == GROUP_X25519 && keyLen == 32) {
                                peerKeyShareX25519 = new byte[32];
                                System.arraycopy(msg, ksPos, peerKeyShareX25519, 0, 32);
                                foundKeyShare = true;
                            } else if (group == GROUP_SECP256R1 && keyLen == 65) {
                                peerKeyShareP256 = new byte[65];
                                System.arraycopy(msg, ksPos, peerKeyShareP256, 0, 65);
                                foundKeyShare = true;
                            }
                            ksPos += keyLen;
                        }
                    }
                    break;
                case EXT_QUIC_TRANSPORT_PARAMS:
                    peerQuicTransportParams = new byte[extLen];
                    System.arraycopy(msg, pos, peerQuicTransportParams, 0, extLen);
                    break;
                case EXT_ALPN:
                    // Parse ALPN protocol list from ClientHello (RFC 7301)
                    if (pos + 2 <= extDataEnd) {
                        int alpnListLen = ((msg[pos] & 0xFF) << 8) | (msg[pos + 1] & 0xFF);
                        int alpnPos = pos + 2;
                        int alpnEnd = alpnPos + alpnListLen;
                        this.peerAlpnProtocols = new ArrayList<>();
                        while (alpnPos < alpnEnd && alpnPos < extDataEnd) {
                            int protoLen = msg[alpnPos++] & 0xFF;
                            if (alpnPos + protoLen <= alpnEnd) {
                                this.peerAlpnProtocols.add(new String(msg, alpnPos, protoLen, StandardCharsets.US_ASCII));
                            }
                            alpnPos += protoLen;
                        }
                    }
                    break;
                case EXT_SERVER_NAME:
                    // Parse SNI extension (RFC 6066 §3): ServerNameList -> ServerName(type=0, host_name)
                    if (pos + 2 <= extDataEnd) {
                        int sniListLen = ((msg[pos] & 0xFF) << 8) | (msg[pos + 1] & 0xFF);
                        int sniPos = pos + 2;
                        int sniEnd = sniPos + sniListLen;
                        while (sniPos + 3 <= sniEnd && sniPos + 3 <= extDataEnd) {
                            int nameType = msg[sniPos++] & 0xFF;
                            int nameLen = ((msg[sniPos] & 0xFF) << 8) | (msg[sniPos + 1] & 0xFF);
                            sniPos += 2;
                            if (nameType == 0 && nameLen > 0 && sniPos + nameLen <= sniEnd) {
                                // host_name type (0x00)
                                this.peerSniHost = new String(msg, sniPos, nameLen, StandardCharsets.US_ASCII);
                            }
                            sniPos += nameLen;
                        }
                    }
                    break;
                default:
                    break;
            }
            pos = extDataEnd;
        }

        return foundTls13 && foundKeyShare;
    }

    // ── ECDHE P-256 ────────────────────────────────────────────────────

    private byte[] buildServerHello() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);

        // legacy_version
        out.write(TLS_VERSION_12 >> 8);
        out.write(TLS_VERSION_12 & 0xFF);

        // random
        out.write(serverRandom);

        // legacy_session_id_echo
        out.write(clientSessionId.length);
        out.write(clientSessionId);

        // cipher_suite
        out.write(TLS_AES_128_GCM_SHA256 >> 8);
        out.write(TLS_AES_128_GCM_SHA256 & 0xFF);

        // legacy_compression_method
        out.write(0x00);

        // extensions
        byte[] exts = buildServerHelloExtensions();
        out.write(exts.length >> 8);
        out.write(exts.length & 0xFF);
        out.write(exts);

        return wrapHandshakeMessage(HT_SERVER_HELLO, out.toByteArray());
    }

    private byte[] buildServerHelloExtensions() throws Exception {
        ByteArrayOutputStream exts = new ByteArrayOutputStream(128);

        // supported_versions extension
        byte[] svExt = new byte[] { (byte) (EXT_SUPPORTED_VERSIONS >> 8), (byte) (EXT_SUPPORTED_VERSIONS & 0xFF), 0x00, 0x02, // length = 2
                (byte) (TLS_VERSION_13 >> 8), (byte) (TLS_VERSION_13 & 0xFF) };
        exts.write(svExt);

        // key_share extension (server's public key, group matches negotiated algorithm)
        byte[] serverPubKeyBytes;
        if (this.selectedGroup == GROUP_X25519) {
            serverPubKeyBytes = x25519EphemeralPubKey; // already in 32-byte wire format
        } else {
            serverPubKeyBytes = encodeP256PublicKey((ECPublicKey) serverEphemeralKeyPair.getPublic());
        }
        ByteArrayOutputStream ksData = new ByteArrayOutputStream();
        ksData.write(this.selectedGroup >> 8);
        ksData.write(this.selectedGroup & 0xFF);
        ksData.write(serverPubKeyBytes.length >> 8);
        ksData.write(serverPubKeyBytes.length & 0xFF);
        ksData.write(serverPubKeyBytes);

        byte[] ksExtData = ksData.toByteArray();
        exts.write(EXT_KEY_SHARE >> 8);
        exts.write(EXT_KEY_SHARE & 0xFF);
        exts.write(ksExtData.length >> 8);
        exts.write(ksExtData.length & 0xFF);
        exts.write(ksExtData);

        return exts.toByteArray();
    }

    private byte[] buildEncryptedExtensions() throws Exception {
        ByteArrayOutputStream exts = new ByteArrayOutputStream(256);

        // Perform ALPN negotiation through the unified SslCertConfig logic.
        String selectedAlpn = null;
        if (this.sslCertConfig != null && this.peerAlpnProtocols != null && !this.peerAlpnProtocols.isEmpty()) {
            selectedAlpn = this.sslCertConfig.negotiateAlpn(this.channel, this.peerAlpnProtocols);
        }
        if (selectedAlpn == null) {
            // Fall back to the default protocol from configuration.
            selectedAlpn = (this.sslCertConfig != null) ? this.sslCertConfig.resolveDefaultProtocol() : null;
        }
        if (selectedAlpn == null && this.peerAlpnProtocols != null && !this.peerAlpnProtocols.isEmpty()) {
            // Final fallback: accept the first protocol offered by the peer.
            selectedAlpn = this.peerAlpnProtocols.get(0);
        }
        this.negotiatedAlpn = selectedAlpn;

        // Encode ALPN extension only when a protocol was negotiated
        if (selectedAlpn != null) {
            byte[] alpnProto = selectedAlpn.getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream alpnData = new ByteArrayOutputStream();
            int alpnListLen = 1 + alpnProto.length;
            alpnData.write(alpnListLen >> 8);
            alpnData.write(alpnListLen & 0xFF);
            alpnData.write(alpnProto.length);
            alpnData.write(alpnProto);
            byte[] alpnBytes = alpnData.toByteArray();

            exts.write(EXT_ALPN >> 8);
            exts.write(EXT_ALPN & 0xFF);
            exts.write(alpnBytes.length >> 8);
            exts.write(alpnBytes.length & 0xFF);
            exts.write(alpnBytes);
        }

        // QUIC Transport Parameters extension.
        byte[] tpBytes = encodeTransportParams(localTransportParams);
        exts.write(EXT_QUIC_TRANSPORT_PARAMS >> 8);
        exts.write(EXT_QUIC_TRANSPORT_PARAMS & 0xFF);
        exts.write(tpBytes.length >> 8);
        exts.write(tpBytes.length & 0xFF);
        exts.write(tpBytes);

        // Wrap it again as an EncryptedExtensions handshake message.
        byte[] extList = exts.toByteArray();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(extList.length >> 8);
        body.write(extList.length & 0xFF);
        body.write(extList);

        return wrapHandshakeMessage(HT_ENCRYPTED_EXTENSIONS, body.toByteArray());
    }

    // Client-side TLS 1.3 handshake.

    private byte[] buildCertificate() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream(4096);

        // certificate_request_context, fixed to empty on the server side.
        body.write(0x00);

        // certificate_list
        ByteArrayOutputStream certList = new ByteArrayOutputStream(4096);
        for (X509Certificate cert : certChain) {
            byte[] certDer = cert.getEncoded();
            certList.write((certDer.length >> 16) & 0xFF);
            certList.write((certDer.length >> 8) & 0xFF);
            certList.write(certDer.length & 0xFF);
            certList.write(certDer);
            // Extensions attached to each certificate entry; left empty here.
            certList.write(0x00);
            certList.write(0x00);
        }

        byte[] certListBytes = certList.toByteArray();
        body.write((certListBytes.length >> 16) & 0xFF);
        body.write((certListBytes.length >> 8) & 0xFF);
        body.write(certListBytes.length & 0xFF);
        body.write(certListBytes);

        return wrapHandshakeMessage(HT_CERTIFICATE, body.toByteArray());
    }

    private byte[] buildCertificateVerify(byte[] transcriptHash) throws Exception {
        // Build the content to be signed according to RFC 8446 §4.4.3.
        ByteArrayOutputStream sigInput = new ByteArrayOutputStream(130);
        // 64 leading 0x20 (space) bytes.
        byte[] padding = new byte[64];
        Arrays.fill(padding, (byte) 0x20);
        sigInput.write(padding);
        // Context string.
        sigInput.write("TLS 1.3, server CertificateVerify".getBytes(StandardCharsets.US_ASCII));
        // Separator byte 0x00.
        sigInput.write(0x00);
        // transcript hash。
        sigInput.write(transcriptHash);

        byte[] contentToSign = sigInput.toByteArray();

        // Sign with the server private key.
        int sigAlgorithm;
        byte[] signature;
        String keyAlg = privateKey.getAlgorithm();

        if ("RSA".equalsIgnoreCase(keyAlg)) {
            sigAlgorithm = SIG_RSA_PSS_RSAE_SHA256;
            signature = signRsaPss(contentToSign);
        } else if ("EC".equalsIgnoreCase(keyAlg)) {
            sigAlgorithm = SIG_ECDSA_SECP256R1_SHA256;
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(privateKey);
            sig.update(contentToSign);
            signature = sig.sign();
        } else {
            throw new IllegalStateException("Unsupported key algorithm: " + keyAlg);
        }

        // Assemble the CertificateVerify message.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(sigAlgorithm >> 8);
        body.write(sigAlgorithm & 0xFF);
        body.write(signature.length >> 8);
        body.write(signature.length & 0xFF);
        body.write(signature);

        return wrapHandshakeMessage(HT_CERTIFICATE_VERIFY, body.toByteArray());
    }

    private byte[] signRsaPss(byte[] data) throws Exception {
        // Prefer RSASSA-PSS first (Java 11+).
        try {
            Signature sig = Signature.getInstance("RSASSA-PSS");
            AlgorithmParameterSpec pssParams = new java.security.spec.PSSParameterSpec("SHA-256", "MGF1", new java.security.spec.MGF1ParameterSpec("SHA-256"), 32, 1);
            sig.setParameter(pssParams);
            sig.initSign(privateKey);
            sig.update(data);
            return sig.sign();
        } catch (NoSuchAlgorithmException e) {
            // Fall back to the BouncyCastle provider if present.
            Provider bcProvider = Security.getProvider("BC");
            if (bcProvider != null) {
                try {
                    Signature sig = Signature.getInstance("SHA256withRSAandMGF1", bcProvider);
                    sig.initSign(privateKey);
                    sig.update(data);
                    return sig.sign();
                } catch (Exception ignored) {
                }
            }
            // TLS 1.3 requires RSA-PSS; SHA256withRSA using PKCS#1 v1.5 is not allowed, see RFC 8446 §4.2.3.
            throw new NoSuchAlgorithmException("RSA-PSS signature not available. TLS 1.3 requires RSASSA-PSS; " + "please use Java 11+ or add BouncyCastle provider.");
        }
    }

    private byte[] buildFinished(byte[] baseSecret, byte[] transcriptHash) throws Exception {
        byte[] finishedKey = QuicCrypto.tlsExpandLabel(baseSecret, "finished", new byte[0], 32);

        javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
        hmac.init(new javax.crypto.spec.SecretKeySpec(finishedKey, "HmacSHA256"));
        byte[] verifyData = hmac.doFinal(transcriptHash);

        return wrapHandshakeMessage(HT_FINISHED, verifyData);
    }

    private void deriveHandshakeSecrets() throws Exception {
        // early_secret = HKDF-Extract(salt=0, PSK=0)
        // RFC 8446 §7.1 defines both salt and IKM as all-zero strings of Hash.length when PSK is not used; here that means 32 bytes.
        byte[] zeroKey = new byte[32];
        byte[] earlySecret = QuicCrypto.hkdfExtract(new byte[32], zeroKey);

        // Derive-Secret(early_secret, "derived", "")
        byte[] emptyHash = QuicCrypto.sha256(new byte[0]);
        byte[] derivedSecret = QuicCrypto.deriveSecret(earlySecret, "derived", emptyHash);

        // handshake_secret = HKDF-Extract(derived_secret, shared_secret)
        byte[] handshakeSecret = QuicCrypto.hkdfExtract(derivedSecret, sharedSecret);

        // Transcript hash up through ServerHello; both ClientHello and ServerHello are already included.
        byte[] chShHash = ((MessageDigest) transcriptHash.clone()).digest();

        // client_handshake_traffic_secret
        clientHandshakeTrafficSecret = QuicCrypto.deriveSecret(handshakeSecret, "c hs traffic", chShHash);

        // server_handshake_traffic_secret
        serverHandshakeTrafficSecret = QuicCrypto.deriveSecret(handshakeSecret, "s hs traffic", chShHash);

        // Derive QUIC packet protection keys; different versions use different labels: v1 uses quic key/iv/hp, while v2 uses quicv2 key/iv/hp.
        clientHandshakeKeys = QuicCrypto.derivePacketKeys(clientHandshakeTrafficSecret, this.quicVersion);
        serverHandshakeKeys = QuicCrypto.derivePacketKeys(serverHandshakeTrafficSecret, this.quicVersion);

        // Save the intermediate secret for continued derivation in the application phase.
        byte[] derivedFromHandshake = QuicCrypto.deriveSecret(handshakeSecret, "derived", emptyHash);
        // master_secret = HKDF-Extract(derived_from_handshake, 0)
        byte[] masterSecret = QuicCrypto.hkdfExtract(derivedFromHandshake, zeroKey);

        // Save it for deriving application-phase keys after Finished.
        this.masterSecret = masterSecret;
    }

    private void deriveApplicationSecrets() throws Exception {
        byte[] masterSecret = this.masterSecret;

        // Transcript hash after the server Finished message.
        byte[] serverFinishedHash = ((MessageDigest) transcriptHash.clone()).digest();

        // client_application_traffic_secret_0
        clientAppTrafficSecret = QuicCrypto.deriveSecret(masterSecret, "c ap traffic", serverFinishedHash);

        // server_application_traffic_secret_0
        serverAppTrafficSecret = QuicCrypto.deriveSecret(masterSecret, "s ap traffic", serverFinishedHash);

        // Continue deriving QUIC packet protection keys for the application phase.
        clientAppKeys = QuicCrypto.derivePacketKeys(clientAppTrafficSecret, this.quicVersion);
        serverAppKeys = QuicCrypto.derivePacketKeys(serverAppTrafficSecret, this.quicVersion);
    }

    /**
     * Generates and returns a TLS ClientHello for QUIC client mode, including X25519 key share,
     * ALPN, SNI, and transport parameters.
     */
    public byte[] generateClientHello() throws Exception {
        this.transcriptHash = MessageDigest.getInstance("SHA-256");

        // Generate the client ephemeral X25519 key pair, in pure Java and runnable on Java 8+.
        this.x25519EphemeralPrivKey = x25519GenScalar();
        this.x25519EphemeralPubKey = x25519KeyGen(x25519EphemeralPrivKey);
        this.selectedGroup = GROUP_X25519;

        // Generate client random.
        this.clientRandom = new byte[32];
        new SecureRandom().nextBytes(clientRandom);

        // Generate a legacy session_id as a 32-byte random value for the middlebox compatibility described in RFC 8446 §4.1.2.
        this.clientSessionId = new byte[32];
        new SecureRandom().nextBytes(clientSessionId);

        this.clientHelloMsg = buildClientHelloMessage();

        // Add ClientHello into the transcript.
        transcriptHash.update(clientHelloMsg);
        return clientHelloMsg;
    }

    // Client-side message construction, private implementation.

    /**
     * Returns the previously generated ClientHello message bytes; available after calling
     * generateClientHello().
     */
    public byte[] getClientHelloBytes() {
        return clientHelloMsg;
    }

    /**
     * Processes a TLS ServerHello from a QUIC Initial packet, extracting key_share and deriving
     * Handshake-level keys.
     */
    public boolean processServerHello(byte[] serverHello) throws Exception {
        if (serverHello == null || serverHello.length < 4) {
            return false;
        }

        int type = serverHello[0] & 0xFF;
        if (type != HT_SERVER_HELLO) {
            return false;
        }

        int length = ((serverHello[1] & 0xFF) << 16) | ((serverHello[2] & 0xFF) << 8) | (serverHello[3] & 0xFF);
        int pos = 4;
        int end = 4 + length;
        if (end > serverHello.length) {
            return false;
        }

        // legacy_version, fixed to 0x0303.
        pos += 2;

        // server_random, 32 bytes long.
        this.serverRandom = new byte[32];
        System.arraycopy(serverHello, pos, serverRandom, 0, 32);
        pos += 32;

        // legacy_session_id_echo
        int sidLen = serverHello[pos++] & 0xFF;
        pos += sidLen;

        // cipher_suite (must be TLS_AES_128_GCM_SHA256)
        int cipherSuite = ((serverHello[pos] & 0xFF) << 8) | (serverHello[pos + 1] & 0xFF);
        pos += 2;
        if (cipherSuite != TLS_AES_128_GCM_SHA256) {
            return false;
        }

        // legacy_compression_method
        pos += 1;

        // extensions
        if (pos + 2 > end) {
            return false;
        }
        int extTotalLen = ((serverHello[pos] & 0xFF) << 8) | (serverHello[pos + 1] & 0xFF);
        pos += 2;
        int extEnd = pos + extTotalLen;

        boolean foundTls13 = false;
        boolean foundKeyShare = false;

        while (pos + 4 <= extEnd) {
            int extType = ((serverHello[pos] & 0xFF) << 8) | (serverHello[pos + 1] & 0xFF);
            int extLen = ((serverHello[pos + 2] & 0xFF) << 8) | (serverHello[pos + 3] & 0xFF);
            pos += 4;
            int extDataEnd = pos + extLen;

            switch (extType) {
                case EXT_SUPPORTED_VERSIONS:
                    // Server's selected version (2 bytes, no list length prefix)
                    if (pos + 2 <= extDataEnd) {
                        int ver = ((serverHello[pos] & 0xFF) << 8) | (serverHello[pos + 1] & 0xFF);
                        if (ver == TLS_VERSION_13) {
                            foundTls13 = true;
                        }
                    }
                    break;

                case EXT_KEY_SHARE:
                    // Server's key_share (single entry, no list length prefix)
                    if (pos + 4 <= extDataEnd) {
                        int group = ((serverHello[pos] & 0xFF) << 8) | (serverHello[pos + 1] & 0xFF);
                        int keyLen = ((serverHello[pos + 2] & 0xFF) << 8) | (serverHello[pos + 3] & 0xFF);
                        if (group == GROUP_X25519 && keyLen == 32 && pos + 4 + keyLen <= extDataEnd) {
                            peerKeyShareX25519 = new byte[32];
                            System.arraycopy(serverHello, pos + 4, peerKeyShareX25519, 0, 32);
                            this.selectedGroup = GROUP_X25519;
                            foundKeyShare = true;
                        } else if (group == GROUP_SECP256R1 && keyLen == 65 && pos + 4 + keyLen <= extDataEnd) {
                            peerKeyShareP256 = new byte[65];
                            System.arraycopy(serverHello, pos + 4, peerKeyShareP256, 0, 65);
                            this.selectedGroup = GROUP_SECP256R1;
                            foundKeyShare = true;
                        }
                    }
                    break;

                default:
                    break;
            }
            pos = extDataEnd;
        }

        if (!foundTls13 || !foundKeyShare) {
            return false;
        }

        // Add ServerHello into the transcript.
        transcriptHash.update(serverHello);

        // Compute the shared secret, i.e. the key exchange result of the local ephemeral private key and the server public key.
        if (this.selectedGroup == GROUP_X25519) {
            this.sharedSecret = x25519SharedSecret(x25519EphemeralPrivKey, peerKeyShareX25519);
        } else {
            this.sharedSecret = computeECDHSharedSecret(serverEphemeralKeyPair.getPrivate(), peerKeyShareP256);
        }

        // Derive Handshake-level keys.
        deriveHandshakeSecrets();
        return true;
    }

    // Client-side message parsing, private implementation.

    /**
     * Processes concatenated server handshake messages, i.e. EE, Cert, CertVerify, and Finished,
     * and derives application-phase keys after validation succeeds.
     */
    public boolean processServerHandshakeMessages(byte[] handshakeData) throws Exception {
        int pos = 0;

        // ── 1. Parse EncryptedExtensions ──────────────────────────────
        if (pos + 4 > handshakeData.length) {
            return false;
        }
        int eeType = handshakeData[pos] & 0xFF;
        if (eeType != HT_ENCRYPTED_EXTENSIONS) {
            return false;
        }
        int eeLen = ((handshakeData[pos + 1] & 0xFF) << 16) | ((handshakeData[pos + 2] & 0xFF) << 8) | (handshakeData[pos + 3] & 0xFF);
        byte[] eeMsg = new byte[4 + eeLen];
        System.arraycopy(handshakeData, pos, eeMsg, 0, eeMsg.length);
        pos += eeMsg.length;

        // Parse the extension list inside EncryptedExtensions.
        parseEncryptedExtensions(eeMsg);
        transcriptHash.update(eeMsg);

        // ── 2. Parse Certificate ──────────────────────────────────────
        if (pos + 4 > handshakeData.length) {
            return false;
        }
        int certType = handshakeData[pos] & 0xFF;
        if (certType != HT_CERTIFICATE) {
            return false;
        }
        int certLen = ((handshakeData[pos + 1] & 0xFF) << 16) | ((handshakeData[pos + 2] & 0xFF) << 8) | (handshakeData[pos + 3] & 0xFF);
        byte[] certMsg = new byte[4 + certLen];
        System.arraycopy(handshakeData, pos, certMsg, 0, certMsg.length);
        pos += certMsg.length;

        // Parse and store the server certificate chain.
        this.peerCertChain = parseCertificateMessage(certMsg);
        if (this.peerCertChain == null || this.peerCertChain.length == 0) {
            return false;
        }
        transcriptHash.update(certMsg);

        // ── 3. Parse and verify CertificateVerify ─────────────────────
        if (pos + 4 > handshakeData.length) {
            return false;
        }
        int cvType = handshakeData[pos] & 0xFF;
        if (cvType != HT_CERTIFICATE_VERIFY) {
            return false;
        }
        int cvLen = ((handshakeData[pos + 1] & 0xFF) << 16) | ((handshakeData[pos + 2] & 0xFF) << 8) | (handshakeData[pos + 3] & 0xFF);
        byte[] cvMsg = new byte[4 + cvLen];
        System.arraycopy(handshakeData, pos, cvMsg, 0, cvMsg.length);
        pos += cvMsg.length;

        // Verify CertificateVerify.
        byte[] transcriptBeforeCv = ((MessageDigest) transcriptHash.clone()).digest();
        if (!verifyCertificateVerify(cvMsg, transcriptBeforeCv, this.peerCertChain[0])) {
            return false;
        }
        transcriptHash.update(cvMsg);

        // ── 4. Parse and verify server Finished ───────────────────────
        if (pos + 4 > handshakeData.length) {
            return false;
        }
        int finType = handshakeData[pos] & 0xFF;
        if (finType != HT_FINISHED) {
            return false;
        }
        int finLen = ((handshakeData[pos + 1] & 0xFF) << 16) | ((handshakeData[pos + 2] & 0xFF) << 8) | (handshakeData[pos + 3] & 0xFF);
        byte[] finMsg = new byte[4 + finLen];
        System.arraycopy(handshakeData, pos, finMsg, 0, finMsg.length);

        // Verify the server Finished message.
        byte[] transcriptBeforeFin = ((MessageDigest) transcriptHash.clone()).digest();
        if (!verifyFinished(finMsg, serverHandshakeTrafficSecret, transcriptBeforeFin)) {
            return false;
        }
        transcriptHash.update(finMsg);

        // Derive application-phase keys.
        deriveApplicationSecrets();

        // Generate the client Finished message.
        byte[] transcriptForClientFin = ((MessageDigest) transcriptHash.clone()).digest();
        this.clientFinishedMsg = buildFinished(clientHandshakeTrafficSecret, transcriptForClientFin);
        transcriptHash.update(clientFinishedMsg);

        return true;
    }

    /**
     * Returns the client Finished message bytes to be sent to the server; available after
     * processServerHandshakeMessages completes.
     */
    public byte[] getClientFinishedBytes() {
        return clientFinishedMsg;
    }

    /**
     * Returns the server certificate chain received during the handshake; available after
     * processServerHandshakeMessages completes.
     */
    public X509Certificate[] getPeerCertChain() {
        return peerCertChain;
    }

    /**
     * Returns whether the current TLS engine is operating in client mode.
     */
    public boolean isClientMode() {
        return clientMode;
    }

    private byte[] buildClientHelloMessage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);

        // legacy_version (0x0303 for TLS 1.2 compat)
        out.write(TLS_VERSION_12 >> 8);
        out.write(TLS_VERSION_12 & 0xFF);

        // random
        out.write(clientRandom);

        // legacy_session_id
        out.write(clientSessionId.length);
        out.write(clientSessionId);

        // cipher_suites: TLS_AES_128_GCM_SHA256
        out.write(0x00);
        out.write(0x02); // length = 2
        out.write(TLS_AES_128_GCM_SHA256 >> 8);
        out.write(TLS_AES_128_GCM_SHA256 & 0xFF);

        // legacy_compression_methods: null
        out.write(0x01); // length = 1
        out.write(0x00); // null compression

        // extensions
        byte[] exts = buildClientHelloExtensions();
        out.write(exts.length >> 8);
        out.write(exts.length & 0xFF);
        out.write(exts);

        return wrapHandshakeMessage(HT_CLIENT_HELLO, out.toByteArray());
    }

    // QUIC transport parameter encoding.

    private byte[] buildClientHelloExtensions() throws Exception {
        ByteArrayOutputStream exts = new ByteArrayOutputStream(512);

        // 1. SNI (server_name) extension
        String sniHost = (this.sslCertConfig != null) ? this.sslCertConfig.getSniHostName() : null;
        if (sniHost != null && !sniHost.isEmpty()) {
            byte[] hostBytes = sniHost.getBytes(StandardCharsets.US_ASCII);
            // ServerNameList structure: list_length(2) + type(1) + name_length(2) + name.
            int sniListLen = 1 + 2 + hostBytes.length;
            int sniExtLen = 2 + sniListLen;
            exts.write(EXT_SERVER_NAME >> 8);
            exts.write(EXT_SERVER_NAME & 0xFF);
            exts.write(sniExtLen >> 8);
            exts.write(sniExtLen & 0xFF);
            exts.write(sniListLen >> 8);
            exts.write(sniListLen & 0xFF);
            exts.write(0x00); // host_name type
            exts.write(hostBytes.length >> 8);
            exts.write(hostBytes.length & 0xFF);
            exts.write(hostBytes);
            this.peerSniHost = sniHost; // Cache the locally sent SNI for later lookup.
        }

        // 2. supported_versions extension, using the client format with a length-prefixed list.
        exts.write(EXT_SUPPORTED_VERSIONS >> 8);
        exts.write(EXT_SUPPORTED_VERSIONS & 0xFF);
        exts.write(0x00);
        exts.write(0x03); // ext length = 3
        exts.write(0x02); // list length = 2
        exts.write(TLS_VERSION_13 >> 8);
        exts.write(TLS_VERSION_13 & 0xFF);

        // 3. supported_groups extension, preferring X25519 with P-256 as fallback.
        exts.write(EXT_SUPPORTED_GROUPS >> 8);
        exts.write(EXT_SUPPORTED_GROUPS & 0xFF);
        exts.write(0x00);
        exts.write(0x06); // ext length = 6
        exts.write(0x00);
        exts.write(0x04); // list length = 4 (two 2-byte groups)
        exts.write(GROUP_X25519 >> 8);
        exts.write(GROUP_X25519 & 0xFF);
        exts.write(GROUP_SECP256R1 >> 8);
        exts.write(GROUP_SECP256R1 & 0xFF);

        // 4. signature_algorithms extension.
        byte[] sigAlgs = new byte[] { (byte) (EXT_SIGNATURE_ALGORITHMS >> 8), (byte) (EXT_SIGNATURE_ALGORITHMS & 0xFF), 0x00, 0x06, // ext length = 6
                0x00, 0x04, // list length = 4
                (byte) (SIG_RSA_PSS_RSAE_SHA256 >> 8), (byte) (SIG_RSA_PSS_RSAE_SHA256 & 0xFF), (byte) (SIG_ECDSA_SECP256R1_SHA256 >> 8), (byte) (SIG_ECDSA_SECP256R1_SHA256 & 0xFF) };
        exts.write(sigAlgs);

        // 5. key_share extension carrying the client X25519 public key, implemented in pure Java and compatible with Java 8+.
        byte[] clientPubKeyBytes = x25519EphemeralPubKey; // Already in 32-byte wire format.
        ByteArrayOutputStream ksEntry = new ByteArrayOutputStream();
        ksEntry.write(GROUP_X25519 >> 8);
        ksEntry.write(GROUP_X25519 & 0xFF);
        ksEntry.write(clientPubKeyBytes.length >> 8);
        ksEntry.write(clientPubKeyBytes.length & 0xFF);
        ksEntry.write(clientPubKeyBytes);
        byte[] ksEntryBytes = ksEntry.toByteArray();

        int ksExtLen = 2 + ksEntryBytes.length; // client_shares list length prefix.
        exts.write(EXT_KEY_SHARE >> 8);
        exts.write(EXT_KEY_SHARE & 0xFF);
        exts.write(ksExtLen >> 8);
        exts.write(ksExtLen & 0xFF);
        exts.write(ksEntryBytes.length >> 8);
        exts.write(ksEntryBytes.length & 0xFF);
        exts.write(ksEntryBytes);

        // 6. ALPN extension, written only when a protocol list is configured.
        String[] alpnProtocols = (this.sslCertConfig != null) ? this.sslCertConfig.getAppProtocol() : null;
        if (alpnProtocols != null && alpnProtocols.length > 0) {
            ByteArrayOutputStream alpnList = new ByteArrayOutputStream();
            for (String proto : alpnProtocols) {
                byte[] protoBytes = proto.getBytes(StandardCharsets.US_ASCII);
                alpnList.write(protoBytes.length);
                alpnList.write(protoBytes);
            }
            byte[] alpnListBytes = alpnList.toByteArray();

            exts.write(EXT_ALPN >> 8);
            exts.write(EXT_ALPN & 0xFF);
            int alpnExtLen = 2 + alpnListBytes.length;
            exts.write(alpnExtLen >> 8);
            exts.write(alpnExtLen & 0xFF);
            exts.write(alpnListBytes.length >> 8);
            exts.write(alpnListBytes.length & 0xFF);
            exts.write(alpnListBytes);
        }

        // 7. QUIC transport parameters extension.
        byte[] tpBytes = encodeTransportParams(localTransportParams);
        exts.write(EXT_QUIC_TRANSPORT_PARAMS >> 8);
        exts.write(EXT_QUIC_TRANSPORT_PARAMS & 0xFF);
        exts.write(tpBytes.length >> 8);
        exts.write(tpBytes.length & 0xFF);
        exts.write(tpBytes);

        return exts.toByteArray();
    }

    // Pure-Java X25519 (RFC 7748 §5), without JDK version restrictions.

    private void parseEncryptedExtensions(byte[] eeMsg) {
        // eeMsg structure: type(1) + length(3) + body.
        int pos = 4;
        if (pos + 2 > eeMsg.length) {
            return;
        }
        int extListLen = ((eeMsg[pos] & 0xFF) << 8) | (eeMsg[pos + 1] & 0xFF);
        pos += 2;
        int extEnd = pos + extListLen;

        while (pos + 4 <= extEnd && pos + 4 <= eeMsg.length) {
            int extType = ((eeMsg[pos] & 0xFF) << 8) | (eeMsg[pos + 1] & 0xFF);
            int extLen = ((eeMsg[pos + 2] & 0xFF) << 8) | (eeMsg[pos + 3] & 0xFF);
            pos += 4;
            int extDataEnd = pos + extLen;

            switch (extType) {
                case EXT_ALPN:
                    // The ALPN selected by the server returns only a single protocol.
                    if (pos + 2 <= extDataEnd) {
                        int alpnListLen = ((eeMsg[pos] & 0xFF) << 8) | (eeMsg[pos + 1] & 0xFF);
                        int alpnPos = pos + 2;
                        if (alpnPos < extDataEnd && alpnPos < pos + 2 + alpnListLen) {
                            int protoLen = eeMsg[alpnPos++] & 0xFF;
                            if (alpnPos + protoLen <= extDataEnd) {
                                this.negotiatedAlpn = new String(eeMsg, alpnPos, protoLen, StandardCharsets.US_ASCII);
                            }
                        }
                    }
                    break;

                case EXT_QUIC_TRANSPORT_PARAMS:
                    peerQuicTransportParams = new byte[extLen];
                    System.arraycopy(eeMsg, pos, peerQuicTransportParams, 0, extLen);
                    break;

                default:
                    break;
            }
            pos = extDataEnd;
        }
    }

    private X509Certificate[] parseCertificateMessage(byte[] certMsg) throws Exception {
        // certMsg structure: type(1) + length(3) + body.
        int pos = 4;
        if (pos >= certMsg.length) {
            return null;
        }

        // certificate_request_context length.
        int ctxLen = certMsg[pos++] & 0xFF;
        pos += ctxLen;

        // certificate_list length, occupying 3 bytes.
        if (pos + 3 > certMsg.length) {
            return null;
        }
        int certListLen = ((certMsg[pos] & 0xFF) << 16) | ((certMsg[pos + 1] & 0xFF) << 8) | (certMsg[pos + 2] & 0xFF);
        pos += 3;
        int certListEnd = pos + certListLen;

        List<X509Certificate> certs = new ArrayList<>();
        CertificateFactory cf = CertificateFactory.getInstance("X.509");

        while (pos + 3 <= certListEnd && pos + 3 <= certMsg.length) {
            int certDerLen = ((certMsg[pos] & 0xFF) << 16) | ((certMsg[pos + 1] & 0xFF) << 8) | (certMsg[pos + 2] & 0xFF);
            pos += 3;
            if (pos + certDerLen > certMsg.length) {
                break;
            }
            byte[] certDer = new byte[certDerLen];
            System.arraycopy(certMsg, pos, certDer, 0, certDerLen);
            pos += certDerLen;

            X509Certificate cert = (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(certDer));
            certs.add(cert);

            // Skip extensions attached after each certificate entry, formatted as 2-byte length + data.
            if (pos + 2 <= certListEnd) {
                int certExtLen = ((certMsg[pos] & 0xFF) << 8) | (certMsg[pos + 1] & 0xFF);
                pos += 2 + certExtLen;
            }
        }
        return certs.toArray(new X509Certificate[0]);
    }

    private boolean verifyCertificateVerify(byte[] cvMsg, byte[] transcriptHash, X509Certificate serverCert) throws Exception {
        // cvMsg structure: type(1) + length(3) + sigAlg(2) + sigLen(2) + signature.
        int pos = 4;
        if (pos + 4 > cvMsg.length) {
            return false;
        }

        int sigAlgorithm = ((cvMsg[pos] & 0xFF) << 8) | (cvMsg[pos + 1] & 0xFF);
        pos += 2;
        int sigLen = ((cvMsg[pos] & 0xFF) << 8) | (cvMsg[pos + 1] & 0xFF);
        pos += 2;
        if (pos + sigLen > cvMsg.length) {
            return false;
        }
        byte[] signature = new byte[sigLen];
        System.arraycopy(cvMsg, pos, signature, 0, sigLen);

        // Rebuild the signed content according to RFC 8446 §4.4.3.
        ByteArrayOutputStream sigInput = new ByteArrayOutputStream(130);
        byte[] padding = new byte[64];
        Arrays.fill(padding, (byte) 0x20);
        sigInput.write(padding);
        sigInput.write("TLS 1.3, server CertificateVerify".getBytes(StandardCharsets.US_ASCII));
        sigInput.write(0x00);
        sigInput.write(transcriptHash);
        byte[] contentToVerify = sigInput.toByteArray();

        // Verify the signature using the server public key.
        PublicKey serverPubKey = serverCert.getPublicKey();
        if (sigAlgorithm == SIG_RSA_PSS_RSAE_SHA256) {
            return verifyRsaPss(contentToVerify, signature, serverPubKey);
        } else if (sigAlgorithm == SIG_ECDSA_SECP256R1_SHA256) {
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initVerify(serverPubKey);
            sig.update(contentToVerify);
            return sig.verify(signature);
        } else {
            return false; // Unsupported signature algorithm for now.
        }
    }

    private boolean verifyRsaPss(byte[] data, byte[] signature, PublicKey publicKey) throws Exception {
        // Prefer RSASSA-PSS first (Java 11+).
        try {
            Signature sig = Signature.getInstance("RSASSA-PSS");
            AlgorithmParameterSpec pssParams = new java.security.spec.PSSParameterSpec("SHA-256", "MGF1", new java.security.spec.MGF1ParameterSpec("SHA-256"), 32, 1);
            sig.setParameter(pssParams);
            sig.initVerify(publicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (NoSuchAlgorithmException e) {
            // Fall back to BouncyCastle.
            Provider bcProvider = Security.getProvider("BC");
            if (bcProvider != null) {
                try {
                    Signature sig = Signature.getInstance("SHA256withRSAandMGF1", bcProvider);
                    sig.initVerify(publicKey);
                    sig.update(data);
                    return sig.verify(signature);
                } catch (Exception ignored) {
                }
            }
            throw new NoSuchAlgorithmException("RSA-PSS verification not available. " + "Please use Java 11+ or add BouncyCastle provider.");
        }
    }

    private boolean verifyFinished(byte[] finMsg, byte[] baseSecret, byte[] transcriptHash) throws Exception {
        if (finMsg == null || finMsg.length < 4) {
            return false;
        }
        int verifyLen = ((finMsg[1] & 0xFF) << 16) | ((finMsg[2] & 0xFF) << 8) | (finMsg[3] & 0xFF);
        if (finMsg.length < 4 + verifyLen) {
            return false;
        }
        byte[] verifyData = Arrays.copyOfRange(finMsg, 4, 4 + verifyLen);

        byte[] finishedKey = QuicCrypto.tlsExpandLabel(baseSecret, "finished", new byte[0], 32);
        javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
        hmac.init(new javax.crypto.spec.SecretKeySpec(finishedKey, "HmacSHA256"));
        byte[] expected = hmac.doFinal(transcriptHash);

        return MessageDigest.isEqual(verifyData, expected);
    }

    private byte[] computeECDHSharedSecret(PrivateKey serverPrivKey, byte[] clientPubKeyBytes) throws Exception {
        // Parse the client's uncompressed P-256 point, formatted as 0x04 || x(32) || y(32).
        if (clientPubKeyBytes[0] != 0x04 || clientPubKeyBytes.length != 65) {
            throw new IllegalArgumentException("Invalid P-256 uncompressed point");
        }

        BigInteger x = new BigInteger(1, Arrays.copyOfRange(clientPubKeyBytes, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(clientPubKeyBytes, 33, 65));
        ECPoint point = new ECPoint(x, y);

        // Extract elliptic-curve parameters from the local key pair.
        ECPublicKey serverPub = (ECPublicKey) serverEphemeralKeyPair.getPublic();
        ECParameterSpec params = serverPub.getParams();

        ECPublicKeySpec peerPubSpec = new ECPublicKeySpec(point, params);
        KeyFactory kf = KeyFactory.getInstance("EC");
        PublicKey peerPubKey = kf.generatePublic(peerPubSpec);

        // Perform ECDH key agreement.
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(serverPrivKey);
        ka.doPhase(peerPubKey, true);
        byte[] secret = ka.generateSecret();

        // Pad the result to 32 bytes; the standard P-256 shared secret length is 32 bytes.
        if (secret.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(secret, 0, padded, 32 - secret.length, secret.length);
            return padded;
        } else if (secret.length > 32) {
            return Arrays.copyOfRange(secret, secret.length - 32, secret.length);
        }
        return secret;
    }

    private byte[] encodeTransportParams(QuicSoConfig config) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        // CID parameters required by RFC 9000 §7.3, whose values are byte arrays rather than VarInt.
        // original_destination_connection_id (0x00): the server must carry it.
        if (!clientMode && originalDestinationCid != null && originalDestinationCid.length > 0) {
            writeTransportParamBytes(out, 0x00, originalDestinationCid);
        }
        // initial_source_connection_id (0x0f): both peers must carry it.
        if (sourceConnectionId != null && sourceConnectionId.length > 0) {
            writeTransportParamBytes(out, 0x0f, sourceConnectionId);
        }
        // Standard VarInt transport parameters.
        writeTransportParam(out, QuicAsyncChannelHandshake.PARAM_MAX_IDLE_TIMEOUT, config.getTpMaxIdleTimeout());
        writeTransportParam(out, QuicAsyncChannelHandshake.PARAM_INITIAL_MAX_DATA, config.getTpInitialFrameMaxData());
        writeTransportParam(out, QuicAsyncChannelHandshake.PARAM_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL, config.getTpInitialMaxStreamDataBidiLocal());
        writeTransportParam(out, QuicAsyncChannelHandshake.PARAM_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE, config.getTpInitialMaxStreamDataBidiRemote());
        writeTransportParam(out, QuicAsyncChannelHandshake.PARAM_INITIAL_MAX_STREAM_DATA_UNI, config.getTpInitialMaxStreamDataUni());
        writeTransportParam(out, QuicAsyncChannelHandshake.PARAM_INITIAL_MAX_STREAMS_BIDI, config.getTpInitialMaxStreamsBidi());
        writeTransportParam(out, QuicAsyncChannelHandshake.PARAM_INITIAL_MAX_STREAMS_UNI, config.getTpInitialMaxStreamsUni());
        writeTransportParam(out, QuicAsyncChannelHandshake.PARAM_ACTIVE_CONNECTION_ID_LIMIT, 8);
        if (config.getTpInitialDatagramFrameMaxData() > 0) {
            writeTransportParam(out, QuicAsyncChannelHandshake.PARAM_MAX_DATAGRAM_FRAME_SIZE, config.getTpInitialDatagramFrameMaxData());
        }
        return out.toByteArray();
    }

    // General helper methods.

    private void writeTransportParam(ByteArrayOutputStream out, int paramId, long value) {
        byte[] idBytes = QuicVarInt.encode(paramId);
        byte[] valBytes = QuicVarInt.encode(value);
        byte[] lenBytes = QuicVarInt.encode(valBytes.length);
        try {
            out.write(idBytes);
            out.write(lenBytes);
            out.write(valBytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Writes a byte-array transport parameter in QUIC encoding format, for example Connection ID;
     * format: paramId(VarInt)|length(VarInt)|value.
     */
    private void writeTransportParamBytes(ByteArrayOutputStream out, int paramId, byte[] value) {
        if (value == null || value.length == 0)
            return;
        byte[] idBytes = QuicVarInt.encode(paramId);
        byte[] lenBytes = QuicVarInt.encode(value.length);
        try {
            out.write(idBytes);
            out.write(lenBytes);
            out.write(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
