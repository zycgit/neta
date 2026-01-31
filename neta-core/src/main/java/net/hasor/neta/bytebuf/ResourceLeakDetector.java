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
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A simplified ResourceLeakDetector based on Netty's implementation.
 */
public class ResourceLeakDetector<T> {
    private static final ReferenceQueue<Object>   refQueue            = new ReferenceQueue<>();
    private static final Set<DefaultResourceLeak> allLeaks            = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final AtomicBoolean            loggedTooManyActive = new AtomicBoolean();

    private final String resourceType;

    public ResourceLeakDetector(Class<?> resourceType) {
        this(resourceType.getSimpleName());
    }

    public ResourceLeakDetector(String resourceType) {
        this.resourceType = resourceType;
    }

    public ResourceLeak open(T obj) {
        reportLeak();
        return new DefaultResourceLeak(obj);
    }

    private void reportLeak() {
        for (; ; ) {
            DefaultResourceLeak ref = (DefaultResourceLeak) refQueue.poll();
            if (ref == null) {
                break;
            }
            if (!ref.dispose()) {
                continue;
            }

            String records = ref.toString();
            if (records.isEmpty()) {
                System.err.println("LEAK: " + resourceType + ".release() was not called before it's garbage-collected. Enable advanced leak detection to find out where the leak occurred.");
            } else {
                System.err.println("LEAK: " + resourceType + ".release() was not called before it's garbage-collected. See leak detection log for details." + System.lineSeparator() + records);
            }
        }
    }

    public interface ResourceLeak {
        void record();

        void record(Object hint);

        boolean close();
    }

    private static final class DefaultResourceLeak extends PhantomReference<Object> implements ResourceLeak {
        private final String        creationRecord;
        private final AtomicBoolean freed;

        DefaultResourceLeak(Object referent) {
            super(referent, refQueue);
            this.creationRecord = Record.getRecord();
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
            if (dispose()) {
                return true;
            }
            return false;
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
                buf.append("\tat ").append(e.toString()).append(System.lineSeparator());
            }
            return buf.toString();
        }
    }
}
