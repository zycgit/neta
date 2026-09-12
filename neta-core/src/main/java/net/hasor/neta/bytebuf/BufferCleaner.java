/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import net.hasor.cobble.logging.Logger;
/**
 * Abstraction for eagerly releasing native memory held by direct
 * {@link java.nio.ByteBuffer} instances.
 * <p>Direct buffers live outside the Java heap, so waiting for GC can keep a
 * large amount of off-heap memory pinned longer than desired. This helper
 * exposes {@link #freeDirectBuffer(java.nio.ByteBuffer)} and selects the
 * appropriate cleanup strategy for the running JDK:
 * <ul>
 *   <li><b>JDK 6-8:</b> access the internal {@code cleaner} object of the direct
 *       buffer and invoke its {@code clean()} method
 *       ({@link BufferCleanerJava6}).</li>
 *   <li><b>JDK 9+:</b> invoke {@code sun.misc.Unsafe#invokeCleaner(ByteBuffer)}
 *       reflectively ({@link BufferCleanerJava9}).</li>
 * </ul>
 * <p>The class reflectively acquires {@code sun.misc.Unsafe} during static
 * initialization. If that is not permitted, {@link ByteBufUtils#CLEANER} stays
 * {@code null} and callers must fall back to ordinary GC-driven cleanup.
 * @author netty, reference io.netty.util.internal.Cleaner、io.netty.util.internal.PlatformDependent0
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 * @see ByteBufUtils#CLEANER
 */
public abstract class BufferCleaner {
    protected static final Logger   logger = Logger.getLogger(BufferCleaner.class);
    protected static final Object   UNSAFE;
    protected static final Class<?> UNSAFE_CLASS;
    protected static final Method   UNSAFE_THROW_METHOD;

    // ensure unsafe
    static {
        Object unsafe = null;
        Class<?> unsafeClass = null;
        Method throwMethod = null;

        try {
            unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            unsafe = unsafeField.get(null);
        } catch (NoSuchFieldException | IllegalAccessException | SecurityException | ClassNotFoundException e) {
            if (logger.isTraceEnabled()) {
                logger.warn("sun.misc.Unsafe: unavailable", e);
            } else {
                logger.info("sun.misc.Unsafe: unavailable: " + e.getMessage());
            }
        }

        if (unsafeClass != null) {
            try {
                throwMethod = unsafeClass.getMethod("throwException", Throwable.class);
                throwMethod.setAccessible(true);
            } catch (NoSuchMethodException e) {
                if (logger.isTraceEnabled()) {
                    logger.warn("sun.misc.Unsafe: unsupport throwException", e);
                } else {
                    logger.info("sun.misc.Unsafe: unsupport throwException: " + e.getMessage());
                }
            }
        }
        UNSAFE_THROW_METHOD = throwMethod;
        UNSAFE_CLASS = unsafeClass;
        UNSAFE = unsafe;
    }

    public static boolean hasUnsafe() {
        return UNSAFE != null;
    }

    /** Free a direct {@link ByteBuffer} if possible */
    public abstract void freeDirectBuffer(ByteBuffer buffer);
}
