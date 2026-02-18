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
 * Default mutable implementation of {@link WebSocketFrame}.
 * <h3>Convenience factory methods</h3>
 * <pre>
 *   WebSocketFrame text   = DefaultWebSocketFrame.text("hello");
 *   WebSocketFrame binary = DefaultWebSocketFrame.binary(bytes);
 *   WebSocketFrame ping   = DefaultWebSocketFrame.ping();
 *   WebSocketFrame pong   = DefaultWebSocketFrame.pong();
 *   WebSocketFrame close  = DefaultWebSocketFrame.close(1000, "Normal closure");
 * </pre>
 */
public class DefaultWebSocketFrame implements WebSocketFrame {

    private final WebSocketOpcode opcode;
    private final boolean         finalFragment;
    private final boolean         masked;
    private final byte[]          maskingKey;
    private final ByteBuf         content;

    /**
     * Creates a new frame.
     * @param opcode the frame opcode
     * @param finalFragment whether the FIN bit is set
     * @param masked whether the payload is masked
     * @param maskingKey the 4-byte masking key (only used when {@code masked} is true)
     * @param content the (already unmasked) payload
     */
    public DefaultWebSocketFrame(WebSocketOpcode opcode, boolean finalFragment, boolean masked, byte[] maskingKey, ByteBuf content) {
        if (opcode == null) {
            throw new IllegalArgumentException("opcode must not be null");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        this.opcode = opcode;
        this.finalFragment = finalFragment;
        this.masked = masked;
        this.maskingKey = masked ? maskingKey : null;
        this.content = content;
    }

    // -------------------------------------------------------------------------
    // Factory helpers
    // -------------------------------------------------------------------------

    /** Creates an unmasked text frame with FIN=true. */
    public static DefaultWebSocketFrame text(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(bytes.length, Integer.MAX_VALUE);
        buf.writeBytes(bytes, 0, bytes.length);
        buf.markWriter();
        return new DefaultWebSocketFrame(WebSocketOpcode.TEXT, true, false, null, buf);
    }

    /** Creates an unmasked binary frame with FIN=true. */
    public static DefaultWebSocketFrame binary(byte[] data) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(data.length, Integer.MAX_VALUE);
        buf.writeBytes(data, 0, data.length);
        buf.markWriter();
        return new DefaultWebSocketFrame(WebSocketOpcode.BINARY, true, false, null, buf);
    }

    /** Creates an unmasked empty ping frame. */
    public static DefaultWebSocketFrame ping() {
        return new DefaultWebSocketFrame(WebSocketOpcode.PING, true, false, null, ByteBuf.EMPTY);
    }

    /** Creates an unmasked empty pong frame. */
    public static DefaultWebSocketFrame pong() {
        return new DefaultWebSocketFrame(WebSocketOpcode.PONG, true, false, null, ByteBuf.EMPTY);
    }

    /**
     * Creates an unmasked close frame with the specified status code and reason.
     * @param statusCode WebSocket close status code (e.g. 1000 = Normal Closure)
     * @param reason human-readable reason (may be empty)
     */
    public static DefaultWebSocketFrame close(int statusCode, String reason) {
        byte[] reasonBytes = (reason != null ? reason : "").getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(2 + reasonBytes.length, Integer.MAX_VALUE);
        // Write status code as big-endian 16-bit integer
        buf.writeByte((byte) ((statusCode >> 8) & 0xFF));
        buf.writeByte((byte) (statusCode & 0xFF));
        if (reasonBytes.length > 0) {
            buf.writeBytes(reasonBytes, 0, reasonBytes.length);
        }
        buf.markWriter();
        return new DefaultWebSocketFrame(WebSocketOpcode.CLOSE, true, false, null, buf);
    }

    // -------------------------------------------------------------------------
    // WebSocketFrame interface
    // -------------------------------------------------------------------------

    @Override
    public WebSocketOpcode opcode() {
        return opcode;
    }

    @Override
    public boolean isFinalFragment() {
        return finalFragment;
    }

    @Override
    public boolean isMasked() {
        return masked;
    }

    @Override
    public byte[] maskingKey() {
        return maskingKey;
    }

    @Override
    public ByteBuf content() {
        return content;
    }

    @Override
    public String toString() {
        return "WebSocketFrame{opcode=" + opcode + ", fin=" + finalFragment + ", masked=" + masked + ", payloadLen=" + content.readableBytes() + '}';
    }
}
