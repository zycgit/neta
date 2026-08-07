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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
/**
 * Provides fast byte-array access for primitive reads/writes.
 * <p>
 * JDK 17 VarHandles keep the heap-array hot paths fast without relying on
 * internal JDK APIs.
 * <p>
 * On x86-64 native byte order is LITTLE_ENDIAN.
 * All methods handle endian conversion automatically.
 * @author Generated for performance optimization
 * @version : 2024-01-01
 */
final class UnsafeMemory {
    static final boolean HAS_FAST_ARRAY_ACCESS = true;
    static final boolean NATIVE_IS_BIG;

    private static final VarHandle SHORT_BE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle SHORT_LE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_BE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    static {
        NATIVE_IS_BIG = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
    }

    // -- byte (heap array) ---------------------------------------------------

    static byte getByte(byte[] arr, int idx) {
        return arr[idx];
    }

    static void putByte(byte[] arr, int idx, byte val) {
        arr[idx] = val;
    }

    // -- int16 ---------------------------------------------------------------

    static short getInt16(byte[] arr, int idx, boolean bigEndian) {
        return (short) (bigEndian ? SHORT_BE : SHORT_LE).get(arr, idx);
    }

    static void putInt16(byte[] arr, int idx, short val, boolean bigEndian) {
        (bigEndian ? SHORT_BE : SHORT_LE).set(arr, idx, val);
    }

    // -- int32 ---------------------------------------------------------------

    static int getInt32(byte[] arr, int idx, boolean bigEndian) {
        return (int) (bigEndian ? INT_BE : INT_LE).get(arr, idx);
    }

    static void putInt32(byte[] arr, int idx, int val, boolean bigEndian) {
        (bigEndian ? INT_BE : INT_LE).set(arr, idx, val);
    }

    // -- uint32 (stored as int, value passed as long) ------------------------

    static void putUInt32(byte[] arr, int idx, long val, boolean bigEndian) {
        putInt32(arr, idx, (int) val, bigEndian);
    }

    // -- int64 ---------------------------------------------------------------

    static long getInt64(byte[] arr, int idx, boolean bigEndian) {
        return (long) (bigEndian ? LONG_BE : LONG_LE).get(arr, idx);
    }

    static void putInt64(byte[] arr, int idx, long val, boolean bigEndian) {
        (bigEndian ? LONG_BE : LONG_LE).set(arr, idx, val);
    }
}
