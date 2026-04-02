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
 * Shared base type for HTTP-related event objects.
 * <p>
 * Provides a protocol-neutral stream identifier and a one-shot release lifecycle so protocol
 * families such as HTTP/2, HTTP/3, and WebSocket do not each need to reimplement the same event
 * bookkeeping.
 */
public abstract class AbstractHttpEvent implements HttpEvent {
    private long    streamId;
    private boolean released;

    @Override
    public final long streamId() {
        return this.streamId;
    }

    @Override
    public AbstractHttpEvent streamId(long streamId) {
        this.streamId = streamId;
        return this;
    }

    @Override
    public final void release() {
        if (this.released) {
            return;
        }
        this.released = true;
        this.streamId = 0L;
        this.doRelease();
    }

    protected void doRelease() {
    }
}