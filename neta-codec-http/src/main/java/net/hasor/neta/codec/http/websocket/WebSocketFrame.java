/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.websocket;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.HttpObject;
/**
 * Wire-level frame model for websocket traffic.
 * <p>
 * This model maps directly to the RFC 6455 frame format and is used to pass data
 * between frame codecs and message handlers.
 * <p>Each field corresponds directly to the on-the-wire layout:
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-------+-+-------------+-------------------------------+
 * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 * |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 * |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 * | |1|2|3|       |K|             |                               |
 * +-+-+-+-+-------+-+-------------+-------------------------------+
 * |     Masking-key (if masked)   |  Payload Data ...             |
 * +-------------------------------- - - - - - - - - - - - - - - - +
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-18
 */
public interface WebSocketFrame extends HttpObject {
    /**
     * Return the opcode of the current frame.
     */
    WebSocketOpcode opcode();

    /**
     * Return {@code true} when the FIN bit is set, meaning this frame is the
     * final fragment of the current message.
     * For control frames, this value is always {@code true}.
     */
    boolean isFinalFragment();

    /**
     * Return {@code true} when the RSV1 bit is set.
     */
    boolean isRsv1();

    /**
     * Return {@code true} when the RSV2 bit is set.
     */
    boolean isRsv2();

    /**
     * Return {@code true} when the RSV3 bit is set.
     */
    boolean isRsv3();

    /**
     * Return {@code true} when the MASK bit is set.
     * Frames sent from clients to servers must be masked, while frames sent
     * from servers to clients must not be masked.
     */
    boolean isMasked();

    /**
     * Return the 4-byte masking key, or {@code null} if the frame is not masked.
     * This value is meaningful only when {@link #isMasked()} returns {@code true}.
     */
    byte[] maskingKey();

    /**
     * Return the payload content of the current frame.
     * For inbound masked frames produced by the decoder, the returned content
     * has already been unmasked.
     */
    ByteBuf content();

    /**
     * Transfers the current payload ownership out of this frame wrapper.
     * <p>
     * After transfer, this frame no longer owns the payload and later
     * {@link #release()} calls must not release the transferred buffer.
     * @return transferred payload, or {@code null} if this frame no longer owns one
     */
    ByteBuf transferContent();

    /**
     * Return the payload length represented by the current frame object.
     * <p>
     * For ordinary frames, this value equals {@code content().readableBytes()}.
     * When the decoder enables streaming output, one large wire-level frame may
     * be split into multiple frame objects, and this value then represents the
     * payload length of the current output fragment.
     */
    int payloadLength();

    static WebSocketFrame create(WebSocketOpcode opcode, boolean finalFragment, boolean masked, byte[] maskingKey, ByteBuf content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        return DefaultWebSocketFrame.newFrame(opcode, finalFragment, false, false, false, masked, maskingKey, content, content.readableBytes());
    }

    static WebSocketFrame create(WebSocketOpcode opcode, boolean finalFragment, boolean masked, byte[] maskingKey, ByteBuf content, int payloadLength) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (payloadLength < 0) {
            throw new IllegalArgumentException("payloadLength must not be negative");
        }
        return DefaultWebSocketFrame.newFrame(opcode, finalFragment, false, false, false, masked, maskingKey, content, payloadLength);
    }

    static WebSocketFrame create(WebSocketOpcode opcode, boolean finalFragment, boolean rsv1, boolean rsv2, boolean rsv3, boolean masked, byte[] maskingKey, ByteBuf content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        return DefaultWebSocketFrame.newFrame(opcode, finalFragment, rsv1, rsv2, rsv3, masked, maskingKey, content, content.readableBytes());
    }

    static WebSocketFrame create(WebSocketOpcode opcode, boolean finalFragment, boolean rsv1, boolean rsv2, boolean rsv3, boolean masked, byte[] maskingKey, ByteBuf content, int payloadLength) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (payloadLength < 0) {
            throw new IllegalArgumentException("payloadLength must not be negative");
        }
        return DefaultWebSocketFrame.newFrame(opcode, finalFragment, rsv1, rsv2, rsv3, masked, maskingKey, content, payloadLength);
    }
}
