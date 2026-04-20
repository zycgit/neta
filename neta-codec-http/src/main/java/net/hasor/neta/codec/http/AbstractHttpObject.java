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
package net.hasor.neta.codec.http;
/**
 * Provides shared stream-id and bad-message state handling for default {@link HttpObject} implementations.
 * @param <T> concrete object type
 * @author 赵永春 (zyc@hasor.net)
 */
public abstract class AbstractHttpObject<T extends HttpObject> implements HttpObject {
    private long    streamId;
    private boolean bad;
    private String  badReason;

    /**
     * Return the concrete typed view of the current instance so fluent calls keep the specific return type.
     * @return the current instance
     */
    protected abstract T self();

    @Override
    public long streamId() {
        return this.streamId;
    }

    @Override
    public T streamId(long streamId) {
        this.streamId = streamId;
        return this.self();
    }

    @Override
    public boolean isBad() {
        return this.bad;
    }

    @Override
    public String badReason() {
        return this.badReason;
    }

    @Override
    public T markBad(String reason) {
        this.bad = true;
        this.badReason = reason;
        return this.self();
    }

    /**
     * Overwrite the shared state of this object with the state from another HTTP object.
     * @param source source object
     */
    protected final void inheritHttpObjectState(HttpObject source) {
        if (source == null) {
            return;
        }
        this.streamId = source.streamId();
        this.bad = source.isBad();
        this.badReason = this.bad ? source.badReason() : null;
    }

    /**
     * Set only the bad-message state without triggering any subclass-specific synchronization.
     * @param reason failure reason
     */
    protected final void setBadState(String reason) {
        this.bad = true;
        this.badReason = reason;
    }

    /**
     * Reset the shared HTTP object state for release or reuse.
     */
    protected final void resetHttpObjectState() {
        this.streamId = 0;
        this.bad = false;
        this.badReason = null;
    }
}