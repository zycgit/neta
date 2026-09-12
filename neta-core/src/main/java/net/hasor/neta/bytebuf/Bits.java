/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
/**
 * Internal helper that reads and writes multi-byte primitive values ({@code short},
 * {@code int}, {@code long}, {@code float}, {@code double}) to/from a
 * {@link ByteBuf} at a given byte offset, in either big-endian or little-endian
 * byte order.
 * <p>To avoid one {@code _putByte}/{@code _getByte} per byte (each of which performs
 * range and availability checks), all multi-byte operations stage the bytes into a
 * thread-local 8-byte scratch buffer ({@code TMP8}) and then copy them to/from the
 * target {@link ByteBuf} in a single bulk call.  This reduces the number of
 * check-and-extend calls from N (one per byte) to 1.
 * <p><b>Package-private:</b> this class is an implementation detail of
 * {@link AbstractByteBuf} and is not part of the public API.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
class Bits {
    // ThreadLocal temp buffer to avoid per-byte _putByte/_getByte calls (reduces checkFree/checkExtension from N to 1)
    private static final ThreadLocal<byte[]> TMP8 = ThreadLocal.withInitial(() -> new byte[8]);

    private static byte long7(long x) {
        return (byte) (x >> 56);
    }

    private static byte long6(long x) {
        return (byte) (x >> 48);
    }

    private static byte long5(long x) {
        return (byte) (x >> 40);
    }

    private static byte long4(long x) {
        return (byte) (x >> 32);
    }

    private static byte long3(long x) {
        return (byte) (x >> 24);
    }

    private static byte long2(long x) {
        return (byte) (x >> 16);
    }

    private static byte long1(long x) {
        return (byte) (x >> 8);
    }

    private static byte long0(long x) {
        return (byte) (x);
    }

    private static byte int3(int x) {
        return (byte) (x >> 24);
    }

    private static byte int2(int x) {
        return (byte) (x >> 16);
    }

    private static byte int1(int x) {
        return (byte) (x >> 8);
    }

    private static byte int0(int x) {
        return (byte) (x);
    }

    private static byte short1(short x) {
        return (byte) (x >> 8);
    }

    private static byte short0(short x) {
        return (byte) (x);
    }

    public static void encodeInt16(AbstractByteBuf bb, int offset, short v, boolean bigEndian) {
        byte[] tmp = TMP8.get();
        if (bigEndian) {
            tmp[0] = short1(v);
            tmp[1] = short0(v);
        } else {
            tmp[0] = short0(v);
            tmp[1] = short1(v);
        }
        bb._putBytes(offset, tmp, 0, 2);
    }

    public static void encodeInt24(AbstractByteBuf bb, int offset, int v, boolean bigEndian) {
        byte[] tmp = TMP8.get();
        if (bigEndian) {
            tmp[0] = int2(v);
            tmp[1] = int1(v);
            tmp[2] = int0(v);
        } else {
            tmp[0] = int0(v);
            tmp[1] = int1(v);
            tmp[2] = int2(v);
        }
        bb._putBytes(offset, tmp, 0, 3);
    }

    public static void encodeInt32(AbstractByteBuf bb, int offset, int v, boolean bigEndian) {
        byte[] tmp = TMP8.get();
        if (bigEndian) {
            tmp[0] = int3(v);
            tmp[1] = int2(v);
            tmp[2] = int1(v);
            tmp[3] = int0(v);
        } else {
            tmp[0] = int0(v);
            tmp[1] = int1(v);
            tmp[2] = int2(v);
            tmp[3] = int3(v);
        }
        bb._putBytes(offset, tmp, 0, 4);
    }

    public static void encodeInt32(AbstractByteBuf bb, int offset, long v, boolean bigEndian) {
        byte[] tmp = TMP8.get();
        if (bigEndian) {
            tmp[0] = long3(v);
            tmp[1] = long2(v);
            tmp[2] = long1(v);
            tmp[3] = long0(v);
        } else {
            tmp[0] = long0(v);
            tmp[1] = long1(v);
            tmp[2] = long2(v);
            tmp[3] = long3(v);
        }
        bb._putBytes(offset, tmp, 0, 4);
    }

    public static void encodeInt64(AbstractByteBuf bb, int offset, long v, boolean bigEndian) {
        byte[] tmp = TMP8.get();
        if (bigEndian) {
            tmp[0] = long7(v);
            tmp[1] = long6(v);
            tmp[2] = long5(v);
            tmp[3] = long4(v);
            tmp[4] = long3(v);
            tmp[5] = long2(v);
            tmp[6] = long1(v);
            tmp[7] = long0(v);
        } else {
            tmp[0] = long0(v);
            tmp[1] = long1(v);
            tmp[2] = long2(v);
            tmp[3] = long3(v);
            tmp[4] = long4(v);
            tmp[5] = long5(v);
            tmp[6] = long6(v);
            tmp[7] = long7(v);
        }
        bb._putBytes(offset, tmp, 0, 8);
    }

    private static short makeShort(byte b1, byte b0) {
        return (short) ((b1 << 8) | (b0 & 0xff));
    }

    private static int makeInt(byte b2, byte b1, byte b0) {
        return (((b2 & 0xff) << 16) | ((b1 & 0xff) << 8) | ((b0 & 0xff)));
    }

    private static int makeInt(byte b3, byte b2, byte b1, byte b0) {
        return (((b3) << 24) | ((b2 & 0xff) << 16) | ((b1 & 0xff) << 8) | ((b0 & 0xff)));
    }

    static private long makeLong(byte b7, byte b6, byte b5, byte b4, byte b3, byte b2, byte b1, byte b0) {
        return ((((long) b7) << 56) | //
                (((long) b6 & 0xff) << 48) | //
                (((long) b5 & 0xff) << 40) | //
                (((long) b4 & 0xff) << 32) | //
                (((long) b3 & 0xff) << 24) | //
                (((long) b2 & 0xff) << 16) | //
                (((long) b1 & 0xff) << 8) | //
                (((long) b0 & 0xff)));
    }

    public static short decodeInt16(AbstractByteBuf bb, int offset, boolean bigEndian) {
        byte[] tmp = TMP8.get();
        bb._getBytes(offset, tmp, 0, 2);
        if (bigEndian) {
            return makeShort(tmp[0], tmp[1]);
        } else {
            return makeShort(tmp[1], tmp[0]);
        }
    }

    public static int decodeInt24(AbstractByteBuf bb, int offset, boolean bigEndian) {
        byte[] tmp = TMP8.get();
        bb._getBytes(offset, tmp, 0, 3);
        if (bigEndian) {
            return makeInt(tmp[0], tmp[1], tmp[2]);
        } else {
            return makeInt(tmp[2], tmp[1], tmp[0]);
        }
    }

    public static int decodeInt32(AbstractByteBuf bb, int offset, boolean bigEndian) {
        byte[] tmp = TMP8.get();
        bb._getBytes(offset, tmp, 0, 4);
        if (bigEndian) {
            return makeInt(tmp[0], tmp[1], tmp[2], tmp[3]);
        } else {
            return makeInt(tmp[3], tmp[2], tmp[1], tmp[0]);
        }
    }

    public static long decodeInt64(AbstractByteBuf bb, int offset, boolean bigEndian) {
        byte[] tmp = TMP8.get();
        bb._getBytes(offset, tmp, 0, 8);
        if (bigEndian) {
            return makeLong(tmp[0], tmp[1], tmp[2], tmp[3], tmp[4], tmp[5], tmp[6], tmp[7]);
        } else {
            return makeLong(tmp[7], tmp[6], tmp[5], tmp[4], tmp[3], tmp[2], tmp[1], tmp[0]);
        }
    }

    public static short decodeUInt8(AbstractByteBuf bb, int offset) {
        return makeShort((byte) 0, bb._getByte(offset));
    }

    public static int decodeUInt16(AbstractByteBuf bb, int offset, boolean bigEndian) {
        byte[] tmp = TMP8.get();
        bb._getBytes(offset, tmp, 0, 2);
        if (bigEndian) {
            return makeInt((byte) 0, tmp[0], tmp[1]);
        } else {
            return makeInt((byte) 0, tmp[1], tmp[0]);
        }
    }

    public static int decodeUInt24(AbstractByteBuf bb, int offset, boolean bigEndian) {
        byte[] tmp = TMP8.get();
        bb._getBytes(offset, tmp, 0, 3);
        if (bigEndian) {
            return makeInt((byte) 0, tmp[0], tmp[1], tmp[2]);
        } else {
            return makeInt((byte) 0, tmp[2], tmp[1], tmp[0]);
        }
    }

    public static long decodeUInt32(AbstractByteBuf bb, int offset, boolean bigEndian) {
        byte[] tmp = TMP8.get();
        bb._getBytes(offset, tmp, 0, 4);
        if (bigEndian) {
            return makeLong((byte) 0, (byte) 0, (byte) 0, (byte) 0, tmp[0], tmp[1], tmp[2], tmp[3]);
        } else {
            return makeLong((byte) 0, (byte) 0, (byte) 0, (byte) 0, tmp[3], tmp[2], tmp[1], tmp[0]);
        }
    }
}
