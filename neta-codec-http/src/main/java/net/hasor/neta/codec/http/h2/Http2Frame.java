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
package net.hasor.neta.codec.http.h2;
/**
 * Represents an HTTP/2 frame as defined by RFC 9113 Section 4.
 * <p>
 * It is the intermediate representation between the binary wire format ({@code ByteBuf}) and
 * semantic HTTP objects ({@code HttpObject}).
 * <p>
 * Frame format:
 * <pre>
 *   +----------------------------------------------+
 *   |                 Length (24)                  |
 *   +---------------+---------------+--------------+
 *   |   Type (8)    |   Flags (8)   |
 *   +-+-------------+---------------+--------------+
 *   |R|               Stream Identifier (31)       |
 *   +=+============================================+
 *   |              Frame Payload (0...)            |
 *   +----------------------------------------------+
 * </pre>
 * <p>
 * Decode path: {@code ByteBuf -> Http2Frame -> HttpObject}<br>
 * Encode path: {@code HttpObject -> Http2Frame -> ByteBuf}
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-24
 * @see Http2FrameType
 * @see Http2Flags
 */
public class Http2Frame {
    public static final long    MAX_STREAM_ID = 0x7FFFFFFFL;
    private static final byte[] EMPTY         = new byte[0];
    private final int           type;
    private final int           flags;
    private final long          streamId;
    private final byte[]        payload;
    private final int           payloadOffset;
    private final int           payloadLength;

    /**
     * Creates an HTTP/2 frame with the given type, flags, stream ID, and payload region.
     * @param type the frame type, for example {@link Http2FrameType#DATA}
     * @param flags the frame flags, for example {@link Http2Flags#END_STREAM}
     * @param streamId the stream identifier; use 0 for connection-level frames
     * @param payload the payload byte array
     * @param payloadOffset the starting offset within the payload array
     * @param payloadLength the payload length in bytes
     */
    public Http2Frame(int type, int flags, long streamId, byte[] payload, int payloadOffset, int payloadLength) {
        this.type = type;
        this.flags = flags;
        this.streamId = streamId;
        this.payload = payload != null ? payload : EMPTY;
        this.payloadOffset = payloadOffset;
        this.payloadLength = payloadLength;
    }

    /**
     * Creates an HTTP/2 frame using the full payload array.
     * @param type the frame type
     * @param flags the frame flags
     * @param streamId the stream identifier
     * @param payload the full payload
     */
    public Http2Frame(int type, int flags, long streamId, byte[] payload) {
        this(type, flags, streamId, payload, 0, payload != null ? payload.length : 0);
    }

    /**
     * Creates an HTTP/2 frame with no payload.
     * @param type the frame type
     * @param flags the frame flags
     * @param streamId the stream identifier
     */
    public Http2Frame(int type, int flags, long streamId) {
        this(type, flags, streamId, EMPTY, 0, 0);
    }

    /**
     * Returns the frame type code, for example {@link Http2FrameType#HEADERS}.
     */
    public int type() {
        return type;
    }

    /**
     * Returns the frame flags, for example {@link Http2Flags#END_STREAM}.
     */
    public int flags() {
        return flags;
    }

    /**
     * Returns the stream identifier; 0 denotes a connection-level frame.
     */
    public long streamId() {
        return streamId;
    }

    /**
     * Returns the raw payload array, to be interpreted together with {@link #payloadOffset()} and {@link #payloadLength()}.
     */
    public byte[] payload() {
        return payload;
    }

    /**
     * Returns the offset inside the payload array.
     */
    public int payloadOffset() {
        return payloadOffset;
    }

    /**
     * Returns the payload length in bytes.
     */
    public int payloadLength() {
        return payloadLength;
    }

    @Override
    public String toString() {
        return "Http2Frame{type=" + Http2FrameType.name(type) + ", flags=0x" + Integer.toHexString(flags) + ", streamId=" + streamId + ", payloadLen=" + payloadLength + "}";
    }

    // ========================= Factory methods =========================

    /**
     * Creates a DATA frame.
     */
    public static Http2Frame data(long streamId, int flags, byte[] payload, int offset, int length) {
        return new Http2Frame(Http2FrameType.DATA, flags, streamId, payload, offset, length);
    }

    /**
     * Creates a DATA frame using the full payload.
     */
    public static Http2Frame data(long streamId, int flags, byte[] payload) {
        return new Http2Frame(Http2FrameType.DATA, flags, streamId, payload);
    }

    /**
     * Creates a HEADERS frame.
     */
    public static Http2Frame headers(long streamId, int flags, byte[] headerBlock, int offset, int length) {
        return new Http2Frame(Http2FrameType.HEADERS, flags, streamId, headerBlock, offset, length);
    }

    /**
     * Creates a HEADERS frame using the full payload.
     */
    public static Http2Frame headers(long streamId, int flags, byte[] headerBlock) {
        return new Http2Frame(Http2FrameType.HEADERS, flags, streamId, headerBlock);
    }

    /**
     * Creates a SETTINGS frame.
     */
    public static Http2Frame settings(int flags, byte[] payload) {
        return new Http2Frame(Http2FrameType.SETTINGS, flags, 0, payload);
    }

    /**
     * Creates a SETTINGS ACK frame with an empty payload and the ACK flag set.
     */
    public static Http2Frame settingsAck() {
        return new Http2Frame(Http2FrameType.SETTINGS, Http2Flags.ACK, 0);
    }

    /**
     * Creates a PING frame.
     */
    public static Http2Frame ping(int flags, byte[] opaqueData) {
        return new Http2Frame(Http2FrameType.PING, flags, 0, opaqueData);
    }

    /**
     * Creates a PING ACK frame that echoes the received opaque data.
     */
    public static Http2Frame pingAck(byte[] opaqueData) {
        return new Http2Frame(Http2FrameType.PING, Http2Flags.ACK, 0, opaqueData);
    }

    /**
     * Creates a WINDOW_UPDATE frame.
     */
    public static Http2Frame windowUpdate(long streamId, byte[] payload) {
        return new Http2Frame(Http2FrameType.WINDOW_UPDATE, Http2Flags.NONE, streamId, payload);
    }

    /**
     * Creates an RST_STREAM frame.
     */
    public static Http2Frame rstStream(long streamId, byte[] payload) {
        return new Http2Frame(Http2FrameType.RST_STREAM, Http2Flags.NONE, streamId, payload);
    }

    /**
     * Creates a GOAWAY frame.
     */
    public static Http2Frame goaway(byte[] payload) {
        return new Http2Frame(Http2FrameType.GOAWAY, Http2Flags.NONE, 0, payload);
    }

    /**
     * Creates a CONTINUATION frame.
     */
    public static Http2Frame continuation(long streamId, int flags, byte[] payload) {
        return new Http2Frame(Http2FrameType.CONTINUATION, flags, streamId, payload);
    }

    /**
     * Creates a PRIORITY frame.
     */
    public static Http2Frame priority(long streamId, byte[] payload) {
        return new Http2Frame(Http2FrameType.PRIORITY, Http2Flags.NONE, streamId, payload);
    }

    /**
     * Creates a PUSH_PROMISE frame.
     */
    public static Http2Frame pushPromise(long streamId, int flags, byte[] payload, int offset, int length) {
        return new Http2Frame(Http2FrameType.PUSH_PROMISE, flags, streamId, payload, offset, length);
    }

    /**
     * Creates a PUSH_PROMISE frame using the full payload.
     */
    public static Http2Frame pushPromise(long streamId, int flags, byte[] payload) {
        return new Http2Frame(Http2FrameType.PUSH_PROMISE, flags, streamId, payload);
    }

    public static int requireWireStreamId(long streamId) {
        if (streamId < 0 || streamId > MAX_STREAM_ID) {
            throw new IllegalArgumentException("HTTP/2 streamId out of 31-bit range: " + streamId);
        }
        return (int) streamId;
    }

    public static long decodeWireStreamId(int rawStreamId) {
        return rawStreamId & MAX_STREAM_ID;
    }
}
