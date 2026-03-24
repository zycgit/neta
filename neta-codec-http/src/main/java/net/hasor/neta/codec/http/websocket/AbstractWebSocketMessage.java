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
 * Base implementation for application-level WebSocket message chunks.
 * <p>
 * Stores stream id, chunk sequence, and payload after frame-level processing has been completed.
 */
public abstract class AbstractWebSocketMessage implements WebSocketMessage {
    private int     streamId;
    private int     sequence;
    private int     payloadLength;
    private ByteBuf content;

    protected AbstractWebSocketMessage() {
    }

    protected final void initMessage(int sequence, ByteBuf content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        this.streamId = 0;
        this.sequence = sequence;
        this.payloadLength = content.readableBytes();
        this.content = content.retain();
    }

    protected final void initEmptyMessage(int sequence) {
        this.streamId = 0;
        this.sequence = sequence;
        this.payloadLength = 0;
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
    public int payloadLength() {
        return this.payloadLength;
    }

    @Override
    public void release() {
        if (this.content != null) {
            this.content.release();
            this.content = null;
        }
        this.streamId = 0;
        this.sequence = WebSocketMessage.FINAL_SEQUENCE;
        this.payloadLength = 0;
        this.recycle();
    }

    protected abstract void recycle();

    @Override
    public String toString() {
        return "WebSocketMessage{type=" + this.type() + ", seq=" + this.sequence + ", len=" + this.payloadLength + '}';
    }
}