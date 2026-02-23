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
/**
 * WebSocket frame opcodes as defined in
 * <a href="https://tools.ietf.org/html/rfc6455#section-5.2">RFC 6455 §5.2</a>.
 */
public enum WebSocketOpcode {
    /** Continuation frame (opcode 0x0). */
    CONTINUATION(0x0),
    /** UTF-8 text frame (opcode 0x1). */
    TEXT(0x1),
    /** Binary frame (opcode 0x2). */
    BINARY(0x2),
    /** Connection-close control frame (opcode 0x8). */
    CLOSE(0x8),
    /** Ping control frame (opcode 0x9). */
    PING(0x9),
    /** Pong control frame (opcode 0xA). */
    PONG(0xA);

    /** Lookup table for O(1) opcode resolution (WebSocket opcodes are 0x0-0xF). */
    private static final WebSocketOpcode[] LOOKUP = new WebSocketOpcode[16];

    static {
        for (WebSocketOpcode op : values()) {
            LOOKUP[op.code] = op;
        }
    }

    private final int code;

    WebSocketOpcode(int code) {
        this.code = code;
    }

    /**
     * Resolves an opcode integer to the corresponding enum constant.
     * @param code the raw opcode byte (0-15)
     * @return the matching constant, or {@code null} if unknown
     */
    public static WebSocketOpcode of(int code) {
        if (code >= 0 && code < LOOKUP.length) {
            return LOOKUP[code];
        }
        return null;
    }

    /** Returns the numeric opcode value. */
    public int code() {
        return code;
    }
}
