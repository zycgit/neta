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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Provide a way to clean a ByteBuffer on Java9+.
 * For more details see <a href="https://github.com/netty/netty/issues/2604">#2604</a>.
 * @author netty ,reference io.netty.util.internal.CleanerJava9
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
final class BufferCleanerJava9 extends BufferCleaner {
    private static final Method INVOKE_CLEANER;

    static {
        Method method = null;
        if (hasUnsafe()) {
            try {
                ByteBuffer buffer = ByteBuffer.allocateDirect(1);
                // See https://bugs.openjdk.java.net/browse/JDK-8171377
                method = UNSAFE.getClass().getDeclaredMethod("invokeCleaner", ByteBuffer.class);
                method.invoke(UNSAFE, buffer);
                logger.debug("java.nio.ByteBuffer.cleaner(): available");
            } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
                if (logger.isDebugEnabled()) {
                    logger.warn("java.nio.ByteBuffer.cleaner(): unavailable", e);
                }
            }
        } else {
            logger.debug("java.nio.ByteBuffer.cleaner(): unavailable");
        }

        INVOKE_CLEANER = method;
    }

    static boolean isSupported() {
        return INVOKE_CLEANER != null;
    }

    @Override
    public void freeDirectBuffer(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            return;
        }

        // Try to minimize overhead when there is no SecurityManager present.
        //    See https://bugs.openjdk.java.net/browse/JDK-8191053.
        try {
            INVOKE_CLEANER.invoke(UNSAFE, buffer);
        } catch (Throwable e) {
            UNSAFE.throwException(e);
        }
    }
}
