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
 * Represents a WebSocket close event produced by {@link WebSocketFrameAggregator}.
 * <p>
 * Contains the close status code and optional reason text as defined in
 * <a href="https://tools.ietf.org/html/rfc6455#section-7.4">RFC 6455 §7.4</a>.
 * <p>
 * Common status codes:
 * <ul>
 *   <li>{@link WebSocketCloseCode#NORMAL_CLOSURE} (1000) — normal closure.</li>
 *   <li>{@link WebSocketCloseCode#GOING_AWAY} (1001) — endpoint is going away (e.g. server shutdown).</li>
 *   <li>{@link WebSocketCloseCode#PROTOCOL_ERROR} (1002) — protocol error.</li>
 *   <li>{@link WebSocketCloseCode#UNSUPPORTED_DATA} (1003) — unsupported data type.</li>
 *   <li>{@link WebSocketCloseCode#NO_STATUS} (1005) — no status code present (synthetic).</li>
 *   <li>{@link WebSocketCloseCode#ABNORMAL_CLOSURE} (1006) — abnormal closure (synthetic).</li>
 * </ul>
 */
public class WebSocketCloseMessage extends AbstractWebSocketMessage {
    private final int    statusCode;
    private final String reason;

    /**
     * Creates a close message.
     * @param statusCode the close status code (e.g. 1000 for normal closure)
     * @param reason the close reason text (may be {@code null})
     */
    public WebSocketCloseMessage(int statusCode, String reason) {
        this.initEmptyMessage();
        this.statusCode = statusCode;
        this.reason = reason;
    }

    /** Returns the close status code. */
    public int statusCode() {
        return this.statusCode;
    }

    /** Returns the close reason text, or {@code null} if none. */
    public String reason() {
        return this.reason;
    }

    @Override
    public WebSocketOpcode type() {
        return WebSocketOpcode.CLOSE;
    }

    @Override
    protected void recycle() {
    }

    @Override
    public String toString() {
        return "WebSocketCloseMessage{code=" + this.statusCode + (this.reason != null ? ", reason='" + this.reason + '\'' : "") + '}';
    }
}
