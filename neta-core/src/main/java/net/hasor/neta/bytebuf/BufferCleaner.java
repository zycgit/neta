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
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import net.hasor.cobble.logging.Logger;

/**
 * Allows to free direct {@link ByteBuffer}s.
 * @author netty, reference io.netty.util.internal.Cleaner、io.netty.util.internal.PlatformDependent0
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
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
