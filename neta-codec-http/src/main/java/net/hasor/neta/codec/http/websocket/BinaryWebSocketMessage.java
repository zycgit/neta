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
import net.hasor.cobble.ref.RecycleObjectPool;
import net.hasor.neta.bytebuf.ByteBuf;
/**
 * Pooled binary message chunk.
 * <p>
 * Represents application-visible BINARY data after frame decoding or before frame encoding.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-15
 */
public final class BinaryWebSocketMessage extends AbstractWebSocketMessage {
    private static final RecycleObjectPool.Recycler<BinaryWebSocketMessage> RECYCLER = RecycleObjectPool.recycler(//
            BinaryWebSocketMessage::new, BinaryWebSocketMessage::resetState, BinaryWebSocketMessage::onRecycle);

    private BinaryWebSocketMessage() {
    }

    private void resetState() {
        this.resetHttpObjectState();
    }

    private void onRecycle() {
        this.resetHttpObjectState();
    }

    /**
     * Create a binary message that is also the final chunk.
     * @param content payload content
     * @return binary message object
     */
    public static BinaryWebSocketMessage request(ByteBuf content) {
        return request(WebSocketMessage.FINAL_SEQUENCE, content);
    }

    /**
     * Create a binary message with the specified chunk sequence.
     * @param sequence chunk sequence
     * @param content payload content whose ownership is transferred to the message
     * @return binary message object
     */
    public static BinaryWebSocketMessage request(int sequence, ByteBuf content) {
        BinaryWebSocketMessage msg = RECYCLER.get();
        msg.initMessage(sequence, content);
        return msg;
    }

    /**
     * Return the message type, always {@link WebSocketOpcode#BINARY}.
     */
    @Override
    public WebSocketOpcode type() {
        return WebSocketOpcode.BINARY;
    }

    /**
     * Return the object to the recycle pool.
     */
    @Override
    protected void recycle() {
        RECYCLER.recycle(this);
    }
}
