/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.logging.Logger;
/**
 * A simplified ResourceLeakDetector based on Netty's implementation.
 * Uses ThreadLocal sampling counter to avoid global AtomicInteger CAS contention.
 */
public class ResourceLeakDetector<T> {
    private static final Logger                   logger            = Logger.getLogger(ResourceLeakDetector.class);
    private static final Level                    level;
    private static final int                      SAMPLING_INTERVAL = 128;
    private static final int                      SAMPLING_MASK     = SAMPLING_INTERVAL - 1;
    private static final ThreadLocal<int[]>       sampleCounter     = ThreadLocal.withInitial(() -> new int[] { 0 });
    private static final ReferenceQueue<Object>   refQueue          = new ReferenceQueue<>();
    private static final Set<DefaultResourceLeak> allLeaks          = Collections.newSetFromMap(new ConcurrentHashMap<>());

    static {
        String levelStr;
        try {
            levelStr = System.getProperty("neta.bytebuf.leakDetection", "simple");
        } catch (SecurityException e) {
            levelStr = "simple";
        }
        switch (levelStr.toLowerCase().trim()) {
            case "disabled":
                level = Level.DISABLED;
                break;
            case "paranoid":
                level = Level.PARANOID;
                break;
            case "simple":
            default:
                level = Level.SIMPLE;
                break;
        }
    }

    private final String resourceType;

    public ResourceLeakDetector(Class<?> resourceType) {
        this(resourceType.getSimpleName());
    }

    public ResourceLeakDetector(String resourceType) {
        this.resourceType = resourceType;
    }

    public static Level getLevel() {
        return level;
    }

    public ResourceLeak open(T obj) {
        if (level == Level.DISABLED) {
            return NoopLeak.INSTANCE;
        }
        if (level == Level.SIMPLE) {
            int[] counter = sampleCounter.get();
            if ((counter[0]++ & SAMPLING_MASK) != 0) {
                return NoopLeak.INSTANCE;
            }
            // Only check for leaked resources when we're about to create a new tracker
            reportLeak();
        } else {
            // PARANOID mode: always report
            reportLeak();
        }
        return new DefaultResourceLeak(obj, level == Level.PARANOID);
    }

    private void reportLeak() {
        for (;;) {
            DefaultResourceLeak ref = (DefaultResourceLeak) refQueue.poll();
            if (ref == null) {
                break;
            }
            if (!ref.dispose()) {
                continue;
            }

            String records = ref.toString();
            if (records.isEmpty()) {
                logger.error("LEAK: " + resourceType + ".release() was not called before it's garbage-collected. Enable advanced leak detection to find out where the leak occurred.");
            } else {
                logger.error("LEAK: " + resourceType + ".release() was not called before it's garbage-collected. See leak detection log for details." + System.lineSeparator() + records);
            }
        }
    }

    /** Detection level: DISABLED skips all tracking, SIMPLE samples without stack traces, PARANOID tracks all with stack traces. */
    public enum Level {
        DISABLED,
        SIMPLE,
        PARANOID
    }

    public interface ResourceLeak {
        void record();

        void record(Object hint);

        boolean close();
    }

    private static final class NoopLeak implements ResourceLeak {
        static final NoopLeak INSTANCE = new NoopLeak();

        @Override
        public void record() {
        }

        @Override
        public void record(Object hint) {
        }

        @Override
        public boolean close() {
            return true;
        }
    }

    private static final class DefaultResourceLeak extends PhantomReference<Object> implements ResourceLeak {
        private final String        creationRecord;
        private final AtomicBoolean freed;

        DefaultResourceLeak(Object referent, boolean captureStackTrace) {
            super(referent, refQueue);
            this.creationRecord = captureStackTrace ? Record.getRecord() : "";
            this.freed = new AtomicBoolean(false);
            allLeaks.add(this);
        }

        @Override
        public void record() {
            // simplified: we only record creation for now
        }

        @Override
        public void record(Object hint) {
            // simplified
        }

        boolean dispose() {
            allLeaks.remove(this);
            return freed.compareAndSet(false, true);
        }

        @Override
        public boolean close() {
            return dispose();
        }

        @Override
        public String toString() {
            return creationRecord;
        }
    }

    private static class Record {
        static String getRecord() {
            // simplified stack trace capture
            StringBuilder buf = new StringBuilder();
            StackTraceElement[] stackTrace = new Throwable().getStackTrace();
            for (StackTraceElement e : stackTrace) {
                // skip internal classes
                String c = e.getClassName();
                if (c.startsWith(ResourceLeakDetector.class.getName())) {
                    continue;
                }
                buf.append("\tat ").append(e).append(System.lineSeparator());
            }
            return buf.toString();
        }
    }
}
