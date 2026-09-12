/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.cookie;
import net.hasor.neta.bytebuf.ByteBuf;
/**
 * Package-private utility methods used by cookie encoding and decoding.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-19
 */
final class CookieUtils {
    // Reusable scratch buffer for writeLong (Long.MIN_VALUE needs at most 20 characters).
    private static final ThreadLocal<byte[]> LONG_SCRATCH = ThreadLocal.withInitial(() -> new byte[20]);

    /**
     * Finds the position of the target byte {@code target} within the buffer range from {@code from}
     * (inclusive) to {@code to} (exclusive).
     * Returns -1 when the target is not found.
     */
    public static int indexOf(ByteBuf buf, int from, int to, byte target) {
        for (int i = from; i < to; i++) {
            if (buf.getByte(i) == target) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Performs a case-insensitive comparison between buffer bytes and a lowercase ASCII byte array.
     */
    public static boolean equalsIgnoreCase(ByteBuf buf, int offset, byte[] lowerTarget) {
        for (int i = 0; i < lowerTarget.length; i++) {
            byte b = buf.getByte(offset + i);
            if (b != lowerTarget[i] && toLower(b) != lowerTarget[i]) {
                return false;
            }
        }
        return true;
    }

    public static byte toLower(byte b) {
        return (b >= 'A' && b <= 'Z') ? (byte) (b + 32) : b;
    }

    /**
     * Writes the given long value to the buffer directly as ASCII decimal digits.
     */
    public static void writeLong(ByteBuf dst, long value) {
        if (value == 0) {
            dst.writeByte((byte) '0');
            return;
        }
        if (value < 0) {
            dst.writeByte((byte) '-');
            value = -value;
        }
        // The maximum number of decimal digits in a long is 19.
        byte[] tmp = LONG_SCRATCH.get();
        int pos = tmp.length;
        while (value > 0) {
            tmp[--pos] = (byte) ('0' + (value % 10));
            value /= 10;
        }
        dst.writeBytes(tmp, pos, tmp.length - pos);
    }
}
