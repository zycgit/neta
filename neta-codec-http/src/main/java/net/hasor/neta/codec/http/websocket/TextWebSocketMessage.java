/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;
import net.hasor.cobble.ref.RecycleObjectPool;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Pooled text message chunk.
 * <p>
 * Represents application-visible TEXT data after frame decoding or before frame encoding.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-15
 */
public final class TextWebSocketMessage extends AbstractWebSocketMessage {
    private static final RecycleObjectPool<TextWebSocketMessage> RECYCLER = new RecycleObjectPool<>(//
            TextWebSocketMessage::new, TextWebSocketMessage::resetState, TextWebSocketMessage::onRecycle);

    private TextWebSocketMessage() {
    }

    private void resetState() {
        this.resetHttpObjectState();
    }

    private void onRecycle() {
        this.resetHttpObjectState();
    }

    /**
     * Create a text message that is also the final chunk.
     * @param content payload content
     * @return text message object
     */
    public static TextWebSocketMessage request(ByteBuf content) {
        return request(WebSocketMessage.FINAL_SEQUENCE, content);
    }

    /**
     * Create a text message with the specified chunk sequence.
     * @param sequence chunk sequence
     * @param content payload content whose ownership is transferred to the message
     * @return text message object
     */
    public static TextWebSocketMessage request(int sequence, ByteBuf content) {
        TextWebSocketMessage msg = RECYCLER.get();
        msg.initMessage(sequence, content);
        return msg;
    }

    /**
     * Return the message type, always {@link WebSocketOpcode#TEXT}.
     */
    @Override
    public WebSocketOpcode type() {
        return WebSocketOpcode.TEXT;
    }

    /**
     * Return the object to the recycle pool.
     */
    @Override
    protected void recycle() {
        RECYCLER.recycle(this);
    }
}
