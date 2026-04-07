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
 * Opcode definitions used by websocket frames and internal control flow.
 * <p>
 * Besides standard RFC 6455 values, this enum also contains one internal
 * handshake-complete marker.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public enum WebSocketOpcode {
    /** Continuation frame, opcode {@code 0x0}. */
    CONTINUATION(0x0),
    /** UTF-8 text frame, opcode {@code 0x1}. */
    TEXT(0x1),
    /** Binary frame, opcode {@code 0x2}. */
    BINARY(0x2),
    /** Close-connection control frame, opcode {@code 0x8}. */
    CLOSE(0x8),
    /** Ping control frame, opcode {@code 0x9}. */
    PING(0x9),
    /** Pong control frame, opcode {@code 0xA}. */
    PONG(0xA),
    /** Internal synthetic message type emitted after handshake completion. */
    HANDSHAKE_COMPLETE(-1);

    /**
     * Lookup table for O(1) opcode resolution. Wire-level websocket opcodes range from {@code 0x0} to {@code 0xF}.
     */
    private static final WebSocketOpcode[] LOOKUP = new WebSocketOpcode[16];

    static {
        for (WebSocketOpcode op : values()) {
            if (op.code >= 0 && op.code < LOOKUP.length) {
                LOOKUP[op.code] = op;
            }
        }
    }

    private final int code;

    WebSocketOpcode(int code) {
        this.code = code;
    }

    /**
     * Resolve an integer opcode to the corresponding enum constant.
     * @param code raw opcode value, expected to be in the range 0 to 15
     * @return matching enum constant, or {@code null} if unknown
     */
    public static WebSocketOpcode of(int code) {
        if (code >= 0 && code < LOOKUP.length) {
            return LOOKUP[code];
        }
        return null;
    }

    /**
     * Return the numeric opcode value.
     */
    public int code() {
        return code;
    }
}
