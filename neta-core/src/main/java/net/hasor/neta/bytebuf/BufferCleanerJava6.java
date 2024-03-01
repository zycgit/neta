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

/**
 * Allows to free direct {@link ByteBuffer} by using Cleaner.
 * For more details see <a href="https://github.com/netty/netty/issues/2604">#2604</a>.
 * @author netty ,reference io.netty.util.internal.CleanerJava6
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
final class BufferCleanerJava6 extends BufferCleaner {
    private static final long   CLEANER_FIELD_OFFSET;
    private static final Method CLEAN_METHOD;
    private static final Field  CLEANER_FIELD;

    static {
        long fieldOffset = -1;
        Method clean = null;
        Field cleanerField = null;

        try {
            final Object cleaner;
            // If we have sun.misc.Unsafe we will use it as its faster then using reflection, otherwise let us try reflection as last resort.
            final ByteBuffer direct = ByteBuffer.allocateDirect(1);
            cleanerField = direct.getClass().getDeclaredField("cleaner");
            cleanerField.setAccessible(true); // We need to make it accessible if we do not use Unsafe as we will access it via reflection.

            if (!hasUnsafe()) {
                fieldOffset = UNSAFE.objectFieldOffset(cleanerField);
                cleaner = UNSAFE.getObject(direct, fieldOffset);
            } else {
                fieldOffset = -1;
                cleaner = cleanerField.get(direct);
            }

            clean = cleaner.getClass().getDeclaredMethod("clean");
            clean.invoke(cleaner);
            logger.debug("java.nio.ByteBuffer.cleaner(): available");
        } catch (Throwable e) {
            // We don't have ByteBuffer.cleaner().
            fieldOffset = -1;
            clean = null;
            cleanerField = null;

            if (logger.isDebugEnabled()) {
                logger.warn("java.nio.ByteBuffer.cleaner(): unavailable", e);
            }
        }

        CLEANER_FIELD = cleanerField;
        CLEANER_FIELD_OFFSET = fieldOffset;
        CLEAN_METHOD = clean;
    }

    static boolean isSupported() {
        return CLEANER_FIELD_OFFSET != -1 || CLEANER_FIELD != null;
    }

    @Override
    public void freeDirectBuffer(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            return;
        }

        try {
            final Object cleaner;
            // If CLEANER_FIELD_OFFSET == -1 we need to use reflection to access the cleaner, otherwise we can use sun.misc.Unsafe.
            if (CLEANER_FIELD_OFFSET == -1) {
                cleaner = CLEANER_FIELD.get(buffer);
            } else {
                cleaner = UNSAFE.getObject(buffer, CLEANER_FIELD_OFFSET);
            }

            if (cleaner != null) {
                CLEAN_METHOD.invoke(cleaner);
            }
        } catch (Throwable e) {
            UNSAFE.throwException(e);
        }
    }
}
