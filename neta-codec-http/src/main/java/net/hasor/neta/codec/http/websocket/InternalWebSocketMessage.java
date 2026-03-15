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

/** Internal message wrapper used when protocol handlers need to re-enter the outbound pipeline with control semantics. */
final class InternalWebSocketMessage extends AbstractWebSocketMessage {
    private WebSocketOpcode opcode;

    private InternalWebSocketMessage() {
    }

    static InternalWebSocketMessage of(WebSocketOpcode opcode, ByteBuf content) {
        if (opcode != WebSocketOpcode.PING && opcode != WebSocketOpcode.PONG && opcode != WebSocketOpcode.CLOSE) {
            throw new IllegalArgumentException("unsupported control opcode: " + opcode);
        }
        InternalWebSocketMessage message = new InternalWebSocketMessage();
        message.opcode = opcode;
        message.initMessage(WebSocketMessage.FINAL_SEQUENCE, content == null ? ByteBuf.EMPTY : content);
        return message;
    }

    @Override
    public WebSocketOpcode type() {
        return this.opcode;
    }

    @Override
    protected void recycle() {
        this.opcode = null;
    }
}