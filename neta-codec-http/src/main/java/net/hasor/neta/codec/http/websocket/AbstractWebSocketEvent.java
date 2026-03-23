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
package net.hasor.neta.codec.http.websocket;
import net.hasor.neta.codec.http.HttpEvent;

/**
 * Base type for WebSocket user events.
 * <p>
 * Provides a common stream id and release lifecycle for handshake, ping/pong, and close events.
 */
public abstract class AbstractWebSocketEvent implements HttpEvent {
    private int     streamId;
    private boolean released;

    public final int streamId() {
        return this.streamId;
    }

    public AbstractWebSocketEvent streamId(int streamId) {
        this.streamId = streamId;
        return this;
    }

    public final void release() {
        if (this.released) {
            return;
        }
        this.released = true;
        this.streamId = 0;
        this.doRelease();
    }

    protected void doRelease() {
    }
}