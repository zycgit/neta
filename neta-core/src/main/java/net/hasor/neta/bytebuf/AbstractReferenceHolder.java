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
/**
 * Shared reference-count implementation for pooled or explicitly-owned objects.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-04-22
 */
public abstract class AbstractReferenceHolder implements ReferenceHolder {
    private int refCnt = 1;

    /**
     * Reset the holder to an initial retained state when an instance is reused from a pool.
     */
    protected final void resetRefCnt() {
        this.refCnt = 1;
    }

    /**
     * Returns whether the holder has already been fully released.
     */
    protected final boolean isReleased() {
        return this.refCnt == 0;
    }

    @Override
    public final int refCnt() {
        return this.refCnt;
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
        int current = this.refCnt;
        if (current == 0) {
            throw new IllegalStateException("has been released.");
        }
        if (current > Integer.MAX_VALUE - increment) {
            throw new IllegalStateException("refCnt overflow: " + current);
        }
        this.refCnt = current + increment;
        return this;
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
        int current = this.refCnt;
        if (current < decrement) {
            throw new IllegalStateException("refCnt: " + current + " (expected: >= " + decrement + ")");
        }
        int next = current - decrement;
        this.refCnt = next;
        if (next == 0) {
            this.deallocate();
            return true;
        }
        return false;
    }

    protected final boolean releaseIfSoleOwner() {
        if (this.refCnt != 1) {
            return false;
        }
        this.refCnt = 0;
        this.deallocate();
        return true;
    }

    /**
     * Called exactly once when the reference count reaches zero.
     */
    protected abstract void deallocate();
}