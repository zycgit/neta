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
import net.hasor.neta.codec.http.AbstractHttpObject;

/**
 * Base implementation for application-visible websocket message chunks.
 * <p>
 * After frame-level processing completes, this type stores the stream ID,
 * chunk sequence, and payload content.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public abstract class AbstractWebSocketMessage extends AbstractHttpObject<WebSocketMessage> implements WebSocketMessage {
    private int     sequence;
    private int     payloadLength;
    private ByteBuf content;

    /**
     * Create the base message object.
     */
    protected AbstractWebSocketMessage() {
    }

    /**
     * Initialize the message chunk state.
     * @param sequence chunk sequence
     * @param content payload content
     */
    protected final void initMessage(int sequence, ByteBuf content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        this.resetHttpObjectState();
        this.sequence = sequence;
        this.payloadLength = content.readableBytes();
        this.content = content.retain();
    }

    @Override
    protected WebSocketMessage self() {
        return this;
    }

    /**
     * Return the message chunk sequence.
     */
    @Override
    public int sequence() {
        return this.sequence;
    }

    /**
     * Set the message chunk sequence.
     * @param sequence chunk sequence
     * @return current message object
     */
    @Override
    public WebSocketMessage sequence(int sequence) {
        this.sequence = sequence;
        return this;
    }

    /**
     * Return the message payload content.
     */
    @Override
    public ByteBuf content() {
        return this.content;
    }

    /**
     * Return the message payload length.
     */
    @Override
    public int payloadLength() {
        return this.payloadLength;
    }

    /**
     * Release the payload and reset object state.
     */
    @Override
    public void release() {
        if (this.content != null) {
            this.content.release();
            this.content = null;
        }
        this.resetHttpObjectState();
        this.sequence = WebSocketMessage.FINAL_SEQUENCE;
        this.payloadLength = 0;
        this.recycle();
    }

    /**
     * Return the current object to the reuse mechanism defined by the implementation.
     */
    protected abstract void recycle();

    /**
     * Return a compact summary string for the message.
     */
    @Override
    public String toString() {
        return "WebSocketMessage{type=" + this.type() + ", seq=" + this.sequence + ", len=" + this.payloadLength + '}';
    }
}