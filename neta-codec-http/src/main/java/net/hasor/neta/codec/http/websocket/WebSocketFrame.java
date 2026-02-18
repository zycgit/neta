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
 * A WebSocket frame as defined in
 * <a href="https://tools.ietf.org/html/rfc6455#section-5">RFC 6455 §5</a>.
 * <p>Fields map directly to the wire format:
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-------+-+-------------+-------------------------------+
 * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 * |I|S|S|S|  (4)  |A|     (7)    |             (16/64)           |
 * |N|V|V|V|       |S|            |   (if payload len==126/127)   |
 * | |1|2|3|       |K|            |                               |
 * +-+-+-+-+-------+-+-------------+-------------------------------+
 * |     Masking-key (if masked)   |  Payload Data ...             |
 * +-------------------------------- - - - - - - - - - - - - - - - +
 * </pre>
 */
public interface WebSocketFrame {

    /** Returns the opcode of this frame. */
    WebSocketOpcode opcode();

    /**
     * Returns {@code true} if the FIN bit is set (final fragment of a message).
     * For control frames this is always {@code true}.
     */
    boolean isFinalFragment();

    /**
     * Returns {@code true} if the MASK bit is set.
     * Client→server frames MUST be masked; server→client frames MUST NOT.
     */
    boolean isMasked();

    /**
     * Returns the 4-byte masking key, or {@code null} if this frame is not masked.
     * The masking key is only meaningful when {@link #isMasked()} is {@code true}.
     */
    byte[] maskingKey();

    /**
     * Returns the (already unmasked) payload of this frame.
     */
    ByteBuf content();
}
