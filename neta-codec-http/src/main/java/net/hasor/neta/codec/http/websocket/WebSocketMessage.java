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
 * An application-visible WebSocket data message chunk produced by {@link WebSocketInboundHandler}.
 * <p>
 * Unlike {@link WebSocketFrame}, a {@code WebSocketMessage} is the business-side TEXT/BINARY
 * stream view. Fragmented websocket messages are exposed as a sequence of message chunks rather
 * than being reassembled into one complete payload.
 * <p>
 * Sequence rules:
 * <ul>
 *   <li>{@code 0}: start chunk of a fragmented message.</li>
 *   <li>{@code 1..n}: middle chunks of the same fragmented message.</li>
 *   <li>{@code -1}: final chunk. If it appears without a prior {@code 0}, the chunk is both start and end.</li>
 * </ul>
 * <p>
 * Only TEXT/BINARY application data is modeled as message flow here. Control semantics such as
 * ping/pong/close are handled through websocket frames and user events.
 */
public interface WebSocketMessage extends HttpObject {
    /** Final chunk marker. When used alone it means start and end in one chunk. */
    int FINAL_SEQUENCE = -1;
    /** First chunk marker of a fragmented message stream. */
    int START_SEQUENCE = 0;

    /** Returns the message type. */
    WebSocketOpcode type();

    /**
     * Returns the chunk sequence in the websocket message stream.
     * <p>
     * {@code -1} means final chunk, {@code 0} means first chunk, and positive values mean
     * continuation chunks in order.
     */
    int sequence();

    /** Sets the chunk sequence and returns this message instance. */
    WebSocketMessage sequence(int sequence);

    /** Returns the raw payload content. */
    ByteBuf content();
}
