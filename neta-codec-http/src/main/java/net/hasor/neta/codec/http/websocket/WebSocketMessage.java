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
 * Application-level WebSocket data chunk.
 * <p>
 * Represents TEXT or BINARY payload after frame parsing, with fragmentation preserved through the
 * sequence field instead of eager aggregation.
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

    /** Returns the payload length represented by this message chunk. */
    int payloadLength();
}
