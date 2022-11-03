package net.hasor.cobble.bytebuf;
import net.hasor.cobble.logging.Logger;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;

abstract class Cleaner {
    protected static final Logger logger = Logger.getLogger(Cleaner.class);
    protected static final Unsafe UNSAFE;

    public static boolean hasUnsafe() {
        return UNSAFE != null;
    }

    /** Free a direct {@link ByteBuffer} if possible */
    public abstract void freeDirectBuffer(ByteBuffer buffer);

    // ensure unsafe
    static {
        Unsafe unsafe = null;
        boolean storeFenceSupport = false;
        boolean copyMemorySupport = false;
        ByteBuffer direct = ByteBuffer.allocateDirect(1);

        // We always want to try using Unsafe as the access still works on java9 as well and
        // we need it for out native-transports and many optimizations.
        try {
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            unsafe = (Unsafe) unsafeField.get(null);
        } catch (NoSuchFieldException | IllegalAccessException | SecurityException e) {
            if (logger.isTraceEnabled()) {
                logger.warn("sun.misc.Unsafe.copyMemory: unavailable", e);
            } else {
                logger.info("sun.misc.Unsafe.copyMemory: unavailable: " + e.getMessage());
            }
        }

        // ensure the unsafe supports all necessary methods to work around the mistake in the latest OpenJDK
        // https://github.com/netty/netty/issues/1061
        // https://www.mail-archive.com/jdk6-dev@openjdk.java.net/msg00698.html
        if (unsafe != null) {
            try {
                unsafe.getClass().getDeclaredMethod("copyMemory", Object.class, long.class, Object.class, long.class, long.class);
                copyMemorySupport = true;
            } catch (NoSuchMethodException | SecurityException e) {
                // Unsafe.copyMemory(Object, long, Object, long, long) unavailable.
                copyMemorySupport = false;
                if (logger.isTraceEnabled()) {
                    logger.warn("sun.misc.Unsafe.copyMemory: unavailable", e);
                } else {
                    logger.info("sun.misc.Unsafe.copyMemory: unavailable: " + e.getMessage());
                }
            }
        }

        // ensure Unsafe::storeFence to be available: jdk < 8 shouldn't have it
        if (unsafe != null) {
            try {
                unsafe.getClass().getDeclaredMethod("storeFence");
                storeFenceSupport = true;
                logger.debug("sun.misc.Unsafe.storeFence: available");
            } catch (NoSuchMethodException | SecurityException e) {
                storeFenceSupport = false;
                if (logger.isTraceEnabled()) {
                    logger.warn("sun.misc.Unsafe.storeFence: unavailable", e);
                } else {
                    logger.info("sun.misc.Unsafe.storeFence: unavailable: " + e.getMessage());
                }
            }
        }

        // test address
        if (unsafe != null) {
            Field addressField = null;
            try {
                Field field = Buffer.class.getDeclaredField("address");
                // Use Unsafe to read value of the address field. This way it will not fail on JDK9+ which
                // will forbid changing the access level via reflection.
                long offset = unsafe.objectFieldOffset(field);
                long address = unsafe.getLong(direct, offset);

                // if direct really is a direct buffer, address will be non-zero
                if (address == 0) {
                    logger.debug("java.nio.Buffer.address: unavailable");
                } else {
                    addressField = field;
                    logger.debug("java.nio.Buffer.address: available");
                }
            } catch (NoSuchFieldException | SecurityException e) {
                if (logger.isTraceEnabled()) {
                    logger.warn("java.nio.Buffer.address: unavailable", e);
                } else {
                    logger.info("java.nio.Buffer.address: unavailable: " + e.getMessage());
                }
            }

            // If we cannot access the address of a direct buffer, there's no point of using unsafe.
            // Let's just pretend unsafe is unavailable for overall simplicity.
            if (addressField == null) {
                unsafe = null;
            }
        }

        UNSAFE = unsafe;
    }
}
