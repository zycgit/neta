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
 * WebSocket protocol versions.
 * <ul>
 *   <li>{@link #V0} — Hixie-76 / hybi-00: uses {@code 0x00…0xFF} text framing and MD5 handshake.</li>
 *   <li>{@link #V7} — hybi-07: uses RFC 6455–style binary framing and SHA-1 handshake.</li>
 *   <li>{@link #V8} — hybi-08/10: uses RFC 6455–style binary framing and SHA-1 handshake.</li>
 *   <li>{@link #V13} — RFC 6455: the final standard, same wire format as V7/V8.</li>
 * </ul>
 * <p>
 * V7, V8, and V13 share the same frame encoding; only the handshake version header differs.
 * V0 uses a completely different frame format.
 */
public enum WebSocketVersion {
    /** Hixie-76 / hybi-00 (version 0). */
    V0(0),
    /** hybi-07 (version 7). */
    V7(7),
    /** hybi-08/10 (version 8). */
    V8(8),
    /** RFC 6455 (version 13). */
    V13(13);

    private final int code;

    WebSocketVersion(int code) {
        this.code = code;
    }

    /**
     * Resolves a version number from the {@code Sec-WebSocket-Version} header value.
     * @param version the header value (e.g. "13", "8", "7"), or {@code null} for Hixie-76
     * @return the matching version, or {@code null} if unknown
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

    /** Returns the numeric version value used in the {@code Sec-WebSocket-Version} header. */
    public int code() {
        return code;
    }

    /** Returns {@code true} if this version uses the RFC 6455 binary frame format. */
    public boolean isRfc6455Framing() {
        return this != V0;
    }
}
