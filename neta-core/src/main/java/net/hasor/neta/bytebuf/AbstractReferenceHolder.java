/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.bytebuf;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
/**
 * Shared reference-count implementation for pooled or explicitly-owned objects.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-22
 */
public abstract class AbstractReferenceHolder implements ReferenceHolder {
    private static final VarHandle REF_CNT;

    static {
        try {
            REF_CNT = MethodHandles.lookup().findVarHandle(AbstractReferenceHolder.class, "refCnt", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile int refCnt = 1;

    /**
     * Reset the holder to an initial retained state when an instance is reused from a pool.
     */
    protected final void resetRefCnt() {
        REF_CNT.setRelease(this, 1);
    }

    /**
     * Returns whether the holder has already been fully released.
     */
    protected final boolean isReleased() {
        return (int) REF_CNT.get(this) == 0;
    }

    @Override
    public final int refCnt() {
        return (int) REF_CNT.getAcquire(this);
    }

    @Override
    public ReferenceHolder retain() {
        return this.retain(1);
    }

    @Override
    public ReferenceHolder retain(int increment) {
        if (increment <= 0) {
            throw new IllegalArgumentException("increment: " + increment + " (expected: > 0)");
        }
        while (true) {
            int current = (int) REF_CNT.get(this);
            if (current == 0) {
                throw new IllegalStateException("has been released.");
            }
            if (current > Integer.MAX_VALUE - increment) {
                throw new IllegalStateException("refCnt overflow: " + current);
            }
            if (REF_CNT.compareAndSet(this, current, current + increment)) {
                return this;
            }
        }
    }

    @Override
    public final boolean release() {
        return this.release(1);
    }

    @Override
    public final boolean release(int decrement) {
        if (decrement <= 0) {
            throw new IllegalArgumentException("decrement: " + decrement + " (expected: > 0)");
        }
        while (true) {
            int current = (int) REF_CNT.get(this);
            if (current < decrement) {
                throw new IllegalStateException("refCnt: " + current + " (expected: >= " + decrement + ")");
            }
            int next = current - decrement;
            if (!REF_CNT.compareAndSet(this, current, next)) {
                continue;
            }
            if (next == 0) {
                this.deallocate();
                return true;
            }
            return false;
        }
    }

    /**
     * Called exactly once when the reference count reaches zero.
     */
    protected abstract void deallocate();
}
