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
import net.hasor.cobble.ref.RecycleObjectPool.ObjHandler;
import net.hasor.neta.bytebuf.ByteBuf;

/**
 * Pooled binary message chunk.
 * <p>
 * Represents application-visible BINARY data after frame decoding or before frame encoding.
 */
public final class BinaryWebSocketMessage extends AbstractWebSocketMessage {
    private static final int                                RECYCLE_INDEX   = RecycleObjectPool.registerType();
    private static final ObjHandler<BinaryWebSocketMessage> RECYCLE_HANDLER = new ObjHandler<BinaryWebSocketMessage>() {
        @Override
        public BinaryWebSocketMessage create() {
            return new BinaryWebSocketMessage();
        }

        @Override
        public void free(BinaryWebSocketMessage tar) {
            RecycleObjectPool.free(RECYCLE_INDEX, tar);
        }
    };

    private BinaryWebSocketMessage() {
    }

    public static BinaryWebSocketMessage request(ByteBuf content) {
        return request(WebSocketMessage.FINAL_SEQUENCE, content);
    }

    public static BinaryWebSocketMessage request(int sequence, ByteBuf content) {
        BinaryWebSocketMessage msg = RecycleObjectPool.get(RECYCLE_INDEX, RECYCLE_HANDLER);
        msg.initMessage(sequence, content);
        return msg;
    }

    @Override
    public WebSocketOpcode type() {
        return WebSocketOpcode.BINARY;
    }

    @Override
    protected void recycle() {
        RECYCLE_HANDLER.free(this);
    }
}