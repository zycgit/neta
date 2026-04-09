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
 * Internal control-message wrapper that re-enters the normal message pipeline.
 * <p>
 * This type is used when protocol handlers need to send ping, pong, or close
 * semantics through the ordinary outbound message path.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-22
 */
final class InternalWebSocketMessage extends AbstractWebSocketMessage {
    private WebSocketOpcode opcode;

    private InternalWebSocketMessage() {
    }

    /**
     * Create an internal control message for a supported control opcode.
     * @param opcode control opcode, must be PING, PONG, or CLOSE
     * @param content optional payload content; {@code null} is normalized to an empty buffer
     * @return internal message instance
     */
    static InternalWebSocketMessage of(long streamId, WebSocketOpcode opcode, ByteBuf content) {
        if (opcode != WebSocketOpcode.PING && opcode != WebSocketOpcode.PONG && opcode != WebSocketOpcode.CLOSE) {
            throw new IllegalArgumentException("unsupported control opcode: " + opcode);
        }

        InternalWebSocketMessage message = new InternalWebSocketMessage();
        message.opcode = opcode;
        message.initMessage(WebSocketMessage.FINAL_SEQUENCE, content == null ? ByteBuf.EMPTY : content);
        message.streamId(streamId);
        return message;
    }

    /**
     * Return the wrapped control opcode.
     */
    @Override
    public WebSocketOpcode type() {
        return this.opcode;
    }

    /**
     * Clear opcode state when the object is recycled.
     */
    @Override
    protected void recycle() {
        this.opcode = null;
    }
}