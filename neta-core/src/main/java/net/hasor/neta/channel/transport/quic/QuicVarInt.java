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
/**
 * Encoding and decoding utilities for QUIC variable-length integers.
 * <p>The implementation follows RFC 9000 Section 16 and handles the 1, 2, 4, and 8 byte variable-length integer format widely used by QUIC frames and transport parameters.
 * @author 赵永春 (zyc@hasor.net)
 */
public final class QuicVarInt {
    private QuicVarInt() {
    }

    /**
     * Decodes one QUIC variable-length integer starting at the specified offset.
     * @param data raw byte array
     * @param offset starting decode offset
     * @return a length-2 array containing the decoded value and the number of bytes consumed
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
        long value = data[offset] & (0x3F);
        for (int i = 1; i < length; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return new long[] { value, length };
    }

    /**
     * Encodes a QUIC variable-length integer into the target array at the specified offset.
     * @param dst target array
     * @param dstOffset starting write offset
     * @param value value to encode
     * @return the number of bytes written, which can only be 1, 2, 4, or 8
     */
    public static int encodeTo(byte[] dst, int dstOffset, long value) {
        if (value <= 63) {
            dst[dstOffset] = (byte) value;
            return 1;
        } else if (value <= 16383) {
            dst[dstOffset] = (byte) (0x40 | (value >>> 8));
            dst[dstOffset + 1] = (byte) (value & 0xFF);
            return 2;
        } else if (value <= 1073741823L) {
            dst[dstOffset] = (byte) (0x80 | (value >>> 24));
            dst[dstOffset + 1] = (byte) ((value >>> 16) & 0xFF);
            dst[dstOffset + 2] = (byte) ((value >>> 8) & 0xFF);
            dst[dstOffset + 3] = (byte) (value & 0xFF);
            return 4;
        } else if (value <= 4611686018427387903L) {
            dst[dstOffset] = (byte) (0xC0 | (value >>> 56));
            dst[dstOffset + 1] = (byte) ((value >>> 48) & 0xFF);
            dst[dstOffset + 2] = (byte) ((value >>> 40) & 0xFF);
            dst[dstOffset + 3] = (byte) ((value >>> 32) & 0xFF);
            dst[dstOffset + 4] = (byte) ((value >>> 24) & 0xFF);
            dst[dstOffset + 5] = (byte) ((value >>> 16) & 0xFF);
            dst[dstOffset + 6] = (byte) ((value >>> 8) & 0xFF);
            dst[dstOffset + 7] = (byte) (value & 0xFF);
            return 8;
        } else {
            throw new IllegalArgumentException("Value too large for varint encoding: " + value);
        }
    }

    /**
     * Encodes a QUIC variable-length integer and returns a newly allocated byte array.
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
     * Returns the number of bytes required to encode the specified value.
     * @return the result can only be 1, 2, 4, or 8
     */
    public static int encodedLength(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Negative varint value: " + value);
        }
        if (value <= 63) {
            return 1;
        }
        if (value <= 16383) {
            return 2;
        }
        if (value <= 1073741823L) {
            return 4;
        }
        if (value <= 4611686018427387903L) {
            return 8;
        }
        throw new IllegalArgumentException("Value too large for varint encoding: " + value);
    }
}
