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
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Arrays;
import javax.crypto.KeyAgreement;

/**
 * Minimal TLS 1.3 handshake engine for QUIC server mode (RFC 8446 + RFC 9001).
 * <p>
 * Implements the server side of TLS 1.3 handshake for QUIC using pure Java crypto APIs:
 * <ul>
 *   <li>Parses ClientHello, extracts key_share (secp256r1), supported_versions, ALPN, quic_transport_params</li>
 *   <li>Generates ServerHello, EncryptedExtensions, Certificate, CertificateVerify, Finished</li>
 *   <li>Manages the TLS 1.3 key schedule (Early → Handshake → Application)</li>
 *   <li>Derives QUIC packet protection keys for each encryption level</li>
 * </ul>
 * <p>
 * This implementation supports:
 * <ul>
 *   <li>Key Exchange: secp256r1 (P-256) ECDHE</li>
 *   <li>Cipher Suite: TLS_AES_128_GCM_SHA256 (0x1301)</li>
 *   <li>Signature: RSA-PSS-RSAE-SHA256 (0x0804) or ECDSA-SECP256R1-SHA256 (0x0403)</li>
 * </ul>
 * @author 赵永春 (zyc@hasor.net)
 */
class QuicTlsEngine {
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
    private static final int GROUP_SECP256R1 = 0x0017;

    // Signature algorithms
    private static final int SIG_RSA_PSS_RSAE_SHA256    = 0x0804;
    private static final int SIG_ECDSA_SECP256R1_SHA256 = 0x0403;

    // ── State ──────────────────────────────────────────────────────────

    private final X509Certificate[] certChain;
    private final PrivateKey        privateKey;
    private final QuicSettings      localTransportParams;

    // TLS handshake state
    private byte[] clientRandom;
    private byte[] serverRandom;
    private byte[] clientSessionId; // legacy session_id echo
    private byte[] peerKeyShareP256; // client's P-256 public key (65 bytes uncompressed)
    private byte[] peerQuicTransportParams;

    // Generated during handshake
    private KeyPair serverEphemeralKeyPair;
    private byte[]  sharedSecret;

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
    private byte[] encryptedExtensionsMsg;
    private byte[] certificateMsg;
    private byte[] certificateVerifyMsg;
    private byte[] serverFinishedMsg;

    /**
     * Creates a new TLS engine for QUIC server mode.
     * @param certChain server certificate chain (leaf first)
     * @param privateKey server private key
     * @param localParams local QUIC transport parameters
     */
    public QuicTlsEngine(X509Certificate[] certChain, PrivateKey privateKey, QuicSettings localParams) {
        this.certChain = certChain;
        this.privateKey = privateKey;
        this.localTransportParams = (localParams != null) ? localParams : defaultTransportParams();
    }

    private static QuicSettings defaultTransportParams() {
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

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Processes a TLS ClientHello message from a QUIC CRYPTO frame.
     * @param clientHello raw ClientHello handshake message bytes (starting with handshake type)
     * @return true if successfully parsed and server messages generated
     */
    public boolean processClientHello(byte[] clientHello) throws Exception {
        this.transcriptHash = MessageDigest.getInstance("SHA-256");

        // Parse ClientHello
        if (!parseClientHello(clientHello)) {
            return false;
        }

        // Add ClientHello to transcript
        transcriptHash.update(clientHello);

        // Generate server ephemeral key pair for P-256
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        this.serverEphemeralKeyPair = kpg.generateKeyPair();

        // Compute shared secret via ECDH
        this.sharedSecret = computeECDHSharedSecret(serverEphemeralKeyPair.getPrivate(), peerKeyShareP256);

        // Generate ServerHello
        this.serverRandom = new byte[32];
        new SecureRandom().nextBytes(serverRandom);
        this.serverHelloMsg = buildServerHello();

        // Add ServerHello to transcript
        transcriptHash.update(serverHelloMsg);

        // Derive handshake keys
        deriveHandshakeSecrets();

        // Generate EncryptedExtensions
        this.encryptedExtensionsMsg = buildEncryptedExtensions();
        transcriptHash.update(encryptedExtensionsMsg);

        // Generate Certificate
        this.certificateMsg = buildCertificate();
        transcriptHash.update(certificateMsg);

        // Generate CertificateVerify
        byte[] transcriptSoFar = ((MessageDigest) transcriptHash.clone()).digest();
        this.certificateVerifyMsg = buildCertificateVerify(transcriptSoFar);
        transcriptHash.update(certificateVerifyMsg);

        // Generate Finished
        byte[] transcriptBeforeFinished = ((MessageDigest) transcriptHash.clone()).digest();
        this.serverFinishedMsg = buildFinished(serverHandshakeTrafficSecret, transcriptBeforeFinished);
        transcriptHash.update(serverFinishedMsg);

        // Derive application keys
        deriveApplicationSecrets();

        return true;
    }

    /**
     * Verifies the client's Finished message.
     * @param clientFinished raw Finished handshake message bytes
     * @return true if verification succeeded
     */
    public boolean verifyClientFinished(byte[] clientFinished) throws Exception {
        if (clientFinished == null || clientFinished.length < 4) {
            return false;
        }

        // Extract verify_data from client Finished
        int verifyLen = ((clientFinished[1] & 0xFF) << 16) | ((clientFinished[2] & 0xFF) << 8) | (clientFinished[3] & 0xFF);
        if (clientFinished.length < 4 + verifyLen) {
            return false;
        }
        byte[] clientVerifyData = Arrays.copyOfRange(clientFinished, 4, 4 + verifyLen);

        // Compute expected verify_data
        byte[] transcriptBeforeClientFinished = ((MessageDigest) transcriptHash.clone()).digest();
        byte[] finishedKey = QuicCrypto.tlsExpandLabel(clientHandshakeTrafficSecret, "finished", new byte[0], 32);

        javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
        hmac.init(new javax.crypto.spec.SecretKeySpec(finishedKey, "HmacSHA256"));
        byte[] expectedVerifyData = hmac.doFinal(transcriptBeforeClientFinished);

        if (!MessageDigest.isEqual(clientVerifyData, expectedVerifyData)) {
            return false;
        }

        // Add client Finished to transcript
        transcriptHash.update(clientFinished);
        return true;
    }

    // ── Getters for derived keys ───────────────────────────────────────

    /** Returns the Initial→ServerHello TLS message bytes (for CRYPTO frame in Initial packet). */
    public byte[] getServerHelloBytes() {
        return serverHelloMsg;
    }

    /**
     * Returns the encrypted handshake messages: EncryptedExtensions + Certificate
     * + CertificateVerify + Finished (for CRYPTO frames in Handshake packet).
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

    // ── ClientHello Parsing ────────────────────────────────────────────

    private boolean parseClientHello(byte[] msg) {
        if (msg.length < 4) {
            return false;
        }

        int type = msg[0] & 0xFF;
        if (type != HT_CLIENT_HELLO) {
            return false;
        }

        int length = ((msg[1] & 0xFF) << 16) | ((msg[2] & 0xFF) << 8) | (msg[3] & 0xFF);
        int pos = 4;
        int end = 4 + length;
        if (end > msg.length) {
            return false;
        }

        // legacy_version (0x0303)
        pos += 2;

        // random (32 bytes)
        this.clientRandom = new byte[32];
        System.arraycopy(msg, pos, clientRandom, 0, 32);
        pos += 32;

        // legacy_session_id
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
                            if (group == GROUP_SECP256R1 && keyLen == 65) {
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

                default:
                    break;
            }
            pos = extDataEnd;
        }

        return foundTls13 && foundKeyShare;
    }

    // ── TLS Message Building ───────────────────────────────────────────

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

        // key_share extension (server's P-256 public key)
        byte[] serverPubKeyBytes = encodeP256PublicKey((ECPublicKey) serverEphemeralKeyPair.getPublic());
        ByteArrayOutputStream ksData = new ByteArrayOutputStream();
        ksData.write(GROUP_SECP256R1 >> 8);
        ksData.write(GROUP_SECP256R1 & 0xFF);
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

        // ALPN extension — advertise "h3"
        byte[] h3 = "h3".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream alpnData = new ByteArrayOutputStream();
        int alpnListLen = 1 + h3.length;
        alpnData.write(alpnListLen >> 8);
        alpnData.write(alpnListLen & 0xFF);
        alpnData.write(h3.length);
        alpnData.write(h3);
        byte[] alpnBytes = alpnData.toByteArray();

        exts.write(EXT_ALPN >> 8);
        exts.write(EXT_ALPN & 0xFF);
        exts.write(alpnBytes.length >> 8);
        exts.write(alpnBytes.length & 0xFF);
        exts.write(alpnBytes);

        // QUIC Transport Parameters extension
        byte[] tpBytes = encodeTransportParams(localTransportParams);
        exts.write(EXT_QUIC_TRANSPORT_PARAMS >> 8);
        exts.write(EXT_QUIC_TRANSPORT_PARAMS & 0xFF);
        exts.write(tpBytes.length >> 8);
        exts.write(tpBytes.length & 0xFF);
        exts.write(tpBytes);

        // Wrap in EncryptedExtensions message
        byte[] extList = exts.toByteArray();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(extList.length >> 8);
        body.write(extList.length & 0xFF);
        body.write(extList);

        return wrapHandshakeMessage(HT_ENCRYPTED_EXTENSIONS, body.toByteArray());
    }

    private byte[] buildCertificate() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream(4096);

        // certificate_request_context (empty for server)
        body.write(0x00);

        // certificate_list
        ByteArrayOutputStream certList = new ByteArrayOutputStream(4096);
        for (X509Certificate cert : certChain) {
            byte[] certDer = cert.getEncoded();
            certList.write((certDer.length >> 16) & 0xFF);
            certList.write((certDer.length >> 8) & 0xFF);
            certList.write(certDer.length & 0xFF);
            certList.write(certDer);
            // extensions per certificate entry (empty)
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
        // Construct the content to be signed (RFC 8446 §4.4.3)
        ByteArrayOutputStream sigInput = new ByteArrayOutputStream(130);
        // 64 bytes of 0x20 (space)
        byte[] padding = new byte[64];
        Arrays.fill(padding, (byte) 0x20);
        sigInput.write(padding);
        // context string
        sigInput.write("TLS 1.3, server CertificateVerify".getBytes(StandardCharsets.US_ASCII));
        // separator byte 0x00
        sigInput.write(0x00);
        // transcript hash
        sigInput.write(transcriptHash);

        byte[] contentToSign = sigInput.toByteArray();

        // Sign with the server's private key
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

        // Build CertificateVerify message
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(sigAlgorithm >> 8);
        body.write(sigAlgorithm & 0xFF);
        body.write(signature.length >> 8);
        body.write(signature.length & 0xFF);
        body.write(signature);

        return wrapHandshakeMessage(HT_CERTIFICATE_VERIFY, body.toByteArray());
    }

    private byte[] signRsaPss(byte[] data) throws Exception {
        // Try RSASSA-PSS (Java 11+)
        try {
            Signature sig = Signature.getInstance("RSASSA-PSS");
            AlgorithmParameterSpec pssParams = new java.security.spec.PSSParameterSpec("SHA-256", "MGF1", new java.security.spec.MGF1ParameterSpec("SHA-256"), 32, 1);
            sig.setParameter(pssParams);
            sig.initSign(privateKey);
            sig.update(data);
            return sig.sign();
        } catch (NoSuchAlgorithmException e) {
            // Fallback: try with BouncyCastle provider if available
            try {
                Provider bcProvider = Security.getProvider("BC");
                if (bcProvider != null) {
                    Signature sig = Signature.getInstance("SHA256withRSAandMGF1", bcProvider);
                    sig.initSign(privateKey);
                    sig.update(data);
                    return sig.sign();
                }
            } catch (Exception ignored) {
            }
            // Last resort: use PKCS#1 v1.5 (not strictly TLS 1.3 compliant, but may work)
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(data);
            return sig.sign();
        }
    }

    private byte[] buildFinished(byte[] baseSecret, byte[] transcriptHash) throws Exception {
        byte[] finishedKey = QuicCrypto.tlsExpandLabel(baseSecret, "finished", new byte[0], 32);

        javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
        hmac.init(new javax.crypto.spec.SecretKeySpec(finishedKey, "HmacSHA256"));
        byte[] verifyData = hmac.doFinal(transcriptHash);

        return wrapHandshakeMessage(HT_FINISHED, verifyData);
    }

    // ── Key Schedule (RFC 8446 §7.1) ───────────────────────────────────

    private void deriveHandshakeSecrets() throws Exception {
        // early_secret = HKDF-Extract(salt=0, PSK=0)
        byte[] zeroKey = new byte[32];
        byte[] earlySecret = QuicCrypto.hkdfExtract(new byte[1], zeroKey); // salt=0x00

        // Derive-Secret(early_secret, "derived", "")
        byte[] emptyHash = QuicCrypto.sha256(new byte[0]);
        byte[] derivedSecret = QuicCrypto.deriveSecret(earlySecret, "derived", emptyHash);

        // handshake_secret = HKDF-Extract(derived_secret, shared_secret)
        byte[] handshakeSecret = QuicCrypto.hkdfExtract(derivedSecret, sharedSecret);

        // Transcript hash up to ServerHello (ClientHello + ServerHello already added)
        byte[] chShHash = ((MessageDigest) transcriptHash.clone()).digest();

        // client_handshake_traffic_secret
        clientHandshakeTrafficSecret = QuicCrypto.deriveSecret(handshakeSecret, "c hs traffic", chShHash);

        // server_handshake_traffic_secret
        serverHandshakeTrafficSecret = QuicCrypto.deriveSecret(handshakeSecret, "s hs traffic", chShHash);

        // Derive QUIC packet protection keys
        clientHandshakeKeys = QuicCrypto.derivePacketKeys(clientHandshakeTrafficSecret);
        serverHandshakeKeys = QuicCrypto.derivePacketKeys(serverHandshakeTrafficSecret);

        // Store handshake_secret for application key derivation
        byte[] derivedFromHandshake = QuicCrypto.deriveSecret(handshakeSecret, "derived", emptyHash);
        // master_secret = HKDF-Extract(derived_from_handshake, 0)
        byte[] masterSecret = QuicCrypto.hkdfExtract(derivedFromHandshake, zeroKey);

        // Store for application key derivation (after Finished)
        this.sharedSecret = masterSecret; // reuse field to store master_secret
    }

    private void deriveApplicationSecrets() throws Exception {
        byte[] masterSecret = this.sharedSecret; // stored from deriveHandshakeSecrets

        // Transcript hash after server Finished
        byte[] serverFinishedHash = ((MessageDigest) transcriptHash.clone()).digest();

        // client_application_traffic_secret_0
        clientAppTrafficSecret = QuicCrypto.deriveSecret(masterSecret, "c ap traffic", serverFinishedHash);

        // server_application_traffic_secret_0
        serverAppTrafficSecret = QuicCrypto.deriveSecret(masterSecret, "s ap traffic", serverFinishedHash);

        // Derive QUIC packet protection keys
        clientAppKeys = QuicCrypto.derivePacketKeys(clientAppTrafficSecret);
        serverAppKeys = QuicCrypto.derivePacketKeys(serverAppTrafficSecret);
    }

    // ── ECDHE P-256 ────────────────────────────────────────────────────

    private byte[] computeECDHSharedSecret(PrivateKey serverPrivKey, byte[] clientPubKeyBytes) throws Exception {
        // Decode client's uncompressed P-256 point (0x04 || x(32) || y(32))
        if (clientPubKeyBytes[0] != 0x04 || clientPubKeyBytes.length != 65) {
            throw new IllegalArgumentException("Invalid P-256 uncompressed point");
        }

        BigInteger x = new BigInteger(1, Arrays.copyOfRange(clientPubKeyBytes, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(clientPubKeyBytes, 33, 65));
        ECPoint point = new ECPoint(x, y);

        // Get EC parameters from our key pair
        ECPublicKey serverPub = (ECPublicKey) serverEphemeralKeyPair.getPublic();
        ECParameterSpec params = serverPub.getParams();

        ECPublicKeySpec peerPubSpec = new ECPublicKeySpec(point, params);
        KeyFactory kf = KeyFactory.getInstance("EC");
        PublicKey peerPubKey = kf.generatePublic(peerPubSpec);

        // ECDH key agreement
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(serverPrivKey);
        ka.doPhase(peerPubKey, true);
        byte[] secret = ka.generateSecret();

        // Pad to 32 bytes (P-256 shared secret is 32 bytes)
        if (secret.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(secret, 0, padded, 32 - secret.length, secret.length);
            return padded;
        } else if (secret.length > 32) {
            return Arrays.copyOfRange(secret, secret.length - 32, secret.length);
        }
        return secret;
    }

    /**
     * Encodes an EC public key as an uncompressed P-256 point (0x04 || x || y, 65 bytes).
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
            // Strip leading zero
            return Arrays.copyOfRange(bytes, bytes.length - length, bytes.length);
        } else {
            // Pad with leading zeros
            byte[] padded = new byte[length];
            System.arraycopy(bytes, 0, padded, length - bytes.length, bytes.length);
            return padded;
        }
    }

    // ── QUIC Transport Parameters Encoding ─────────────────────────────

    private byte[] encodeTransportParams(QuicSettings settings) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        writeTransportParam(out, QuicSettings.PARAM_MAX_IDLE_TIMEOUT, settings.maxIdleTimeout());
        writeTransportParam(out, QuicSettings.PARAM_INITIAL_MAX_DATA, settings.initialMaxData());
        writeTransportParam(out, QuicSettings.PARAM_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL, settings.initialMaxStreamDataBidiLocal());
        writeTransportParam(out, QuicSettings.PARAM_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE, settings.initialMaxStreamDataBidiRemote());
        writeTransportParam(out, QuicSettings.PARAM_INITIAL_MAX_STREAM_DATA_UNI, settings.initialMaxStreamDataUni());
        writeTransportParam(out, QuicSettings.PARAM_INITIAL_MAX_STREAMS_BIDI, settings.initialMaxStreamsBidi());
        writeTransportParam(out, QuicSettings.PARAM_INITIAL_MAX_STREAMS_UNI, settings.initialMaxStreamsUni());
        writeTransportParam(out, QuicSettings.PARAM_ACTIVE_CONNECTION_ID_LIMIT, settings.activeConnectionIdLimit());
        return out.toByteArray();
    }

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

    // ── Utility ────────────────────────────────────────────────────────

    private static byte[] wrapHandshakeMessage(int type, byte[] body) {
        byte[] msg = new byte[4 + body.length];
        msg[0] = (byte) type;
        msg[1] = (byte) ((body.length >> 16) & 0xFF);
        msg[2] = (byte) ((body.length >> 8) & 0xFF);
        msg[3] = (byte) (body.length & 0xFF);
        System.arraycopy(body, 0, msg, 4, body.length);
        return msg;
    }
}
