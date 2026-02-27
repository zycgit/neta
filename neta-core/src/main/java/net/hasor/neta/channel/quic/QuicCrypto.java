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
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptographic utilities for QUIC packet protection (RFC 9001).
 * Provides HKDF key derivation, AES-128-GCM AEAD, header protection,
 * and Initial/Handshake/1-RTT key derivation.
 * @author 赵永春 (zyc@hasor.net)
 */
final class QuicCrypto {
    public static final  int GCM_TAG_LENGTH      = 16;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private QuicCrypto() {
    }

    // ── HKDF (RFC 5869) ────────────────────────────────────────────────

    public static byte[] hkdfExtract(byte[] salt, byte[] ikm) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
        return hmac.doFinal(ikm);
    }

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

    public static byte[] tlsExpandLabel(byte[] secret, String label, byte[] context, int length) throws Exception {
        return hkdfExpandLabel(secret, label, context, length, "tls13 ");
    }

    public static byte[] quicExpandLabel(byte[] secret, String label, byte[] context, int length) throws Exception {
        return hkdfExpandLabel(secret, label, context, length, "quic ");
    }

    // ── Initial Keys (RFC 9001 §5.2) ───────────────────────────────────

    /** Derives Initial secrets for QUIC v1. For version-aware derivation, use {@link #deriveInitialSecrets(byte[], QuicVersion)}. */
    public static byte[][] deriveInitialSecrets(byte[] dcid) throws Exception {
        return deriveInitialSecrets(dcid, QuicVersion.V1);
    }

    /**
     * Derives Initial secrets using the version-specific Initial Salt.
     * @param dcid the Destination Connection ID from the client's Initial packet
     * @param version the QUIC version determining which Initial Salt to use
     * @return {@code [clientSecret, serverSecret]}, each 32 bytes
     * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-5.2">RFC 9001 §5.2</a>
     * @see <a href="https://www.rfc-editor.org/rfc/rfc9369#section-4.1">RFC 9369 §4.1</a>
     */
    public static byte[][] deriveInitialSecrets(byte[] dcid, QuicVersion version) throws Exception {
        byte[] initialSecret = hkdfExtract(version.getInitialSalt(), dcid);
        byte[] clientSecret = tlsExpandLabel(initialSecret, "client in", new byte[0], 32);
        byte[] serverSecret = tlsExpandLabel(initialSecret, "server in", new byte[0], 32);
        return new byte[][] { clientSecret, serverSecret };
    }

    // ── Packet Protection Keys (RFC 9001 §5.1) ────────────────────────

    /** Derives packet protection keys for QUIC v1. For version-aware derivation, use {@link #derivePacketKeys(byte[], QuicVersion)}. */
    public static byte[][] derivePacketKeys(byte[] secret) throws Exception {
        return derivePacketKeys(secret, QuicVersion.V1);
    }

    /**
     * Derives QUIC packet protection keys using version-specific HKDF labels.
     * <p>QUIC v1 uses labels {@code "quic key/iv/hp"}; v2 uses {@code "quicv2 key/iv/hp"} (RFC 9369 §4.1).
     * @param secret the traffic secret from which to derive keys
     * @param version the QUIC version determining the HKDF label prefix
     * @return {@code [key(16), iv(12), hp(16)]}
     */
    public static byte[][] derivePacketKeys(byte[] secret, QuicVersion version) throws Exception {
        String prefix = version.getKeyLabelPrefix();
        byte[] key = tlsExpandLabel(secret, prefix + " key", new byte[0], 16);
        byte[] iv = tlsExpandLabel(secret, prefix + " iv", new byte[0], 12);
        byte[] hp = tlsExpandLabel(secret, prefix + " hp", new byte[0], 16);
        return new byte[][] { key, iv, hp };
    }

    // ── AEAD (AES-128-GCM) ────────────────────────────────────────────

    public static byte[] aesGcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(plaintext);
    }

    public static byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(ciphertext);
    }

    // ── Nonce construction ─────────────────────────────────────────────

    public static byte[] createNonce(byte[] iv, long packetNumber) {
        byte[] nonce = new byte[iv.length];
        System.arraycopy(iv, 0, nonce, 0, iv.length);
        for (int i = 0; i < 8; i++) {
            nonce[iv.length - 1 - i] ^= (byte) ((packetNumber >>> (8 * i)) & 0xFF);
        }
        return nonce;
    }

    // ── Header Protection (RFC 9001 §5.4) ──────────────────────────────

    public static byte[] headerProtectionMask(byte[] hpKey, byte[] sample) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hpKey, "AES"));
        byte[] encrypted = cipher.doFinal(sample);
        byte[] mask = new byte[5];
        System.arraycopy(encrypted, 0, mask, 0, 5);
        return mask;
    }

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

    // ── TLS Key Schedule helpers (RFC 8446 §7.1) ──────────────────────

    public static byte[] deriveSecret(byte[] secret, String label, byte[] transcriptHash) throws Exception {
        return tlsExpandLabel(secret, label, transcriptHash, 32);
    }

    public static byte[] sha256(byte[] data) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        return md.digest(data);
    }

    public static byte[] sha256(byte[]... dataArrays) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        for (byte[] data : dataArrays) {
            if (data != null) {
                md.update(data);
            }
        }
        return md.digest();
    }

    // ── Utility ────────────────────────────────────────────────────────

    private static byte[] toAscii(String s) {
        byte[] result = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            result[i] = (byte) s.charAt(i);
        }
        return result;
    }

    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) | Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
