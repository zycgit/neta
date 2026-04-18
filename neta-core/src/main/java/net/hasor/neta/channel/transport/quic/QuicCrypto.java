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
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptographic utility set related to QUIC packet protection.
 * <p>Covers the HKDF, AES-128-GCM AEAD, and header-protection algorithms required by RFC 9001.
 * @author 赵永春 (zyc@hasor.net)
 */
final class QuicCrypto {
    public static final int  GCM_TAG_LENGTH      = 16;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private QuicCrypto() {
    }

    // ── HKDF (RFC 5869) ───────────────────────────────────────────────

    /**
     * Performs HKDF-Extract.
     * <p>Derives the PRK from the salt and input keying material.
     */
    public static byte[] hkdfExtract(byte[] salt, byte[] ikm) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
        return hmac.doFinal(ikm);
    }

    /**
     * Performs HKDF-Expand.
     * <p>Expands the PRK to the target length using the info parameter.
     */
    public static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] result = new byte[length];
        byte[] t = new byte[0];
        int offset = 0;
        byte counter = 1;
        while (offset < length) {
            hmac.update(t);
            hmac.update(info);
            hmac.update(counter);
            t = hmac.doFinal();
            int toCopy = Math.min(t.length, length - offset);
            System.arraycopy(t, 0, result, offset, toCopy);
            offset += toCopy;
            counter++;
        }
        return result;
    }

    /**
     * Performs HKDF-Expand-Label with a configurable label prefix.
     */
    public static byte[] hkdfExpandLabel(byte[] secret, String label, byte[] context, int length, String labelPrefix) throws Exception {
        byte[] fullLabel = toAscii(labelPrefix + label);
        byte[] ctx = (context != null) ? context : new byte[0];
        byte[] hkdfLabel = new byte[2 + 1 + fullLabel.length + 1 + ctx.length];
        hkdfLabel[0] = (byte) (length >> 8);
        hkdfLabel[1] = (byte) (length);
        hkdfLabel[2] = (byte) fullLabel.length;
        System.arraycopy(fullLabel, 0, hkdfLabel, 3, fullLabel.length);
        hkdfLabel[3 + fullLabel.length] = (byte) ctx.length;
        if (ctx.length > 0) {
            System.arraycopy(ctx, 0, hkdfLabel, 4 + fullLabel.length, ctx.length);
        }
        return hkdfExpand(secret, hkdfLabel, length);
    }

    /**
     * Performs HKDF-Expand-Label using the TLS 1.3 label prefix.
     */
    public static byte[] tlsExpandLabel(byte[] secret, String label, byte[] context, int length) throws Exception {
        return hkdfExpandLabel(secret, label, context, length, "tls13 ");
    }

    /**
     * Performs HKDF-Expand-Label using the QUIC label prefix.
     */
    public static byte[] quicExpandLabel(byte[] secret, String label, byte[] context, int length) throws Exception {
        return hkdfExpandLabel(secret, label, context, length, "quic ");
    }

    // ── Initial keys (RFC 9001 §5.2) ──────────────────────────────────

    /**
     * Derives Initial secrets for QUIC v1 using the default Initial Salt.
     */
    public static byte[][] deriveInitialSecrets(byte[] dcid) throws Exception {
        return deriveInitialSecrets(dcid, QuicVersion.V1);
    }

    /**
     * Derives Initial secrets using the Initial Salt for the specified version.
     * @return returns clientSecret and serverSecret
     */
    public static byte[][] deriveInitialSecrets(byte[] dcid, QuicVersion version) throws Exception {
        byte[] initialSecret = hkdfExtract(version.getInitialSalt(), dcid);
        byte[] clientSecret = tlsExpandLabel(initialSecret, "client in", new byte[0], 32);
        byte[] serverSecret = tlsExpandLabel(initialSecret, "server in", new byte[0], 32);
        return new byte[][] { clientSecret, serverSecret };
    }

    // ── Packet protection keys (RFC 9001 §5.1) ───────────────────────

    /**
     * Derives packet-protection keys for QUIC v1 using the default HKDF labels.
     */
    public static byte[][] derivePacketKeys(byte[] secret) throws Exception {
        return derivePacketKeys(secret, QuicVersion.V1);
    }

    /**
     * Derives QUIC packet-protection keys using the version-specific HKDF label prefix.
     * @return returns the key, iv, and hp results
     */
    public static byte[][] derivePacketKeys(byte[] secret, QuicVersion version) throws Exception {
        String prefix = version.getKeyLabelPrefix();
        byte[] key = tlsExpandLabel(secret, prefix + " key", new byte[0], 16);
        byte[] iv = tlsExpandLabel(secret, prefix + " iv", new byte[0], 12);
        byte[] hp = tlsExpandLabel(secret, prefix + " hp", new byte[0], 16);
        return new byte[][] { key, iv, hp };
    }

    // ── AEAD (AES-128-GCM) ────────────────────────────────────────────

    /**
     * Encrypts data with AES-128-GCM.
     */
    public static byte[] aesGcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(plaintext);
    }

    /**
     * Decrypts data with AES-128-GCM.
     */
    public static byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(ciphertext);
    }

    // ── Nonce construction ────────────────────────────────────────────

    /**
     * Builds the AEAD nonce by XORing the IV with the packet number.
     */
    public static byte[] createNonce(byte[] iv, long packetNumber) {
        byte[] nonce = new byte[iv.length];
        System.arraycopy(iv, 0, nonce, 0, iv.length);
        for (int i = 0; i < 8; i++) {
            nonce[iv.length - 1 - i] ^= (byte) ((packetNumber >>> (8 * i)) & 0xFF);
        }
        return nonce;
    }

    // ── Header protection (RFC 9001 §5.4) ────────────────────────────

    /**
     * Generates a 5-byte header-protection mask from a 16-byte sample.
     */
    public static byte[] headerProtectionMask(byte[] hpKey, byte[] sample) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hpKey, "AES"));
        byte[] encrypted = cipher.doFinal(sample);
        byte[] mask = new byte[5];
        System.arraycopy(encrypted, 0, mask, 0, 5);
        return mask;
    }

    /**
     * Removes header protection in place and returns the parsed packet number length.
     */
    public static int removeHeaderProtection(byte[] packet, int pnOffset, byte[] hpKey, boolean isLongHeader) throws Exception {
        int sampleOffset = pnOffset + 4;
        if (sampleOffset + 16 > packet.length) {
            throw new IllegalArgumentException("Packet too short for header protection sample");
        }
        byte[] sample = new byte[16];
        System.arraycopy(packet, sampleOffset, sample, 0, 16);
        byte[] mask = headerProtectionMask(hpKey, sample);
        if (isLongHeader) {
            packet[0] ^= (mask[0] & 0x0F);
        } else {
            packet[0] ^= (mask[0] & 0x1F);
        }
        int pnLength = (packet[0] & 0x03) + 1;
        for (int i = 0; i < pnLength; i++) {
            packet[pnOffset + i] ^= mask[1 + i];
        }
        return pnLength;
    }

    /**
     * Apply header protection to the packet in place.
     */
    public static void applyHeaderProtection(byte[] packet, int pnOffset, int pnLength, byte[] hpKey, boolean isLongHeader) throws Exception {
        int sampleOffset = pnOffset + 4;
        byte[] sample = new byte[16];
        System.arraycopy(packet, sampleOffset, sample, 0, 16);
        byte[] mask = headerProtectionMask(hpKey, sample);
        if (isLongHeader) {
            packet[0] ^= (mask[0] & 0x0F);
        } else {
            packet[0] ^= (mask[0] & 0x1F);
        }
        for (int i = 0; i < pnLength; i++) {
            packet[pnOffset + i] ^= mask[1 + i];
        }
    }

    // ── Retry Integrity Tag (RFC 9001 §5.8, RFC 9369 §3.3.3) ─────────────────

    /**
     * Retry Integrity Tag key for QUIC v1, defined in RFC 9001 §5.8.
     * <p>Hex value {@code be0c690b9f66575a1d766b54e368c84e}.
     */
    private static final byte[] RETRY_KEY_V1   = hexToBytes("be0c690b9f66575a1d766b54e368c84e");
    /**
     * Retry Integrity Tag nonce for QUIC v1, defined in RFC 9001 §5.8.
     * <p>Hex value {@code 461599d35d632bf2239825bb}.
     */
    private static final byte[] RETRY_NONCE_V1 = hexToBytes("461599d35d632bf2239825bb");
    /**
     * Retry Integrity Tag key for QUIC v2, defined in RFC 9369 §3.3.3.
     * <p>Hex value {@code 8fb4b01b56ac48e260fbcbcead7ccc92}.
     */
    private static final byte[] RETRY_KEY_V2   = hexToBytes("8fb4b01b56ac48e260fbcbcead7ccc92");
    /**
     * Retry Integrity Tag nonce for QUIC v2, defined in RFC 9369 §3.3.3.
     * <p>Hex value {@code d86969bc2d7c6d9990efb04a}.
     */
    private static final byte[] RETRY_NONCE_V2 = hexToBytes("d86969bc2d7c6d9990efb04a");

    /**
     * Builds the Retry Pseudo-Packet as defined in RFC 9001 §5.8.
     * <pre>
     *   Retry Pseudo-Packet {
     *     ODCID Length (8),
     *     Original Destination Connection ID (0..160),
     *     Header Form (1),
     *     Fixed Bit (1),
     *     Long Packet Type (2),
     *     Unused (4),
     *     Version (32),
     *     DCID Len (8), Destination Connection ID (0..160),
     *     SCID Len (8), Source Connection ID (0..160),
     *     Retry Token (..),
     *   }
     * </pre>
     * @param odcid                   original destination CID from the client's first Initial
     * @param retryPacketWithoutTag   Retry packet bytes starting at the first byte (long header) and ending
     *                                immediately before the 16-byte integrity tag
     */
    public static byte[] buildRetryPseudoPacket(byte[] odcid, byte[] retryPacketWithoutTag) {
        int odcidLen = odcid != null ? odcid.length : 0;
        byte[] out = new byte[1 + odcidLen + retryPacketWithoutTag.length];
        out[0] = (byte) odcidLen;
        if (odcidLen > 0) {
            System.arraycopy(odcid, 0, out, 1, odcidLen);
        }
        System.arraycopy(retryPacketWithoutTag, 0, out, 1 + odcidLen, retryPacketWithoutTag.length);
        return out;
    }

    /**
     * Returns the version-specific AEAD_AES_128_GCM key used for Retry Integrity Tag computation.
     */
    private static byte[] retryKey(QuicVersion version) {
        if (version == QuicVersion.V2) {
            return RETRY_KEY_V2;
        }
        return RETRY_KEY_V1;
    }

    /**
     * Returns the version-specific AEAD_AES_128_GCM nonce used for Retry Integrity Tag computation.
     */
    private static byte[] retryNonce(QuicVersion version) {
        if (version == QuicVersion.V2) {
            return RETRY_NONCE_V2;
        }
        return RETRY_NONCE_V1;
    }

    /**
     * Computes the 16-byte Retry Integrity Tag for a Retry packet, as required by RFC 9001 §5.8
     * (and RFC 9369 §3.3.3 for QUIC v2).
     * <p>The tag is the AEAD_AES_128_GCM authentication tag produced with:
     * <ul>
     *   <li>key = Retry key fixed by the version</li>
     *   <li>nonce = Retry nonce fixed by the version</li>
     *   <li>plaintext = empty</li>
     *   <li>AAD = Retry Pseudo-Packet (see {@link #buildRetryPseudoPacket})</li>
     * </ul>
     * @param odcid                 original destination CID
     * @param retryPacketWithoutTag Retry packet bytes without the trailing 16-byte tag
     * @param version               QUIC version; {@link QuicVersion#V1} or {@link QuicVersion#V2}
     * @return the 16-byte integrity tag
     */
    public static byte[] computeRetryIntegrityTag(byte[] odcid, byte[] retryPacketWithoutTag, QuicVersion version) throws Exception {
        byte[] aad = buildRetryPseudoPacket(odcid, retryPacketWithoutTag);
        // AEAD_AES_128_GCM over empty plaintext returns a 16-byte ciphertext that is exactly the tag.
        byte[] ct = aesGcmEncrypt(retryKey(version), retryNonce(version), new byte[0], aad);
        if (ct.length != GCM_TAG_LENGTH) {
            throw new IllegalStateException("unexpected Retry tag length " + ct.length);
        }
        return ct;
    }

    /**
     * Validates the 16-byte Retry Integrity Tag of a received Retry packet per RFC 9001 §5.8.
     * @param odcid      original destination CID the client previously sent
     * @param retryBytes full Retry packet bytes including the trailing 16-byte tag
     * @param version    QUIC version
     * @return {@code true} when the trailing tag matches the computed AEAD tag
     */
    public static boolean verifyRetryIntegrityTag(byte[] odcid, byte[] retryBytes, QuicVersion version) throws Exception {
        if (retryBytes == null || retryBytes.length < GCM_TAG_LENGTH + 1) {
            return false;
        }
        int tagOffset = retryBytes.length - GCM_TAG_LENGTH;
        byte[] withoutTag = new byte[tagOffset];
        System.arraycopy(retryBytes, 0, withoutTag, 0, tagOffset);
        byte[] expected = computeRetryIntegrityTag(odcid, withoutTag, version);
        int diff = 0;
        for (int i = 0; i < GCM_TAG_LENGTH; i++) {
            diff |= (expected[i] ^ retryBytes[tagOffset + i]) & 0xFF;
        }
        return diff == 0;
    }

    // ── TLS key-schedule helper methods (RFC 8446 §7.1) ──────────────────────

    /**
     * Derive a TLS 1.3 secret from the base secret, label, and transcript hash.
     */
    public static byte[] deriveSecret(byte[] secret, String label, byte[] transcriptHash) throws Exception {
        return tlsExpandLabel(secret, label, transcriptHash, 32);
    }

    /**
     * Compute the SHA-256 digest of the given data.
     */
    public static byte[] sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(data);
    }

    /**
     * Compute the SHA-256 digest of multiple concatenated data segments.
     */
    public static byte[] sha256(byte[]... dataArrays) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (byte[] data : dataArrays) {
            if (data != null) {
                md.update(data);
            }
        }
        return md.digest();
    }

    // ── Utility methods ───────────────────────────────────────────────────────

    private static byte[] toAscii(String s) {
        byte[] result = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            result[i] = (byte) s.charAt(i);
        }
        return result;
    }

    /**
     * Convert a hexadecimal string into a byte array.
     */
    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) | Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Convert a byte array into a lowercase hexadecimal string.
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
