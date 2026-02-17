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
package net.hasor.neta.bytebuf;

import java.lang.reflect.Field;

/**
 * Provides fast byte-array access via sun.misc.Unsafe for int/long read/write.
 * Falls back gracefully when Unsafe is unavailable — callers should check {@link #HAS_UNSAFE}.
 * <p>
 * On x86-64 native byte order is LITTLE_ENDIAN.
 * All methods handle endian conversion automatically.
 *
 * @author Generated for performance optimization
 * @version : 2024-01-01
 */
final class UnsafeMemory {
    static final boolean HAS_UNSAFE;
    static final boolean NATIVE_IS_BIG;

    private static final sun.misc.Unsafe UNSAFE;
    private static final long            BYTE_ARRAY_BASE;
    private static final long            DIRECT_ADDRESS_OFFSET;

    static {
        sun.misc.Unsafe u = null;
        long base = 0;
        long addrOffset = -1;
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            u = (sun.misc.Unsafe) f.get(null);
            base = u.arrayBaseOffset(byte[].class);
            // Get offset of the 'address' field in java.nio.Buffer for direct buffer access
            Field addressField = java.nio.Buffer.class.getDeclaredField("address");
            addrOffset = u.objectFieldOffset(addressField);
        } catch (Throwable ignored) {
            // Unsafe not available
        }
        UNSAFE = u;
        BYTE_ARRAY_BASE = base;
        DIRECT_ADDRESS_OFFSET = addrOffset;
        HAS_UNSAFE = u != null;
        NATIVE_IS_BIG = (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.BIG_ENDIAN);
    }

    // -- byte (heap array, no bounds check via Unsafe) -------------------

    static byte getByte(byte[] arr, int idx) {
        return UNSAFE.getByte(arr, BYTE_ARRAY_BASE + idx);
    }

    static void putByte(byte[] arr, int idx, byte val) {
        UNSAFE.putByte(arr, BYTE_ARRAY_BASE + idx, val);
    }

    // -- int16 ---------------------------------------------------------------

    static short getInt16(byte[] arr, int idx, boolean bigEndian) {
        short raw = UNSAFE.getShort(arr, BYTE_ARRAY_BASE + idx);
        return (bigEndian == NATIVE_IS_BIG) ? raw : Short.reverseBytes(raw);
    }

    static void putInt16(byte[] arr, int idx, short val, boolean bigEndian) {
        UNSAFE.putShort(arr, BYTE_ARRAY_BASE + idx, (bigEndian == NATIVE_IS_BIG) ? val : Short.reverseBytes(val));
    }

    // -- int32 ---------------------------------------------------------------

    static int getInt32(byte[] arr, int idx, boolean bigEndian) {
        int raw = UNSAFE.getInt(arr, BYTE_ARRAY_BASE + idx);
        return (bigEndian == NATIVE_IS_BIG) ? raw : Integer.reverseBytes(raw);
    }

    static void putInt32(byte[] arr, int idx, int val, boolean bigEndian) {
        UNSAFE.putInt(arr, BYTE_ARRAY_BASE + idx, (bigEndian == NATIVE_IS_BIG) ? val : Integer.reverseBytes(val));
    }

    // -- uint32 (stored as int, value passed as long) ------------------------

    static void putUInt32(byte[] arr, int idx, long val, boolean bigEndian) {
        putInt32(arr, idx, (int) val, bigEndian);
    }

    // -- int64 ---------------------------------------------------------------

    static long getInt64(byte[] arr, int idx, boolean bigEndian) {
        long raw = UNSAFE.getLong(arr, BYTE_ARRAY_BASE + idx);
        return (bigEndian == NATIVE_IS_BIG) ? raw : Long.reverseBytes(raw);
    }

    static void putInt64(byte[] arr, int idx, long val, boolean bigEndian) {
        UNSAFE.putLong(arr, BYTE_ARRAY_BASE + idx, (bigEndian == NATIVE_IS_BIG) ? val : Long.reverseBytes(val));
    }

    // -- Direct buffer address -----------------------------------------------

    /** Get the native memory address of a direct ByteBuffer. Returns 0 if not available. */
    static long getDirectAddress(java.nio.ByteBuffer bb) {
        if (DIRECT_ADDRESS_OFFSET >= 0 && bb != null && bb.isDirect()) {
            return UNSAFE.getLong(bb, DIRECT_ADDRESS_OFFSET);
        }
        return 0;
    }

    // -- Direct memory access (off-heap) -------------------------------------

    static byte getByteDirect(long addr) {
        return UNSAFE.getByte(addr);
    }

    static void putByteDirect(long addr, byte val) {
        UNSAFE.putByte(addr, val);
    }

    static short getInt16Direct(long addr, boolean bigEndian) {
        short raw = UNSAFE.getShort(addr);
        return (bigEndian == NATIVE_IS_BIG) ? raw : Short.reverseBytes(raw);
    }

    static void putInt16Direct(long addr, short val, boolean bigEndian) {
        UNSAFE.putShort(addr, (bigEndian == NATIVE_IS_BIG) ? val : Short.reverseBytes(val));
    }

    static int getInt32Direct(long addr, boolean bigEndian) {
        int raw = UNSAFE.getInt(addr);
        return (bigEndian == NATIVE_IS_BIG) ? raw : Integer.reverseBytes(raw);
    }

    static void putInt32Direct(long addr, int val, boolean bigEndian) {
        UNSAFE.putInt(addr, (bigEndian == NATIVE_IS_BIG) ? val : Integer.reverseBytes(val));
    }

    static long getInt64Direct(long addr, boolean bigEndian) {
        long raw = UNSAFE.getLong(addr);
        return (bigEndian == NATIVE_IS_BIG) ? raw : Long.reverseBytes(raw);
    }

    static void putInt64Direct(long addr, long val, boolean bigEndian) {
        UNSAFE.putLong(addr, (bigEndian == NATIVE_IS_BIG) ? val : Long.reverseBytes(val));
    }
}
