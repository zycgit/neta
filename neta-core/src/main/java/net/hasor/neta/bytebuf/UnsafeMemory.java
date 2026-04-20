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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.Buffer;
/**
 * Provides fast byte-array access via sun.misc.Unsafe for int/long read/write.
 * All Unsafe access is done through reflection + MethodHandle to avoid any
 * compile-time dependency on sun.misc.Unsafe, ensuring compatibility with
 * JDK 8, 11, 17, 21 and future versions.
 * <p>
 * Falls back gracefully when Unsafe is unavailable. Callers should check {@link #HAS_UNSAFE}.
 * <p>
 * Static final MethodHandles are treated as compile-time constants by the JIT
 * compiler and get fully inlined, providing performance equivalent to direct calls.
 * <p>
 * On x86-64 native byte order is LITTLE_ENDIAN.
 * All methods handle endian conversion automatically.
 * @author Generated for performance optimization
 * @version : 2024-01-01
 */
final class UnsafeMemory {
    static final boolean HAS_UNSAFE;
    static final boolean NATIVE_IS_BIG;

    private static final long BYTE_ARRAY_BASE;
    private static final long DIRECT_ADDRESS_OFFSET;

    // MethodHandles bound to the Unsafe singleton (heap array access: Object base + long offset)
    private static final MethodHandle MH_GET_BYTE;   // (Object, long) -> byte
    private static final MethodHandle MH_PUT_BYTE;   // (Object, long, byte) -> void
    private static final MethodHandle MH_GET_SHORT;  // (Object, long) -> short
    private static final MethodHandle MH_PUT_SHORT;  // (Object, long, short) -> void
    private static final MethodHandle MH_GET_INT;    // (Object, long) -> int
    private static final MethodHandle MH_PUT_INT;    // (Object, long, int) -> void
    private static final MethodHandle MH_GET_LONG;   // (Object, long) -> long
    private static final MethodHandle MH_PUT_LONG;   // (Object, long, long) -> void

    // MethodHandles for direct memory access (long address)
    private static final MethodHandle MH_GET_BYTE_D;  // (long) -> byte
    private static final MethodHandle MH_PUT_BYTE_D;  // (long, byte) -> void
    private static final MethodHandle MH_GET_SHORT_D; // (long) -> short
    private static final MethodHandle MH_PUT_SHORT_D; // (long, short) -> void
    private static final MethodHandle MH_GET_INT_D;   // (long) -> int
    private static final MethodHandle MH_PUT_INT_D;   // (long, int) -> void
    private static final MethodHandle MH_GET_LONG_D;  // (long) -> long
    private static final MethodHandle MH_PUT_LONG_D;  // (long, long) -> void

    static {
        Object unsafe = null;
        long base = 0;
        long addrOffset = -1;
        MethodHandle getByte = null, putByte = null;
        MethodHandle getShort = null, putShort = null;
        MethodHandle getInt = null, putInt = null;
        MethodHandle getLong = null, putLong = null;
        MethodHandle getByteD = null, putByteD = null;
        MethodHandle getShortD = null, putShortD = null;
        MethodHandle getIntD = null, putIntD = null;
        MethodHandle getLongD = null, putLongD = null;

        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = f.get(null);

            // arrayBaseOffset and objectFieldOffset via reflection (init-time only)
            Method abom = unsafeClass.getMethod("arrayBaseOffset", Class.class);
            base = ((Number) abom.invoke(unsafe, byte[].class)).longValue();

            Method ofom = unsafeClass.getMethod("objectFieldOffset", Field.class);
            Field addressField = Buffer.class.getDeclaredField("address");
            addrOffset = ((Number) ofom.invoke(unsafe, addressField)).longValue();

            // Build MethodHandles for heap array access (Object base + long offset)
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            getByte = lookup.findVirtual(unsafeClass, "getByte", MethodType.methodType(byte.class, Object.class, long.class)).bindTo(unsafe);
            putByte = lookup.findVirtual(unsafeClass, "putByte", MethodType.methodType(void.class, Object.class, long.class, byte.class)).bindTo(unsafe);
            getShort = lookup.findVirtual(unsafeClass, "getShort", MethodType.methodType(short.class, Object.class, long.class)).bindTo(unsafe);
            putShort = lookup.findVirtual(unsafeClass, "putShort", MethodType.methodType(void.class, Object.class, long.class, short.class)).bindTo(unsafe);
            getInt = lookup.findVirtual(unsafeClass, "getInt", MethodType.methodType(int.class, Object.class, long.class)).bindTo(unsafe);
            putInt = lookup.findVirtual(unsafeClass, "putInt", MethodType.methodType(void.class, Object.class, long.class, int.class)).bindTo(unsafe);
            getLong = lookup.findVirtual(unsafeClass, "getLong", MethodType.methodType(long.class, Object.class, long.class)).bindTo(unsafe);
            putLong = lookup.findVirtual(unsafeClass, "putLong", MethodType.methodType(void.class, Object.class, long.class, long.class)).bindTo(unsafe);

            // Build MethodHandles for direct memory access (long address)
            getByteD = lookup.findVirtual(unsafeClass, "getByte", MethodType.methodType(byte.class, long.class)).bindTo(unsafe);
            putByteD = lookup.findVirtual(unsafeClass, "putByte", MethodType.methodType(void.class, long.class, byte.class)).bindTo(unsafe);
            getShortD = lookup.findVirtual(unsafeClass, "getShort", MethodType.methodType(short.class, long.class)).bindTo(unsafe);
            putShortD = lookup.findVirtual(unsafeClass, "putShort", MethodType.methodType(void.class, long.class, short.class)).bindTo(unsafe);
            getIntD = lookup.findVirtual(unsafeClass, "getInt", MethodType.methodType(int.class, long.class)).bindTo(unsafe);
            putIntD = lookup.findVirtual(unsafeClass, "putInt", MethodType.methodType(void.class, long.class, int.class)).bindTo(unsafe);
            getLongD = lookup.findVirtual(unsafeClass, "getLong", MethodType.methodType(long.class, long.class)).bindTo(unsafe);
            putLongD = lookup.findVirtual(unsafeClass, "putLong", MethodType.methodType(void.class, long.class, long.class)).bindTo(unsafe);
        } catch (Throwable ignored) {
            // Unsafe not available — HAS_UNSAFE will be false
        }

        BYTE_ARRAY_BASE = base;
        DIRECT_ADDRESS_OFFSET = addrOffset;
        HAS_UNSAFE = unsafe != null;
        NATIVE_IS_BIG = (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.BIG_ENDIAN);

        MH_GET_BYTE = getByte;
        MH_PUT_BYTE = putByte;
        MH_GET_SHORT = getShort;
        MH_PUT_SHORT = putShort;
        MH_GET_INT = getInt;
        MH_PUT_INT = putInt;
        MH_GET_LONG = getLong;
        MH_PUT_LONG = putLong;
        MH_GET_BYTE_D = getByteD;
        MH_PUT_BYTE_D = putByteD;
        MH_GET_SHORT_D = getShortD;
        MH_PUT_SHORT_D = putShortD;
        MH_GET_INT_D = getIntD;
        MH_PUT_INT_D = putIntD;
        MH_GET_LONG_D = getLongD;
        MH_PUT_LONG_D = putLongD;
    }

    // -- byte (heap array) ---------------------------------------------------

    static byte getByte(byte[] arr, int idx) {
        try {
            return (byte) MH_GET_BYTE.invokeExact((Object) arr, BYTE_ARRAY_BASE + idx);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void putByte(byte[] arr, int idx, byte val) {
        try {
            MH_PUT_BYTE.invokeExact((Object) arr, BYTE_ARRAY_BASE + idx, val);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // -- int16 ---------------------------------------------------------------

    static short getInt16(byte[] arr, int idx, boolean bigEndian) {
        try {
            short raw = (short) MH_GET_SHORT.invokeExact((Object) arr, BYTE_ARRAY_BASE + idx);
            return (bigEndian == NATIVE_IS_BIG) ? raw : Short.reverseBytes(raw);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void putInt16(byte[] arr, int idx, short val, boolean bigEndian) {
        try {
            MH_PUT_SHORT.invokeExact((Object) arr, BYTE_ARRAY_BASE + idx, (bigEndian == NATIVE_IS_BIG) ? val : Short.reverseBytes(val));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // -- int32 ---------------------------------------------------------------

    static int getInt32(byte[] arr, int idx, boolean bigEndian) {
        try {
            int raw = (int) MH_GET_INT.invokeExact((Object) arr, BYTE_ARRAY_BASE + idx);
            return (bigEndian == NATIVE_IS_BIG) ? raw : Integer.reverseBytes(raw);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void putInt32(byte[] arr, int idx, int val, boolean bigEndian) {
        try {
            MH_PUT_INT.invokeExact((Object) arr, BYTE_ARRAY_BASE + idx, (bigEndian == NATIVE_IS_BIG) ? val : Integer.reverseBytes(val));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // -- uint32 (stored as int, value passed as long) ------------------------

    static void putUInt32(byte[] arr, int idx, long val, boolean bigEndian) {
        putInt32(arr, idx, (int) val, bigEndian);
    }

    // -- int64 ---------------------------------------------------------------

    static long getInt64(byte[] arr, int idx, boolean bigEndian) {
        try {
            long raw = (long) MH_GET_LONG.invokeExact((Object) arr, BYTE_ARRAY_BASE + idx);
            return (bigEndian == NATIVE_IS_BIG) ? raw : Long.reverseBytes(raw);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void putInt64(byte[] arr, int idx, long val, boolean bigEndian) {
        try {
            MH_PUT_LONG.invokeExact((Object) arr, BYTE_ARRAY_BASE + idx, (bigEndian == NATIVE_IS_BIG) ? val : Long.reverseBytes(val));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // -- Direct buffer address -----------------------------------------------

    /** Get the native memory address of a direct ByteBuffer. Returns 0 if not available. */
    static long getDirectAddress(java.nio.ByteBuffer bb) {
        if (DIRECT_ADDRESS_OFFSET >= 0 && bb != null && bb.isDirect()) {
            try {
                return (long) MH_GET_LONG.invokeExact((Object) bb, DIRECT_ADDRESS_OFFSET);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }
        return 0;
    }

    // -- Direct memory access (off-heap) -------------------------------------

    static byte getByteDirect(long addr) {
        try {
            return (byte) MH_GET_BYTE_D.invokeExact(addr);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void putByteDirect(long addr, byte val) {
        try {
            MH_PUT_BYTE_D.invokeExact(addr, val);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static short getInt16Direct(long addr, boolean bigEndian) {
        try {
            short raw = (short) MH_GET_SHORT_D.invokeExact(addr);
            return (bigEndian == NATIVE_IS_BIG) ? raw : Short.reverseBytes(raw);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void putInt16Direct(long addr, short val, boolean bigEndian) {
        try {
            MH_PUT_SHORT_D.invokeExact(addr, (bigEndian == NATIVE_IS_BIG) ? val : Short.reverseBytes(val));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int getInt32Direct(long addr, boolean bigEndian) {
        try {
            int raw = (int) MH_GET_INT_D.invokeExact(addr);
            return (bigEndian == NATIVE_IS_BIG) ? raw : Integer.reverseBytes(raw);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void putInt32Direct(long addr, int val, boolean bigEndian) {
        try {
            MH_PUT_INT_D.invokeExact(addr, (bigEndian == NATIVE_IS_BIG) ? val : Integer.reverseBytes(val));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long getInt64Direct(long addr, boolean bigEndian) {
        try {
            long raw = (long) MH_GET_LONG_D.invokeExact(addr);
            return (bigEndian == NATIVE_IS_BIG) ? raw : Long.reverseBytes(raw);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void putInt64Direct(long addr, long val, boolean bigEndian) {
        try {
            MH_PUT_LONG_D.invokeExact(addr, (bigEndian == NATIVE_IS_BIG) ? val : Long.reverseBytes(val));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Rethrow any Throwable as unchecked without wrapping. */
    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        throw new UndeclaredThrowableException(t);
    }

    private static final class UndeclaredThrowableException extends RuntimeException {
        UndeclaredThrowableException(Throwable cause) {
            super(cause);
        }
    }
}
