package net.hasor.cobble.bytebuf;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Provide a way to clean a ByteBuffer on Java9+.
 */
final class CleanerJava9 extends Cleaner {
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
