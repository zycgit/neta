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
import net.hasor.neta.codec.http.HttpObject;

/**
 * A complete WebSocket message produced by {@link WebSocketFrameAggregator}.
 * <p>
 * Unlike {@link WebSocketFrame} (which maps 1:1 to wire frames and may be fragments),
 * a {@code WebSocketMessage} represents a fully reassembled application-level message.
 * <p>
 * Message types:
 * <ul>
 *   <li>{@link WebSocketOpcode#TEXT} — UTF-8 text message, retrievable via {@link #text()}.</li>
 *   <li>{@link WebSocketOpcode#BINARY} — binary message, retrievable via {@link #content()}.</li>
 *   <li>{@link WebSocketOpcode#CONTINUATION} — continuation message for explicit fragment-level handling.</li>
 *   <li>{@link WebSocketOpcode#PING} — ping control message.</li>
 *   <li>{@link WebSocketOpcode#PONG} — pong control message.</li>
 *   <li>{@link WebSocketOpcode#CLOSE} — close control message.</li>
 *   <li>{@link WebSocketOpcode#HANDSHAKE_COMPLETE} — synthetic message emitted after handshake success.</li>
 * </ul>
 */
public interface WebSocketMessage extends HttpObject {

    /** Returns the message type. */
    WebSocketOpcode type();

    /** Returns the raw payload content. */
    ByteBuf content();
}
