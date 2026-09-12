/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.quic;
import java.util.Arrays;
/**
 * Low-level encoding and decoding helper for QUIC long-header and short-header packets.
 * <p>This utility is responsible for parsing packet outer structures, applying or removing
 * header protection, decrypting payloads, and building encrypted packets for the handshake
 * phase and the 1-RTT data phase.
 * <p><b>The packet forms handled here are as follows:</b>
 * <pre>
 *   Long Header packet
 *     + first byte + version + dcid + scid + [token] + length + pn + ciphertext
 *   Short Header packet
 *     + first byte + dcid + pn + ciphertext
 * </pre>
 * <p>Frame parsing inside decrypted payloads is mostly handled elsewhere; this class focuses on
 * packet encapsulation concerns.
 * @author 赵永春 (zyc@hasor.net)
 */
final class QuicPacket {
    public static final int TYPE_INITIAL   = 0x00;
    public static final int TYPE_0RTT      = 0x01;
    public static final int TYPE_HANDSHAKE = 0x02;
    public static final int TYPE_RETRY     = 0x03;
    public static final int TYPE_1RTT      = 0x10;

    private QuicPacket() {
    }

    /**
     * Determines whether the first byte indicates a Long Header packet.
     */
    public static boolean isLongHeader(byte[] data) {
        return (data[0] & 0x80) != 0;
    }

    /**
     * Determines whether the first byte indicates a Long Header packet.
     */
    public static boolean isLongHeader(byte firstByte) {
        return (firstByte & 0x80) != 0;
    }

    /**
     * Extracts the raw Long Header packet type from the first byte without version mapping.
     */
    public static int longHeaderType(byte firstByte) {
        return (firstByte & 0x30) >> 4;
    }

    /**
     * Obtains the logical packet type from the Long Header first byte using the version-specific
     * mapping from wire type to logical type.
     */
    public static int longHeaderType(byte firstByte, QuicVersion version) {
        int wireType = (firstByte & 0x30) >> 4;
        return version.wireToLogicalType(wireType);
    }

    /**
     * Parses a Long Header packet structure without decrypting it; returns null if the data is
     * too short.
     */
    public static ParsedPacket parseLongHeader(byte[] data, int offset, int length) {
        if (length < 7) {
            return null;
        }
        ParsedPacket pkt = new ParsedPacket();
        int pos = offset;
        byte firstByte = data[pos++];
        pkt.packetType = longHeaderType(firstByte);
        pkt.version = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16) | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
        pos += 4;
        // Convert the wire packet type to the logical type based on the QUIC version; v2 uses a different mapping order.
        QuicVersion ver = QuicVersion.fromVersion(pkt.version);
        if (ver != null) {
            pkt.packetType = ver.wireToLogicalType(pkt.packetType);
        }
        int dcidLen = data[pos++] & 0xFF;
        if (pos + dcidLen > offset + length) {
            return null;
        }
        pkt.dcid = new byte[dcidLen];
        System.arraycopy(data, pos, pkt.dcid, 0, dcidLen);
        pos += dcidLen;
        if (pos >= offset + length) {
            return null;
        }
        int scidLen = data[pos++] & 0xFF;
        if (pos + scidLen > offset + length) {
            return null;
        }
        pkt.scid = new byte[scidLen];
        System.arraycopy(data, pos, pkt.scid, 0, scidLen);
        pos += scidLen;
        if (pkt.packetType == TYPE_INITIAL) {
            if (pos >= offset + length) {
                return null;
            }
            long[] tokenLenResult = QuicVarInt.decode(data, pos);
            int tokenLen = (int) tokenLenResult[0];
            pos += (int) tokenLenResult[1];
            if (pos + tokenLen > offset + length) {
                return null;
            }
            pkt.token = new byte[tokenLen];
            if (tokenLen > 0) {
                System.arraycopy(data, pos, pkt.token, 0, tokenLen);
            }
            pos += tokenLen;
        } else {
            pkt.token = new byte[0];
        }
        if (pos >= offset + length) {
            return null;
        }
        long[] lengthResult = QuicVarInt.decode(data, pos);
        int pktLength = (int) lengthResult[0];
        pos += (int) lengthResult[1];
        int pnOffset = pos;
        if (pnOffset + pktLength > offset + length) {
            return null;
        }
        pkt.headerLength = pnOffset;
        pkt.payloadLength = pktLength;
        pkt.headerBytes = Arrays.copyOfRange(data, offset, pnOffset + pktLength);
        return pkt;
    }

    /**
     * Removes header protection from a Long Header packet and decrypts the payload; returns true
     * on success.
     */
    public static boolean decryptLongHeaderPacket(byte[] data, int offset, ParsedPacket parsed, byte[] key, byte[] iv, byte[] hp, long largestPn) {
        try {
            int pnOffset = parsed.headerLength;
            parsed.pnLength = QuicCrypto.removeHeaderProtection(data, pnOffset, hp, true);
            long truncatedPn = 0;
            for (int i = 0; i < parsed.pnLength; i++) {
                truncatedPn = (truncatedPn << 8) | (data[pnOffset + i] & 0xFF);
            }
            parsed.packetNumber = decodePacketNumber(truncatedPn, parsed.pnLength, largestPn);
            int aadLength = pnOffset + parsed.pnLength;
            byte[] aad = new byte[aadLength - offset];
            System.arraycopy(data, offset, aad, 0, aad.length);
            int encStart = pnOffset + parsed.pnLength;
            int encLength = parsed.payloadLength - parsed.pnLength;
            byte[] encrypted = new byte[encLength];
            System.arraycopy(data, encStart, encrypted, 0, encLength);
            byte[] nonce = QuicCrypto.createNonce(iv, parsed.packetNumber);
            parsed.payload = QuicCrypto.aesGcmDecrypt(key, nonce, encrypted, aad);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Parses and decrypts a Short Header (1-RTT) packet; returns null on failure.
     */
    public static ParsedPacket decryptShortHeaderPacket(byte[] data, int offset, int length, int dcidLen, byte[] key, byte[] iv, byte[] hp, long largestPn) {
        try {
            if (length < 1 + dcidLen + 4 + 16) {
                return null;
            }
            ParsedPacket pkt = new ParsedPacket();
            pkt.packetType = TYPE_1RTT;
            pkt.version = 0;
            int pos = offset + 1;
            pkt.dcid = new byte[dcidLen];
            System.arraycopy(data, pos, pkt.dcid, 0, dcidLen);
            pos += dcidLen;
            int pnOffset = pos;
            pkt.pnLength = QuicCrypto.removeHeaderProtection(data, pnOffset, hp, false);
            long truncatedPn = 0;
            for (int i = 0; i < pkt.pnLength; i++) {
                truncatedPn = (truncatedPn << 8) | (data[pnOffset + i] & 0xFF);
            }
            pkt.packetNumber = decodePacketNumber(truncatedPn, pkt.pnLength, largestPn);
            int aadLength = pnOffset + pkt.pnLength - offset;
            byte[] aad = new byte[aadLength];
            System.arraycopy(data, offset, aad, 0, aadLength);
            int encStart = pnOffset + pkt.pnLength;
            int encLength = offset + length - encStart;
            byte[] encrypted = new byte[encLength];
            System.arraycopy(data, encStart, encrypted, 0, encLength);
            byte[] nonce = QuicCrypto.createNonce(iv, pkt.packetNumber);
            pkt.payload = QuicCrypto.aesGcmDecrypt(key, nonce, encrypted, aad);
            pkt.headerLength = pnOffset - offset + pkt.pnLength;
            return pkt;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Builds an encrypted Long Header packet protected with AEAD; Initial packets can be padded
     * with PADDING as needed.
     */
    public static byte[] buildLongHeaderPacket(int packetType, int version, byte[] dcid, byte[] scid, byte[] token, long packetNumber, byte[] payload, byte[] key, byte[] iv, byte[] hp, int minSize) throws Exception {
        int pnLength = packetNumberLength(packetNumber);
        byte[] pnBytes = encodePacketNumber(packetNumber, pnLength);
        int headerSize = 1 + 4 + 1 + dcid.length + 1 + scid.length;
        if (packetType == TYPE_INITIAL) {
            headerSize += QuicVarInt.encodedLength(token != null ? token.length : 0);
            headerSize += (token != null ? token.length : 0);
        }
        int payloadWithTag = payload.length + QuicCrypto.GCM_TAG_LENGTH;
        int lengthFieldValue = pnLength + payloadWithTag;
        if (packetType == TYPE_INITIAL && minSize > 0) {
            int totalWithoutPadding = headerSize + QuicVarInt.encodedLength(lengthFieldValue) + pnLength + payloadWithTag;
            if (totalWithoutPadding < minSize) {
                int paddingNeeded = minSize - totalWithoutPadding;
                byte[] paddedPayload = new byte[payload.length + paddingNeeded];
                System.arraycopy(payload, 0, paddedPayload, 0, payload.length);
                payload = paddedPayload;
                payloadWithTag = payload.length + QuicCrypto.GCM_TAG_LENGTH;
                lengthFieldValue = pnLength + payloadWithTag;
            }
        }
        headerSize += QuicVarInt.encodedLength(lengthFieldValue);
        int totalSize = headerSize + pnLength + payloadWithTag;
        byte[] packet = new byte[totalSize];
        int pos = 0;
        packet[pos++] = (byte) (0xC0 | (packetType << 4) | (pnLength - 1));
        packet[pos++] = (byte) (version >> 24);
        packet[pos++] = (byte) (version >> 16);
        packet[pos++] = (byte) (version >> 8);
        packet[pos++] = (byte) version;
        packet[pos++] = (byte) dcid.length;
        System.arraycopy(dcid, 0, packet, pos, dcid.length);
        pos += dcid.length;
        packet[pos++] = (byte) scid.length;
        System.arraycopy(scid, 0, packet, pos, scid.length);
        pos += scid.length;
        if (packetType == TYPE_INITIAL) {
            int tokenLen = (token != null) ? token.length : 0;
            pos += QuicVarInt.encodeTo(packet, pos, tokenLen);
            if (tokenLen > 0) {
                System.arraycopy(token, 0, packet, pos, tokenLen);
                pos += tokenLen;
            }
        }
        pos += QuicVarInt.encodeTo(packet, pos, lengthFieldValue);
        int pnOffset = pos;
        System.arraycopy(pnBytes, 0, packet, pos, pnLength);
        pos += pnLength;
        byte[] aad = new byte[pnOffset + pnLength];
        System.arraycopy(packet, 0, aad, 0, aad.length);
        byte[] nonce = QuicCrypto.createNonce(iv, packetNumber);
        byte[] encrypted = QuicCrypto.aesGcmEncrypt(key, nonce, payload, aad);
        System.arraycopy(encrypted, 0, packet, pos, encrypted.length);
        QuicCrypto.applyHeaderProtection(packet, pnOffset, pnLength, hp, true);
        return packet;
    }

    /**
     * Version-aware Long Header packet builder that maps the logical packet type to its wire
     * encoding for the given QUIC version.
     */
    public static byte[] buildLongHeaderPacket(QuicVersion version, int packetType, byte[] dcid, byte[] scid, byte[] token, long packetNumber, byte[] payload, byte[] key, byte[] iv, byte[] hp, int minSize) throws Exception {
        return buildLongHeaderPacket(version.logicalToWireType(packetType), version.getVersion(), dcid, scid, token, packetNumber, payload, key, iv, hp, minSize);
    }

    /**
     * Builds a Version Negotiation packet (RFC 9000 §17.2.1).
     */
    public static byte[] buildVersionNegotiationPacket(byte[] clientDcid, byte[] clientScid, int[] supportedVersions) {
        int vnDcidLen = clientScid != null ? clientScid.length : 0;
        int vnScidLen = clientDcid != null ? clientDcid.length : 0;
        int packetLen = 1 + 4 + 1 + vnDcidLen + 1 + vnScidLen + (supportedVersions.length * 4);
        byte[] packet = new byte[packetLen];
        int pos = 0;

        // First byte: Form bit set to 1, remaining 7 bits unused.
        packet[pos++] = (byte) 0x80;

        // Version: 0x00000000, used to identify this as a Version Negotiation packet.
        packet[pos++] = 0x00;
        packet[pos++] = 0x00;
        packet[pos++] = 0x00;
        packet[pos++] = 0x00;

        // DCID Length + DCID, set to the SCID from the received packet and echoed verbatim.
        packet[pos++] = (byte) vnDcidLen;
        if (vnDcidLen > 0) {
            System.arraycopy(clientScid, 0, packet, pos, vnDcidLen);
            pos += vnDcidLen;
        }

        // SCID Length + SCID, set to the DCID from the received packet and echoed verbatim.
        packet[pos++] = (byte) vnScidLen;
        if (vnScidLen > 0) {
            System.arraycopy(clientDcid, 0, packet, pos, vnScidLen);
            pos += vnScidLen;
        }

        // Supported version list, 4 bytes per entry in big-endian order.
        for (int version : supportedVersions) {
            packet[pos++] = (byte) (version >> 24);
            packet[pos++] = (byte) (version >> 16);
            packet[pos++] = (byte) (version >> 8);
            packet[pos++] = (byte) version;
        }

        return packet;
    }

    // ── Building Packets ───────────────────────────────────────────────

    /**
     * Builds an encrypted Short Header (1-RTT) packet protected with AEAD.
     */
    public static byte[] buildShortHeaderPacket(byte[] dcid, long packetNumber, byte[] payload, byte[] key, byte[] iv, byte[] hp) throws Exception {
        int pnLength = packetNumberLength(packetNumber);
        byte[] pnBytes = encodePacketNumber(packetNumber, pnLength);
        int headerSize = 1 + dcid.length + pnLength;

        // RFC 9001 §5.4.2: ensure the ciphertext length (payload + GCM tag) is at least
        // (4 + 16 - pnLength) so the header protection sample region (16 bytes starting at
        // pnOffset + 4) remains within packet bounds.
        int minPayloadLen = 4 - pnLength + 16 - QuicCrypto.GCM_TAG_LENGTH; // = 20 - pnLength - 16 = 4 - pnLength
        byte[] actualPayload;
        if (payload.length < minPayloadLen) {
            actualPayload = new byte[minPayloadLen];
            System.arraycopy(payload, 0, actualPayload, 0, payload.length);
            // Fill the remaining bytes with 0x00, i.e. PADDING frames (RFC 9000 §19.1).
        } else {
            actualPayload = payload;
        }

        int totalSize = headerSize + actualPayload.length + QuicCrypto.GCM_TAG_LENGTH;
        byte[] packet = new byte[totalSize];
        int pos = 0;
        packet[pos++] = (byte) (0x40 | (pnLength - 1));
        System.arraycopy(dcid, 0, packet, pos, dcid.length);
        pos += dcid.length;
        int pnOffset = pos;
        System.arraycopy(pnBytes, 0, packet, pos, pnLength);
        pos += pnLength;
        byte[] aad = new byte[headerSize];
        System.arraycopy(packet, 0, aad, 0, headerSize);
        byte[] nonce = QuicCrypto.createNonce(iv, packetNumber);
        byte[] encrypted = QuicCrypto.aesGcmEncrypt(key, nonce, actualPayload, aad);
        System.arraycopy(encrypted, 0, packet, pos, encrypted.length);
        QuicCrypto.applyHeaderProtection(packet, pnOffset, pnLength, hp, false);
        return packet;
    }

    /** Recovers the full packet number from a truncated value using the largest acknowledged PN (RFC 9000 Appendix A). */
    static long decodePacketNumber(long truncatedPn, int pnLength, long largestPn) {
        long expectedPn = largestPn + 1;
        long pnWin = 1L << (pnLength * 8);
        long pnHalfWin = pnWin / 2;
        long pnMask = pnWin - 1;
        long candidatePn = (expectedPn & ~pnMask) | truncatedPn;
        if (candidatePn <= expectedPn - pnHalfWin && candidatePn + pnWin <= (1L << 62)) {
            return candidatePn + pnWin;
        }
        if (candidatePn > expectedPn + pnHalfWin && candidatePn >= pnWin) {
            return candidatePn - pnWin;
        }
        return candidatePn;
    }

    // ── Packet Number Encoding/Decoding ────────────────────────────────

    /** Returns the minimum number of bytes needed to encode the given packet number, in the range 1 to 4. */
    static int packetNumberLength(long pn) {
        if (pn <= 0xFF) {
            return 1;
        }
        if (pn <= 0xFFFF) {
            return 2;
        }
        if (pn <= 0xFFFFFF) {
            return 3;
        }
        return 4;
    }

    /** Encodes the packet number as a big-endian byte array of the specified length. */
    static byte[] encodePacketNumber(long pn, int length) {
        byte[] result = new byte[length];
        for (int i = length - 1; i >= 0; i--) {
            result[i] = (byte) (pn & 0xFF);
            pn >>= 8;
        }
        return result;
    }

    /** Builds a CRYPTO frame according to RFC 9000 §19.6, laid out as type + offset + length + data. */
    public static byte[] buildCryptoFrame(long offset, byte[] data) {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.CRYPTO);
        byte[] offsetBytes = QuicVarInt.encode(offset);
        byte[] lengthBytes = QuicVarInt.encode(data.length);
        byte[] frame = new byte[typeBytes.length + offsetBytes.length + lengthBytes.length + data.length];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(offsetBytes, 0, frame, pos, offsetBytes.length);
        pos += offsetBytes.length;
        System.arraycopy(lengthBytes, 0, frame, pos, lengthBytes.length);
        pos += lengthBytes.length;
        System.arraycopy(data, 0, frame, pos, data.length);
        return frame;
    }

    // ── QUIC Frame building utilities ──────────────────────────────────

    /** Builds an ACK frame containing only a single acknowledgment range according to RFC 9000 §19.3. */
    public static byte[] buildAckFrame(long largestAcked, long firstRange) {
        byte[] typeBytes = QuicVarInt.encode(QuicFrameType.ACK);
        byte[] largestBytes = QuicVarInt.encode(largestAcked);
        byte[] delayBytes = QuicVarInt.encode(0);
        byte[] countBytes = QuicVarInt.encode(0);
        byte[] rangeBytes = QuicVarInt.encode(firstRange);
        byte[] frame = new byte[typeBytes.length + largestBytes.length + delayBytes.length + countBytes.length + rangeBytes.length];
        int pos = 0;
        System.arraycopy(typeBytes, 0, frame, pos, typeBytes.length);
        pos += typeBytes.length;
        System.arraycopy(largestBytes, 0, frame, pos, largestBytes.length);
        pos += largestBytes.length;
        System.arraycopy(delayBytes, 0, frame, pos, delayBytes.length);
        pos += delayBytes.length;
        System.arraycopy(countBytes, 0, frame, pos, countBytes.length);
        pos += countBytes.length;
        System.arraycopy(rangeBytes, 0, frame, pos, rangeBytes.length);
        return frame;
    }

    /** Builds a HANDSHAKE_DONE frame according to RFC 9000 §19.20. */
    public static byte[] buildHandshakeDoneFrame() {
        return QuicVarInt.encode(QuicFrameType.HANDSHAKE_DONE);
    }

    /**
     * Scans QUIC frames starting from the given offset and returns the first CRYPTO frame as
     * {cryptoOffset, dataPos, dataLength}; returns {@code null} if none is found.
     */
    public static long[] parseCryptoFrame(byte[] data, int offset) {
        int pos = offset;
        while (pos < data.length) {
            long[] typeResult = QuicVarInt.decode(data, pos);
            int frameType = (int) typeResult[0];
            pos += (int) typeResult[1];

            // Once a CRYPTO frame is found, parse offset and length and return immediately.
            if (frameType == QuicFrameType.CRYPTO) {
                long[] offsetResult = QuicVarInt.decode(data, pos);
                long cryptoOffset = offsetResult[0];
                pos += (int) offsetResult[1];
                long[] lengthResult = QuicVarInt.decode(data, pos);
                long dataLength = lengthResult[0];
                pos += (int) lengthResult[1];
                return new long[] { cryptoOffset, pos, dataLength };
            }

            // Zero-length frames: PADDING, PING.
            if (frameType == QuicFrameType.PADDING || frameType == QuicFrameType.PING) {
                continue;
            }

            // ACK / ACK_ECN, see RFC 9000 §19.3.
            if (frameType == QuicFrameType.ACK || frameType == QuicFrameType.ACK_ECN) {
                long[] tmp = QuicVarInt.decode(data, pos);
                pos += (int) tmp[1]; // Largest Acknowledged
                tmp = QuicVarInt.decode(data, pos);
                pos += (int) tmp[1]; // ACK Delay
                tmp = QuicVarInt.decode(data, pos);
                long rangeCount = tmp[0];
                pos += (int) tmp[1]; // ACK Range Count
                tmp = QuicVarInt.decode(data, pos);
                pos += (int) tmp[1]; // First ACK Range
                for (long i = 0; i < rangeCount; i++) {
                    tmp = QuicVarInt.decode(data, pos);
                    pos += (int) tmp[1]; // Gap
                    tmp = QuicVarInt.decode(data, pos);
                    pos += (int) tmp[1]; // ACK Range
                }
                if (frameType == QuicFrameType.ACK_ECN) {
                    for (int i = 0; i < 3; i++) {
                        tmp = QuicVarInt.decode(data, pos);
                        pos += (int) tmp[1]; // ECT(0), ECT(1), ECN-CE
                    }
                }
                continue;
            }

            // NEW_CONNECTION_ID, see RFC 9000 §19.15.
            if (frameType == QuicFrameType.NEW_CONNECTION_ID) {
                long[] tmp = QuicVarInt.decode(data, pos);
                pos += (int) tmp[1]; // Sequence Number
                tmp = QuicVarInt.decode(data, pos);
                pos += (int) tmp[1]; // Retire Prior To
                int cidLen = data[pos++] & 0xFF;
                pos += cidLen + 16; // Connection ID + Stateless Reset Token
                continue;
            }

            // CONNECTION_CLOSE / CONNECTION_CLOSE_APP, see RFC 9000 §19.19.
            if (frameType == QuicFrameType.CONNECTION_CLOSE || frameType == QuicFrameType.CONNECTION_CLOSE_APP) {
                long[] tmp = QuicVarInt.decode(data, pos);
                pos += (int) tmp[1]; // Error Code
                if (frameType == QuicFrameType.CONNECTION_CLOSE) {
                    tmp = QuicVarInt.decode(data, pos);
                    pos += (int) tmp[1]; // Frame Type
                }
                tmp = QuicVarInt.decode(data, pos);
                pos += (int) tmp[1] + (int) tmp[0]; // Reason Length + Reason
                continue;
            }

            // Unknown frame types cannot be sized reliably, so stop scanning here.
            break;
        }
        return null;
    }

    /** Builds a raw unencrypted Long Header packet for non-SSL mode. */
    public static byte[] buildRawLongHeaderPacket(int packetType, int version, byte[] dcid, byte[] scid, byte[] token, long packetNumber, byte[] payload) {
        int pnLength = packetNumberLength(packetNumber);
        byte[] pnBytes = encodePacketNumber(packetNumber, pnLength);
        int headerSize = 1 + 4 + 1 + dcid.length + 1 + scid.length;
        if (packetType == TYPE_INITIAL) {
            headerSize += QuicVarInt.encodedLength(token != null ? token.length : 0);
            headerSize += (token != null ? token.length : 0);
        }
        int lengthFieldValue = pnLength + payload.length;
        headerSize += QuicVarInt.encodedLength(lengthFieldValue);
        int totalSize = headerSize + pnLength + payload.length;
        byte[] packet = new byte[totalSize];
        int pos = 0;
        packet[pos++] = (byte) (0xC0 | (packetType << 4) | (pnLength - 1));
        packet[pos++] = (byte) (version >> 24);
        packet[pos++] = (byte) (version >> 16);
        packet[pos++] = (byte) (version >> 8);
        packet[pos++] = (byte) version;
        packet[pos++] = (byte) dcid.length;
        System.arraycopy(dcid, 0, packet, pos, dcid.length);
        pos += dcid.length;
        packet[pos++] = (byte) scid.length;
        System.arraycopy(scid, 0, packet, pos, scid.length);
        pos += scid.length;
        if (packetType == TYPE_INITIAL) {
            int tokenLen = (token != null) ? token.length : 0;
            pos += QuicVarInt.encodeTo(packet, pos, tokenLen);
            if (tokenLen > 0) {
                System.arraycopy(token, 0, packet, pos, tokenLen);
                pos += tokenLen;
            }
        }
        pos += QuicVarInt.encodeTo(packet, pos, lengthFieldValue);
        System.arraycopy(pnBytes, 0, packet, pos, pnLength);
        pos += pnLength;
        System.arraycopy(payload, 0, packet, pos, payload.length);
        return packet;
    }

    /** Maps the logical packet type to its wire encoding for the specified QUIC version and builds a raw Long Header packet. */
    public static byte[] buildRawLongHeaderPacket(QuicVersion version, int packetType, byte[] dcid, byte[] scid, byte[] token, long packetNumber, byte[] payload) {
        return buildRawLongHeaderPacket(version.logicalToWireType(packetType), version.getVersion(), dcid, scid, token, packetNumber, payload);
    }

    // ── Version Negotiation (RFC 9000 §17.2.1) ─────────────────────────

    /**
     * Returns {@code true} when the raw packet is a Version Negotiation packet, meaning it is a
     * Long Header packet as defined by RFC 9000 §17.2.1 and its version field equals {@code 0x00000000}.
     */
    public static boolean isVersionNegotiation(byte[] data) {
        if (data == null || data.length < 5) {
            return false;
        }
        if ((data[0] & 0x80) == 0) {
            return false; // A Short Header packet cannot be a Version Negotiation packet.
        }
        return data[1] == 0 && data[2] == 0 && data[3] == 0 && data[4] == 0;
    }

    /**
     * Parses the DCID field from a Version Negotiation packet, see RFC 9000 §17.2.1.
     * The server echoes the client's Source Connection ID as the VN packet DCID for the
     * anti-spoofing validation described in RFC 9000 §6.2.
     * Returns {@code null} if the packet format is invalid.
     */
    public static byte[] parseVersionNegotiationDcid(byte[] data) {
        if (data == null || data.length < 7) {
            return null;
        }
        int pos = 5; // Skip the first byte and the 4-byte version.
        int dcidLen = data[pos++] & 0xFF;
        if (pos + dcidLen > data.length) {
            return null;
        }
        byte[] dcid = new byte[dcidLen];
        System.arraycopy(data, pos, dcid, 0, dcidLen);
        return dcid;
    }

    /**
     * Parses the Supported Versions list from a Version Negotiation packet, see RFC 9000 §17.2.1.
     * The returned value preserves the server-declared order of 32-bit version numbers; returns
     * an empty array when the list is empty, or {@code null} on parse failure.
     */
    public static int[] parseVersionNegotiationVersions(byte[] data) {
        if (data == null || data.length < 7) {
            return null;
        }
        int pos = 5; // Skip the first byte and the 4-byte version.
        int dcidLen = data[pos++] & 0xFF;
        if (pos + dcidLen > data.length) {
            return null;
        }
        pos += dcidLen;
        if (pos >= data.length) {
            return new int[0];
        }
        int scidLen = data[pos++] & 0xFF;
        if (pos + scidLen > data.length) {
            return null;
        }
        pos += scidLen;
        int remaining = data.length - pos;
        if (remaining < 0 || remaining % 4 != 0) {
            return null;
        }
        int count = remaining / 4;
        int[] versions = new int[count];
        for (int i = 0; i < count; i++) {
            versions[i] = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16) | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;
        }
        return versions;
    }

    // ── Retry (RFC 9000 §17.2.5) ────────────────────────────────────────

    /**
     * Returns {@code true} when {@code data} starts with a QUIC Retry packet (long header form,
     * type Retry mapped per the given version). A Retry packet has no Length/Packet-Number field
     * and therefore cannot be parsed with {@link #parseLongHeader}.
     */
    public static boolean isRetryPacket(byte[] data) {
        if (data == null || data.length < 7) {
            return false;
        }
        if ((data[0] & 0x80) == 0 || (data[0] & 0x40) == 0) {
            return false; // not a long header with Fixed Bit set
        }
        int version = ((data[1] & 0xFF) << 24) | ((data[2] & 0xFF) << 16) | ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);
        if (version == 0) {
            return false; // Version Negotiation
        }
        QuicVersion ver = QuicVersion.fromVersion(version);
        if (ver == null) {
            return false;
        }
        int wireType = (data[0] & 0x30) >> 4;
        return ver.wireToLogicalType(wireType) == TYPE_RETRY;
    }

    /**
     * Parsed representation of a QUIC Retry packet (RFC 9000 §17.2.5, RFC 9369 §3.3.3).
     */
    public static final class RetryPacket {
        /** 32-bit wire protocol version number carried in the Retry header. */
        public int    version;
        /** Destination Connection ID echoed from the client's Initial SCID. */
        public byte[] dcid;
        /** Server-chosen Source Connection ID; the client uses it as its new DCID. */
        public byte[] scid;
        /** Opaque Retry Token to be echoed in the client's subsequent Initial packets. */
        public byte[] retryToken;
        /** 16-byte Retry Integrity Tag, see RFC 9001 §5.8. */
        public byte[] integrityTag;
    }

    /**
     * Parses a Retry packet (RFC 9000 §17.2.5). The trailing 16 bytes are the Retry Integrity Tag
     * (RFC 9001 §5.8); everything between the header and the tag is the Retry Token. Returns
     * {@code null} when the buffer does not contain a well-formed Retry packet.
     */
    public static RetryPacket parseRetry(byte[] data, int offset, int length) {
        if (data == null || length < 1 + 4 + 1 + 1 + 16) {
            return null;
        }
        int pos = offset;
        byte firstByte = data[pos++];
        if ((firstByte & 0x80) == 0 || (firstByte & 0x40) == 0) {
            return null;
        }
        int version = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16) | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
        pos += 4;
        QuicVersion ver = QuicVersion.fromVersion(version);
        if (ver == null) {
            return null;
        }
        int wireType = (firstByte & 0x30) >> 4;
        if (ver.wireToLogicalType(wireType) != TYPE_RETRY) {
            return null;
        }
        int end = offset + length;
        int dcidLen = data[pos++] & 0xFF;
        if (pos + dcidLen >= end) {
            return null;
        }
        byte[] dcid = new byte[dcidLen];
        System.arraycopy(data, pos, dcid, 0, dcidLen);
        pos += dcidLen;
        int scidLen = data[pos++] & 0xFF;
        if (pos + scidLen > end) {
            return null;
        }
        byte[] scid = new byte[scidLen];
        System.arraycopy(data, pos, scid, 0, scidLen);
        pos += scidLen;
        int tagOffset = end - 16;
        if (tagOffset < pos) {
            return null;
        }
        int tokenLen = tagOffset - pos;
        byte[] token = new byte[tokenLen];
        System.arraycopy(data, pos, token, 0, tokenLen);
        byte[] tag = new byte[16];
        System.arraycopy(data, tagOffset, tag, 0, 16);
        RetryPacket pkt = new RetryPacket();
        pkt.version = version;
        pkt.dcid = dcid;
        pkt.scid = scid;
        pkt.retryToken = token;
        pkt.integrityTag = tag;
        return pkt;
    }

    // ── Raw (non-TLS) Short Header ─────────────────────────────────────

    /**
     * Parses a raw unencrypted Short Header packet for non-TLS mode.
     * Both server and client use this path when {@code sslEnabled=false}.
     * Returns {@code null} if the data is too short.
     */
    public static ParsedPacket parseRawShortHeader(byte[] data, int dcidLen) {
        if (data.length < 1 + dcidLen + 1) {
            return null;
        }
        ParsedPacket pkt = new ParsedPacket();
        pkt.packetType = TYPE_1RTT;
        int pos = 0;
        int firstByte = data[pos++] & 0xFF;
        int pnLength = (firstByte & 0x03) + 1;
        pkt.dcid = new byte[dcidLen];
        System.arraycopy(data, pos, pkt.dcid, 0, dcidLen);
        pos += dcidLen;
        if (pos + pnLength > data.length) {
            return null;
        }
        long pn = 0;
        for (int i = 0; i < pnLength; i++) {
            pn = (pn << 8) | (data[pos++] & 0xFF);
        }
        pkt.packetNumber = pn;
        pkt.pnLength = pnLength;
        int payloadLen = data.length - pos;
        if (payloadLen > 0) {
            pkt.payload = new byte[payloadLen];
            System.arraycopy(data, pos, pkt.payload, 0, payloadLen);
        } else {
            pkt.payload = new byte[0];
        }
        return pkt;
    }

    /** Parses a raw unencrypted Long Header packet for non-SSL mode. */
    public static ParsedPacket parseRawLongHeaderPacket(byte[] data, int offset, int length) {
        ParsedPacket pkt = parseLongHeader(data, offset, length);
        if (pkt == null) {
            return null;
        }
        int pnOffset = pkt.headerLength;
        pkt.pnLength = (data[offset] & 0x03) + 1;
        long truncatedPn = 0;
        for (int i = 0; i < pkt.pnLength; i++) {
            truncatedPn = (truncatedPn << 8) | (data[pnOffset + i] & 0xFF);
        }
        pkt.packetNumber = truncatedPn;
        int dataStart = pnOffset + pkt.pnLength;
        int dataLen = pkt.payloadLength - pkt.pnLength;
        if (dataLen > 0) {
            pkt.payload = new byte[dataLen];
            System.arraycopy(data, dataStart, pkt.payload, 0, dataLen);
        } else {
            pkt.payload = new byte[0];
        }
        return pkt;
    }

    public static class ParsedPacket {
        public int    packetType;
        public int    version;
        public byte[] dcid;
        public byte[] scid;
        public byte[] token;
        public long   packetNumber;
        public int    pnLength;
        public byte[] payload;
        public byte[] headerBytes;
        public int    headerLength;
        public int    payloadLength;
    }
}
