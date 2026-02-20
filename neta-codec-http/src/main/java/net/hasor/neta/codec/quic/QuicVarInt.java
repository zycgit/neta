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
package net.hasor.neta.codec.quic;

/**
 * QUIC variable-length integer encoding/decoding as defined in RFC 9000, Section 16.
 * <p>
 * QUIC uses a variable-length integer encoding that uses the two most significant
 * bits to indicate the length of the integer:
 * <ul>
 *   <li>00 → 1 byte (6-bit value, max 63)</li>
 *   <li>01 → 2 bytes (14-bit value, max 16383)</li>
 *   <li>10 → 4 bytes (30-bit value, max 1073741823)</li>
 *   <li>11 → 8 bytes (62-bit value, max 4611686018427387903)</li>
 * </ul>
 */
public final class QuicVarInt {

    private QuicVarInt() {
    }

    /**
     * Reads a variable-length integer from the given byte array at the specified offset.
     * @param data the byte array
     * @param offset the offset to start reading
     * @return a two-element array: [value, bytesConsumed]
     */
    public static long[] decode(byte[] data, int offset) {
        if (offset >= data.length) {
            throw new IllegalArgumentException("Not enough data for varint at offset " + offset);
        }

        int prefix = (data[offset] & 0xFF) >>> 6;
        int length = 1 << prefix;

        if (offset + length > data.length) {
            throw new IllegalArgumentException("Not enough data for " + length + "-byte varint at offset " + offset);
        }

        long value = data[offset] & (0x3F); // Strip the 2-bit length prefix
        for (int i = 1; i < length; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }

        return new long[] { value, length };
    }

    /**
     * Encodes a value as a QUIC variable-length integer.
     * @param value the value to encode (must be non-negative and at most 2^62-1)
     * @return the encoded bytes
     */
    public static byte[] encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Negative varint value: " + value);
        }

        if (value <= 63) {
            return new byte[] { (byte) value };
        } else if (value <= 16383) {
            return new byte[] { (byte) (0x40 | (value >>> 8)), (byte) (value & 0xFF) };
        } else if (value <= 1073741823L) {
            return new byte[] { (byte) (0x80 | (value >>> 24)), (byte) ((value >>> 16) & 0xFF), (byte) ((value >>> 8) & 0xFF), (byte) (value & 0xFF) };
        } else if (value <= 4611686018427387903L) {
            return new byte[] { (byte) (0xC0 | (value >>> 56)), (byte) ((value >>> 48) & 0xFF), (byte) ((value >>> 40) & 0xFF), (byte) ((value >>> 32) & 0xFF), (byte) ((value >>> 24) & 0xFF), (byte) ((value >>> 16) & 0xFF), (byte) ((value >>> 8) & 0xFF), (byte) (value & 0xFF) };
        } else {
            throw new IllegalArgumentException("Value too large for varint encoding: " + value);
        }
    }

    /**
     * Returns the number of bytes needed to encode the given value.
     * @param value the value
     * @return the number of bytes (1, 2, 4 or 8)
     */
    public static int encodedLength(long value) {
        if (value <= 63)
            return 1;
        if (value <= 16383)
            return 2;
        if (value <= 1073741823L)
            return 4;
        return 8;
    }
}
