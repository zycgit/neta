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
 * Cryptographic utilities for QUIC packet protection (RFC 9001): HKDF, AES-128-GCM AEAD, and header protection.
 * @author 赵永春 (zyc@hasor.net)
 */
final class QuicCrypto {
    public static final  int GCM_TAG_LENGTH      = 16;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private QuicCrypto() {
    }

    // ── HKDF (RFC 5869) ────────────────────────────────────────────────

    /** HKDF-Extract (RFC 5869 §2.2): derives a PRK from salt and input keying material. */
    public static byte[] hkdfExtract(byte[] salt, byte[] ikm) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
        return hmac.doFinal(ikm);
    }

    /** HKDF-Expand (RFC 5869 §2.3): expands a PRK to the desired output length using the info parameter. */
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

    /** HKDF-Expand-Label with a configurable label prefix ("tls13 " or "quic "). */
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

    /** HKDF-Expand-Label with TLS 1.3 prefix ("tls13 "). */
    public static byte[] tlsExpandLabel(byte[] secret, String label, byte[] context, int length) throws Exception {
        return hkdfExpandLabel(secret, label, context, length, "tls13 ");
    }

    /** HKDF-Expand-Label with QUIC prefix ("quic "). */
    public static byte[] quicExpandLabel(byte[] secret, String label, byte[] context, int length) throws Exception {
        return hkdfExpandLabel(secret, label, context, length, "quic ");
    }

    // ── Initial Keys (RFC 9001 §5.2) ───────────────────────────────────

    /** Derives Initial secrets for QUIC v1 using the default Initial Salt. */
    public static byte[][] deriveInitialSecrets(byte[] dcid) throws Exception {
        return deriveInitialSecrets(dcid, QuicVersion.V1);
    }

    /** Derives Initial secrets using the version-specific Initial Salt, returning [clientSecret, serverSecret]. */
    public static byte[][] deriveInitialSecrets(byte[] dcid, QuicVersion version) throws Exception {
        byte[] initialSecret = hkdfExtract(version.getInitialSalt(), dcid);
        byte[] clientSecret = tlsExpandLabel(initialSecret, "client in", new byte[0], 32);
        byte[] serverSecret = tlsExpandLabel(initialSecret, "server in", new byte[0], 32);
        return new byte[][] { clientSecret, serverSecret };
    }

    // ── Packet Protection Keys (RFC 9001 §5.1) ────────────────────────

    /** Derives packet protection keys for QUIC v1 using default HKDF labels. */
    public static byte[][] derivePacketKeys(byte[] secret) throws Exception {
        return derivePacketKeys(secret, QuicVersion.V1);
    }

    /** Derives QUIC packet protection keys [key(16), iv(12), hp(16)] using version-specific HKDF label prefix. */
    public static byte[][] derivePacketKeys(byte[] secret, QuicVersion version) throws Exception {
        String prefix = version.getKeyLabelPrefix();
        byte[] key = tlsExpandLabel(secret, prefix + " key", new byte[0], 16);
        byte[] iv = tlsExpandLabel(secret, prefix + " iv", new byte[0], 12);
        byte[] hp = tlsExpandLabel(secret, prefix + " hp", new byte[0], 16);
        return new byte[][] { key, iv, hp };
    }

    // ── AEAD (AES-128-GCM) ────────────────────────────────────────────

    /** Encrypts plaintext using AES-128-GCM with the given key, nonce, and AAD. */
    public static byte[] aesGcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(plaintext);
    }

    /** Decrypts ciphertext using AES-128-GCM with the given key, nonce, and AAD. */
    public static byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(ciphertext);
    }

    // ── Nonce construction ─────────────────────────────────────────────

    /** Constructs an AEAD nonce by XOR-ing the IV with the packet number (RFC 9001 §5.3). */
    public static byte[] createNonce(byte[] iv, long packetNumber) {
        byte[] nonce = new byte[iv.length];
        System.arraycopy(iv, 0, nonce, 0, iv.length);
        for (int i = 0; i < 8; i++) {
            nonce[iv.length - 1 - i] ^= (byte) ((packetNumber >>> (8 * i)) & 0xFF);
        }
        return nonce;
    }

    // ── Header Protection (RFC 9001 §5.4) ──────────────────────────────

    /** Generates a 5-byte header protection mask using AES-ECB on the 16-byte sample. */
    public static byte[] headerProtectionMask(byte[] hpKey, byte[] sample) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hpKey, "AES"));
        byte[] encrypted = cipher.doFinal(sample);
        byte[] mask = new byte[5];
        System.arraycopy(encrypted, 0, mask, 0, 5);
        return mask;
    }

    /** Removes header protection in-place and returns the decoded packet number length. */
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

    /** Applies header protection in-place to the packet (RFC 9001 §5.4). */
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

    /** Derives a TLS 1.3 secret from a base secret, label, and transcript hash (RFC 8446 §7.1). */
    public static byte[] deriveSecret(byte[] secret, String label, byte[] transcriptHash) throws Exception {
        return tlsExpandLabel(secret, label, transcriptHash, 32);
    }

    /** Computes the SHA-256 digest of the given data. */
    public static byte[] sha256(byte[] data) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        return md.digest(data);
    }

    /** Computes the SHA-256 digest of multiple concatenated data arrays. */
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

    /** Converts a hex string to a byte array. */
    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) | Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /** Converts a byte array to a lowercase hex string. */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
