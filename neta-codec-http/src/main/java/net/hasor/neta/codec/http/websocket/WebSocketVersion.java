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
 * Definitions of supported websocket protocol versions.
 * <p>
 * Used to distinguish the early V0 handshake/frame family from RFC 6455-compatible versions.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-03-15
 */
public enum WebSocketVersion {
    /** Hixie-76 / hybi-00, protocol version {@code 0}. */
    V0(0),
    /** hybi-07, protocol version {@code 7}. */
    V7(7),
    /** hybi-08/10, protocol version {@code 8}. */
    V8(8),
    /** RFC 6455, protocol version {@code 13}. */
    V13(13);

    private final int code;

    WebSocketVersion(int code) {
        this.code = code;
    }

    /**
     * Resolve the protocol version from a {@code Sec-WebSocket-Version} header value.
     * @param version header value such as {@code "13"}, {@code "8"}, or {@code "7"}; empty values are treated as V0
     * @return matching version, or {@code null} if unknown
     */
    public static WebSocketVersion of(String version) {
        if (version == null || version.isEmpty()) {
            return V0;
        }
        switch (version.trim()) {
            case "0":
                return V0;
            case "7":
                return V7;
            case "8":
                return V8;
            case "13":
                return V13;
            default:
                return null;
        }
    }

    /**
     * Resolve the websocket version from a numeric protocol version.
     * @param version numeric version value
     * @return matching version, or {@code null} if unknown
     */
    public static WebSocketVersion of(int version) {
        switch (version) {
            case 0:
                return V0;
            case 7:
                return V7;
            case 8:
                return V8;
            case 13:
                return V13;
            default:
                return null;
        }
    }

    /**
     * Return the numeric version value used by the {@code Sec-WebSocket-Version} header.
     */
    public int code() {
        return code;
    }

    /**
     * Return {@code true} when this version uses RFC 6455 binary framing.
     */
    public boolean isRfc6455Framing() {
        return this != V0;
    }
}
