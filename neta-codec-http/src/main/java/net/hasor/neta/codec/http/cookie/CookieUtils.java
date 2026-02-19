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
package net.hasor.neta.codec.http.cookie;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Internal utility methods for ByteBuf-based cookie encoding and decoding.
 */
final class CookieUtils {
    // Reusable scratch for writeLong (max 20 digits for Long.MIN_VALUE)
    private static final ThreadLocal<byte[]> LONG_SCRATCH = ThreadLocal.withInitial(() -> new byte[20]);

    /** Find the index of {@code target} byte in the buffer between {@code from} (inclusive) and {@code to} (exclusive). Returns -1 if not found. Offset is relative to readerIndex. */
    static int indexOf(ByteBuf buf, int from, int to, byte target) {
        for (int i = from; i < to; i++) {
            if (buf.getByte(i) == target) {
                return i;
            }
        }
        return -1;
    }

    /** Case-insensitive comparison of buffer bytes against a lowercase ASCII byte array. Offset is relative to readerIndex. */
    static boolean equalsIgnoreCase(ByteBuf buf, int offset, byte[] lowerTarget) {
        for (int i = 0; i < lowerTarget.length; i++) {
            byte b = buf.getByte(offset + i);
            if (b != lowerTarget[i] && toLower(b) != lowerTarget[i]) {
                return false;
            }
        }
        return true;
    }

    static byte toLower(byte b) {
        return (b >= 'A' && b <= 'Z') ? (byte) (b + 32) : b;
    }

    /** Write a long value as ASCII decimal digits directly to the buffer. */
    static void writeLong(ByteBuf dst, long value) {
        if (value == 0) {
            dst.writeByte((byte) '0');
            return;
        }
        if (value < 0) {
            dst.writeByte((byte) '-');
            value = -value;
        }
        // max digits for long is 19
        byte[] tmp = LONG_SCRATCH.get();
        int pos = tmp.length;
        while (value > 0) {
            tmp[--pos] = (byte) ('0' + (value % 10));
            value /= 10;
        }
        dst.writeBytes(tmp, pos, tmp.length - pos);
    }
}