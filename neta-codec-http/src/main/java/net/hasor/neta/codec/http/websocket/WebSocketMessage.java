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
 * Message-layer payload abstraction for websocket traffic.
 * <p>
 * Inbound handlers typically use this type to represent staged TEXT or BINARY
 * message chunks and preserve fragmentation order through the {@code sequence}
 * field.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-15
 */
public interface WebSocketMessage extends HttpObject {
    /**
     * Marker for the final chunk.
     * When used alone, it means the entire message consists of this single chunk.
     */
    int FINAL_SEQUENCE = -1;
    /**
     * Marker for the first chunk in a fragmented message stream.
     */
    int START_SEQUENCE = 0;

    /**
     * Return the logical message type.
     */
    WebSocketOpcode type();

    /**
     * Return the position of this chunk within the websocket message stream.
     * <p>
     * {@code -1} means the final chunk, {@code 0} means the first chunk, and
     * positive numbers represent subsequent chunks in order.
     */
    int sequence();

    /**
     * Set the chunk sequence and return the current message instance.
     * @param sequence chunk sequence
     * @return current message instance
     */
    WebSocketMessage sequence(int sequence);

    /**
     * Return the raw payload content.
     * <p>
     * The returned buffer is a borrowed reference owned by this message object.
     * Callers that need to keep the payload beyond the lifetime of the current
     * message, or pass it to code that will release the buffer, must first call
     * {@link ByteBuf#retain()} or {@link ByteBuf#copy()} explicitly.
     * </p>
     */
    ByteBuf content();

    /**
     * Return the payload length represented by this message chunk.
     */
    int payloadLength();
}
