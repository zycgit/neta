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
 * QUIC protocol version definitions, encapsulating version-specific parameters
 * such as Initial Salt, key derivation label prefixes, and packet type wire encodings.
 * <p>
 * Predefined instances:
 * <ul>
 *   <li>{@link #V1} — QUIC v1 (RFC 9000/9001), wire version {@code 0x00000001}</li>
 *   <li>{@link #V2} — QUIC v2 (RFC 9369), wire version {@code 0x6b3343cf}</li>
 * </ul>
 * <p>
 * Custom versions can be created via the public constructor for experimental
 * or future QUIC versions.
 * @author 赵永春 (zyc@hasor.net)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000">RFC 9000 — QUIC v1</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9369">RFC 9369 — QUIC v2</a>
 */
public final class QuicVersion {

    // ── Wire version numbers ───────────────────────────────────────────

    /** QUIC v1 wire version (RFC 9000). */
    public static final int VERSION_1 = 0x00000001;

    /** QUIC v2 wire version (RFC 9369). */
    public static final int VERSION_2 = 0x6b3343cf;

    // ── Predefined version instances ───────────────────────────────────

    /**
     * QUIC v1 (RFC 9000/9001).
     * <ul>
     *   <li>Initial Salt: {@code 38762cf7f55934b34d179ae6a4c80cadccbb7f0a}</li>
     *   <li>Key labels: {@code "quic key"}, {@code "quic iv"}, {@code "quic hp"}</li>
     *   <li>Packet types: Initial=0, 0-RTT=1, Handshake=2, Retry=3</li>
     * </ul>
     */
    public static final QuicVersion V1 = new QuicVersion(               //
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

    /**
     * QUIC v2 (RFC 9369).
     * <ul>
     *   <li>Initial Salt: {@code 0dede3def700a6db819381be6e269dcbf9bd2ed9}</li>
     *   <li>Key labels: {@code "quicv2 key"}, {@code "quicv2 iv"}, {@code "quicv2 hp"}</li>
     *   <li>Packet types: Initial=1, 0-RTT=2, Handshake=3, Retry=0 (rotated from v1)</li>
     * </ul>
     */
    public static final QuicVersion V2 = new QuicVersion(               //
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

    // ── Instance fields ────────────────────────────────────────────────

    private final int    version;
    private final byte[] initialSalt;
    private final String keyLabelPrefix;
    private final int[]  wirePacketTypes;  // indexed by logical type: [Initial, 0-RTT, Handshake, Retry]

    /**
     * Creates a custom QUIC version definition.
     * <p>
     * Use this constructor to support experimental or future QUIC versions that are
     * not yet covered by the predefined {@link #V1} and {@link #V2} instances.
     * @param version the 32-bit wire version number
     * @param initialSalt the 20-byte Initial Salt for HKDF-Extract (RFC 9001 §5.2)
     * @param keyLabelPrefix the HKDF label prefix for packet protection keys
     * (e.g. {@code "quic"} for v1, {@code "quicv2"} for v2)
     * @param wirePacketTypes a 4-element array mapping logical packet types (indices 0–3:
     * Initial, 0-RTT, Handshake, Retry) to their wire encodings
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

    // ── Accessors ──────────────────────────────────────────────────────

    /**
     * Resolves a {@link QuicVersion} from a 32-bit wire version number.
     * @param wireVersion the version number read from a QUIC packet header
     * @return the matching {@link QuicVersion}, or {@code null} if the version is unknown
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

    /** Returns the 32-bit wire version number. */
    public int getVersion() {
        return this.version;
    }

    /**
     * Returns a copy of the 20-byte Initial Salt used for deriving Initial secrets.
     * @see <a href="https://www.rfc-editor.org/rfc/rfc9001#section-5.2">RFC 9001 §5.2</a>
     */
    public byte[] getInitialSalt() {
        return Arrays.copyOf(this.initialSalt, this.initialSalt.length);
    }

    // ── Packet Type Mapping ────────────────────────────────────────────

    /**
     * Returns the HKDF label prefix for packet protection key derivation.
     * <p>For QUIC v1 this is {@code "quic"} (producing labels like {@code "quic key"});
     * for v2 it is {@code "quicv2"} (producing {@code "quicv2 key"}).
     */
    public String getKeyLabelPrefix() {
        return this.keyLabelPrefix;
    }

    /**
     * Converts a logical packet type to its wire encoding for this QUIC version.
     * <p>Logical types use the v1 numbering convention:
     * Initial=0, 0-RTT=1, Handshake=2, Retry=3.
     * @param logicalType one of {@link QuicPacket#TYPE_INITIAL}, {@link QuicPacket#TYPE_0RTT},
     * {@link QuicPacket#TYPE_HANDSHAKE}, {@link QuicPacket#TYPE_RETRY}
     * @return the version-specific wire encoding (2-bit value)
     */
    public int logicalToWireType(int logicalType) {
        if (logicalType < 0 || logicalType > 3) {
            throw new IllegalArgumentException("Invalid logical packet type: " + logicalType);
        }
        return this.wirePacketTypes[logicalType];
    }

    // ── Lookup ─────────────────────────────────────────────────────────

    /**
     * Converts a version-specific wire packet type back to the logical type.
     * @param wireType the wire-encoded packet type from the Long Header first byte (2-bit value)
     * @return the logical type (Initial=0, 0-RTT=1, Handshake=2, Retry=3)
     * @throws IllegalArgumentException if the wire type is not found in this version's mapping
     */
    public int wireToLogicalType(int wireType) {
        for (int i = 0; i < this.wirePacketTypes.length; i++) {
            if (this.wirePacketTypes[i] == wireType) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown wire packet type " + wireType + " for QUIC version 0x" + Integer.toHexString(this.version));
    }

    @Override
    public String toString() {
        return "QuicVersion{0x" + Integer.toHexString(this.version) + ", labels=" + this.keyLabelPrefix + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof QuicVersion))
            return false;
        return this.version == ((QuicVersion) o).version;
    }

    @Override
    public int hashCode() {
        return this.version;
    }
}
