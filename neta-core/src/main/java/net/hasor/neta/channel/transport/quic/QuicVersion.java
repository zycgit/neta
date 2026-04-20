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
import java.util.Arrays;
/**
 * QUIC protocol version definition.
 * <p>Encapsulates the version-specific initial salt, key label prefix, and packet-type encoding mappings.
 * @author 赵永春 (zyc@hasor.net)
 */
public final class QuicVersion {
    // ── Wire protocol version numbers ─────────────────────────────────
    /** Wire protocol version number for QUIC v1. */
    public static final int VERSION_1 = 0x00000001;
    /** Wire protocol version number for QUIC v2. */
    public static final int VERSION_2 = 0x6b3343cf;
    // ── Predefined version instances ──────────────────────────────────
    /** QUIC v1 version instance. */
    public static final QuicVersion V1 = new QuicVersion(        //
            VERSION_1,                                                  //
            new byte[] {                                                //
                    (byte) 0x38, (byte) 0x76, (byte) 0x2c, (byte) 0xf7, //
                    (byte) 0xf5, (byte) 0x59, (byte) 0x34, (byte) 0xb3, //
                    (byte) 0x4d, (byte) 0x17, (byte) 0x9a, (byte) 0xe6, //
                    (byte) 0xa4, (byte) 0xc8, (byte) 0x0c, (byte) 0xad, //
                    (byte) 0xcc, (byte) 0xbb, (byte) 0x7f, (byte) 0x0a  //
            },                                                          //
            "quic",                                                     //
            new int[] { 0x00, 0x01, 0x02, 0x03 }                        // Initial, 0-RTT, Handshake, Retry
    );
    /** QUIC v2 version instance using the wire version and packet-type mapping defined in RFC 9369. */
    public static final QuicVersion V2 = new QuicVersion(        //
            VERSION_2,                                                  //
            new byte[] {                                                //
                    (byte) 0x0d, (byte) 0xed, (byte) 0xe3, (byte) 0xde, //
                    (byte) 0xf7, (byte) 0x00, (byte) 0xa6, (byte) 0xdb, //
                    (byte) 0x81, (byte) 0x93, (byte) 0x81, (byte) 0xbe, //
                    (byte) 0x6e, (byte) 0x26, (byte) 0x9d, (byte) 0xcb, //
                    (byte) 0xf9, (byte) 0xbd, (byte) 0x2e, (byte) 0xd9  //
            },                                                          //
            "quicv2",                                                   //
            new int[] { 0x01, 0x02, 0x03, 0x00 }                        // Initial, 0-RTT, Handshake, Retry
    );

    // ── Instance fields ───────────────────────────────────────────────

    private final int    version;
    private final byte[] initialSalt;
    private final String keyLabelPrefix;
    private final int[]  wirePacketTypes;  // Indexed by logical type: [Initial, 0-RTT, Handshake, Retry]

    /**
     * Creates a custom QUIC version definition.
     */
    public QuicVersion(int version, byte[] initialSalt, String keyLabelPrefix, int[] wirePacketTypes) {
        if (initialSalt == null || initialSalt.length != 20) {
            throw new IllegalArgumentException("initialSalt must be exactly 20 bytes");
        }
        if (keyLabelPrefix == null || keyLabelPrefix.isEmpty()) {
            throw new IllegalArgumentException("keyLabelPrefix must not be null or empty");
        }
        if (wirePacketTypes == null || wirePacketTypes.length != 4) {
            throw new IllegalArgumentException("wirePacketTypes must be a 4-element array [Initial, 0-RTT, Handshake, Retry]");
        }
        this.version = version;
        this.initialSalt = Arrays.copyOf(initialSalt, initialSalt.length);
        this.keyLabelPrefix = keyLabelPrefix;
        this.wirePacketTypes = Arrays.copyOf(wirePacketTypes, wirePacketTypes.length);
    }

    // ── Accessors ─────────────────────────────────────────────────────

    /**
     * Resolves the corresponding QuicVersion from a 32-bit wire protocol version number.
     * @return returns null if the version is unknown
     */
    public static QuicVersion fromVersion(int wireVersion) {
        switch (wireVersion) {
            case VERSION_1:
                return V1;
            case VERSION_2:
                return V2;
            default:
                return null;
        }
    }

    /**
     * Returns the 32-bit wire protocol version number.
     */
    public int getVersion() {
        return this.version;
    }

    /**
     * Returns a copy of the 20-byte Initial Salt.
     */
    public byte[] getInitialSalt() {
        return Arrays.copyOf(this.initialSalt, this.initialSalt.length);
    }

    // ── Packet type mapping ───────────────────────────────────────────

    /**
     * Returns the HKDF label prefix used for packet protection key derivation.
     */
    public String getKeyLabelPrefix() {
        return this.keyLabelPrefix;
    }

    /**
     * Converts a logical packet type to the wire encoding for the current version.
     */
    public int logicalToWireType(int logicalType) {
        if (logicalType < 0 || logicalType > 3) {
            throw new IllegalArgumentException("Invalid logical packet type: " + logicalType);
        }
        return this.wirePacketTypes[logicalType];
    }

    // ── Reverse lookup ────────────────────────────────────────────────

    /**
     * Converts a version-specific wire packet type back to its logical type number.
     */
    public int wireToLogicalType(int wireType) {
        for (int i = 0; i < this.wirePacketTypes.length; i++) {
            if (this.wirePacketTypes[i] == wireType) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown wire packet type " + wireType + " for QUIC version 0x" + Integer.toHexString(this.version));
    }

    /**
     * Returns a version description suitable for debug output.
     */
    @Override
    public String toString() {
        return "QuicVersion{0x" + Integer.toHexString(this.version) + ", labels=" + this.keyLabelPrefix + "}";
    }

    /**
     * Returns whether two version objects represent the same wire protocol version.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof QuicVersion))
            return false;
        return this.version == ((QuicVersion) o).version;
    }

    /**
     * Returns a hash value based on the wire protocol version number.
     */
    @Override
    public int hashCode() {
        return this.version;
    }
}
