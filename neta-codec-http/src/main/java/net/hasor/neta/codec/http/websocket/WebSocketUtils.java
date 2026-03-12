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
import java.nio.charset.StandardCharsets;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;

/**
 * Utility methods for creating WebSocket frames.
 */
public final class WebSocketUtils {
    private WebSocketUtils() {
    }

    public static WebSocketFrame textFrame(String text) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(text.length() * 3, Integer.MAX_VALUE);
        buf.writeString(text, StandardCharsets.UTF_8);
        buf.markWriter();
        return DefaultWebSocketFrame.newFrame(WebSocketOpcode.TEXT, true, false, null, buf);
    }

    public static WebSocketFrame binaryFrame(byte[] data) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(data.length, Integer.MAX_VALUE);
        buf.writeBytes(data, 0, data.length);
        buf.markWriter();
        return DefaultWebSocketFrame.newFrame(WebSocketOpcode.BINARY, true, false, null, buf);
    }

    public static WebSocketFrame pingFrame() {
        return DefaultWebSocketFrame.newFrame(WebSocketOpcode.PING, true, false, null, ByteBuf.EMPTY);
    }

    public static WebSocketFrame pongFrame() {
        return DefaultWebSocketFrame.newFrame(WebSocketOpcode.PONG, true, false, null, ByteBuf.EMPTY);
    }

    public static WebSocketFrame closeFrame(int statusCode, String reason) {
        String reasonStr = reason != null ? reason : "";
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(2 + reasonStr.length() * 3, Integer.MAX_VALUE);
        buf.writeByte((byte) ((statusCode >> 8) & 0xFF));
        buf.writeByte((byte) (statusCode & 0xFF));
        if (!reasonStr.isEmpty()) {
            buf.writeString(reasonStr, StandardCharsets.UTF_8);
        }
        buf.markWriter();
        return DefaultWebSocketFrame.newFrame(WebSocketOpcode.CLOSE, true, false, null, buf);
    }

    public static TextWebSocketMessage textMessage(ByteBuf content) {
        return TextWebSocketMessage.request(content);
    }

    public static BinaryWebSocketMessage binaryMessage(ByteBuf content) {
        return BinaryWebSocketMessage.request(content);
    }

    public static ContinuationWebSocketMessage continuationMessage(ByteBuf content) {
        return ContinuationWebSocketMessage.request(content);
    }

    public static PingWebSocketMessage pingMessage(ByteBuf content) {
        return PingWebSocketMessage.request(content);
    }

    public static PongWebSocketMessage pongMessage(ByteBuf content) {
        return PongWebSocketMessage.request(content);
    }

    public static WebSocketCloseMessage closeMessage(int statusCode, String reason) {
        return new WebSocketCloseMessage(statusCode, reason);
    }

    public static HandshakeWebSocketMessage handshakeComplete(WebSocketVersion version, String requestPath, String subProtocol, String extensions) {
        return new HandshakeWebSocketMessage(version, requestPath, subProtocol, extensions);
    }

    //

    static WebSocketFrame textFrame(boolean finalFragment, boolean masked, byte[] maskingKey, ByteBuf content) {
        return DefaultWebSocketFrame.newFrame(WebSocketOpcode.TEXT, finalFragment, masked, maskingKey, content);
    }

    static WebSocketFrame binaryFrame(boolean finalFragment, boolean masked, byte[] maskingKey, ByteBuf content) {
        return DefaultWebSocketFrame.newFrame(WebSocketOpcode.BINARY, finalFragment, masked, maskingKey, content);
    }

    static WebSocketFrame continuationFrame(boolean finalFragment, boolean masked, byte[] maskingKey, ByteBuf content) {
        return DefaultWebSocketFrame.newFrame(WebSocketOpcode.CONTINUATION, finalFragment, masked, maskingKey, content);
    }

    static WebSocketFrame pingFrame(boolean masked, byte[] maskingKey, ByteBuf content) {
        return DefaultWebSocketFrame.newFrame(WebSocketOpcode.PING, true, masked, maskingKey, content);
    }

    static WebSocketFrame pongFrame(boolean masked, byte[] maskingKey, ByteBuf content) {
        return DefaultWebSocketFrame.newFrame(WebSocketOpcode.PONG, true, masked, maskingKey, content);
    }

    static WebSocketFrame closeFrame(boolean masked, byte[] maskingKey, ByteBuf content) {
        return DefaultWebSocketFrame.newFrame(WebSocketOpcode.CLOSE, true, masked, maskingKey, content);
    }
}
