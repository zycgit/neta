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
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Abstract base class for application-level WebSocket message chunks.
 * <p>
 * Message type distinction and chunk sequence belong here after frame handling, not on the raw
 * transport frame model.
 */
public abstract class AbstractWebSocketMessage implements WebSocketMessage {
    private int     streamId;
    private int     sequence;
    private ByteBuf content;

    protected AbstractWebSocketMessage() {
    }

    protected final void initMessage(int sequence, ByteBuf content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        this.streamId = 0;
        this.sequence = sequence;
        this.content = content.retain();
    }

    protected final void initEmptyMessage(int sequence) {
        this.streamId = 0;
        this.sequence = sequence;
        this.content = ByteBuf.EMPTY.retain();
    }

    @Override
    public int streamId() {
        return this.streamId;
    }

    @Override
    public WebSocketMessage streamId(int streamId) {
        this.streamId = streamId;
        return this;
    }

    @Override
    public int sequence() {
        return this.sequence;
    }

    @Override
    public WebSocketMessage sequence(int sequence) {
        this.sequence = sequence;
        return this;
    }

    @Override
    public ByteBuf content() {
        return this.content;
    }

    @Override
    public void release() {
        if (this.content != null) {
            this.content.release();
            this.content = null;
        }
        this.streamId = 0;
        this.sequence = WebSocketMessage.FINAL_SEQUENCE;
        this.recycle();
    }

    protected abstract void recycle();

    @Override
    public String toString() {
        return "WebSocketMessage{type=" + this.type() + ", seq=" + this.sequence + ", len=" + (this.content != null ? this.content.readableBytes() : 0) + '}';
    }
}