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
import java.util.Arrays;

/**
 * QUIC packet parsing and building utilities (RFC 9000 §17).
 * Handles Long Header (Initial, Handshake) and Short Header (1-RTT) packets.
 * @author 赵永春 (zyc@hasor.net)
 */
public final class QuicPacket {
    private QuicPacket() {
    }

    public static final int TYPE_INITIAL   = 0x00;
    public static final int TYPE_0RTT      = 0x01;
    public static final int TYPE_HANDSHAKE = 0x02;
    public static final int TYPE_RETRY     = 0x03;
    public static final int TYPE_1RTT      = 0x10;

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

    public static boolean isLongHeader(byte[] data) {
        return (data[0] & 0x80) != 0;
    }

    public static int longHeaderType(byte firstByte) {
        return (firstByte & 0x30) >> 4;
    }

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

    // ── Building Packets ───────────────────────────────────────────────

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

    public static byte[] buildShortHeaderPacket(byte[] dcid, long packetNumber, byte[] payload, byte[] key, byte[] iv, byte[] hp) throws Exception {
        int pnLength = packetNumberLength(packetNumber);
        byte[] pnBytes = encodePacketNumber(packetNumber, pnLength);
        int headerSize = 1 + dcid.length + pnLength;
        int totalSize = headerSize + payload.length + QuicCrypto.GCM_TAG_LENGTH;
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
        byte[] encrypted = QuicCrypto.aesGcmEncrypt(key, nonce, payload, aad);
        System.arraycopy(encrypted, 0, packet, pos, encrypted.length);
        QuicCrypto.applyHeaderProtection(packet, pnOffset, pnLength, hp, false);
        return packet;
    }

    // ── Packet Number Encoding/Decoding ────────────────────────────────

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

    static byte[] encodePacketNumber(long pn, int length) {
        byte[] result = new byte[length];
        for (int i = length - 1; i >= 0; i--) {
            result[i] = (byte) (pn & 0xFF);
            pn >>= 8;
        }
        return result;
    }

    // ── QUIC Frame building utilities ──────────────────────────────────

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

    public static byte[] buildHandshakeDoneFrame() {
        return QuicVarInt.encode(QuicFrameType.HANDSHAKE_DONE);
    }

    public static long[] parseCryptoFrame(byte[] data, int offset) {
        int pos = offset;
        long[] typeResult = QuicVarInt.decode(data, pos);
        if ((int) typeResult[0] != QuicFrameType.CRYPTO) {
            return null;
        }
        pos += (int) typeResult[1];
        long[] offsetResult = QuicVarInt.decode(data, pos);
        long cryptoOffset = offsetResult[0];
        pos += (int) offsetResult[1];
        long[] lengthResult = QuicVarInt.decode(data, pos);
        long dataLength = lengthResult[0];
        pos += (int) lengthResult[1];
        return new long[] { cryptoOffset, pos, dataLength };
    }

    /** Build a raw (unencrypted) Long Header packet for non-SSL mode. */
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

    /** Parse a raw (unencrypted) Long Header packet for non-SSL mode. */
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
}
