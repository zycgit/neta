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
package net.hasor.neta.codec.http.h3;
/**
 * Represents an HTTP/3 frame as defined in RFC 9114 (Section 7.1).
 * <p>
 * This is the intermediate representation between the binary wire format
 * ({@code ByteBuf}) and the semantic HTTP objects ({@code HttpObject}).
 * <p>
 * Unlike HTTP/2, HTTP/3 frames do not encode the stream ID in the frame header —
 * that is provided by the underlying QUIC transport. The stream ID is carried
 * here as transport metadata for each frame.
 * <p>
 * Frame format (RFC 9114, Section 7.1):
 * <pre>
 *   HTTP/3 Frame {
 *     Type (i),       — QUIC variable-length integer
 *     Length (i),     — QUIC variable-length integer
 *     Frame Payload (..),
 *   }
 * </pre>
 * <p>
 * Decode path: {@code ByteBuf → Http3Frame → HttpObject}<br>
 * Encode path: {@code HttpObject → Http3Frame → ByteBuf}
 * @see Http3FrameType
 */
public class Http3Frame {
    private static final byte[] EMPTY = new byte[0];

    private final long    type;
    private final long    streamId;
    private final byte[]  payload;
    private final int     payloadOffset;
    private final int     payloadLength;
    private final boolean fin;

    /**
     * Creates an HTTP/3 frame with full parameters.
     * @param type frame type (e.g., {@link Http3FrameType#DATA})
     * @param streamId QUIC stream identifier (transport metadata)
     * @param fin true if QUIC FIN is set (end of stream)
     * @param payload frame payload bytes
     * @param payloadOffset offset into the payload array
     * @param payloadLength number of payload bytes
     */
    public Http3Frame(long type, long streamId, boolean fin, byte[] payload, int payloadOffset, int payloadLength) {
        this.type = type;
        this.streamId = streamId;
        this.fin = fin;
        this.payload = payload != null ? payload : EMPTY;
        this.payloadOffset = payloadOffset;
        this.payloadLength = payloadLength;
    }

    /**
     * Creates an HTTP/3 frame with the given type, stream ID, FIN flag, and full payload.
     */
    public Http3Frame(long type, long streamId, boolean fin, byte[] payload) {
        this(type, streamId, fin, payload, 0, payload != null ? payload.length : 0);
    }

    /**
     * Creates an HTTP/3 frame with no payload.
     */
    public Http3Frame(long type, long streamId, boolean fin) {
        this(type, streamId, fin, EMPTY, 0, 0);
    }

    // ========================= Factory Methods =========================

    /** Creates a DATA frame. */
    public static Http3Frame data(long streamId, boolean fin, byte[] payload, int offset, int length) {
        return new Http3Frame(Http3FrameType.DATA, streamId, fin, payload, offset, length);
    }

    /** Creates a DATA frame with full payload. */
    public static Http3Frame data(long streamId, boolean fin, byte[] payload) {
        return new Http3Frame(Http3FrameType.DATA, streamId, fin, payload);
    }

    /** Creates a HEADERS frame. */
    public static Http3Frame headers(long streamId, boolean fin, byte[] headerBlock, int offset, int length) {
        return new Http3Frame(Http3FrameType.HEADERS, streamId, fin, headerBlock, offset, length);
    }

    /** Creates a HEADERS frame with full payload. */
    public static Http3Frame headers(long streamId, boolean fin, byte[] headerBlock) {
        return new Http3Frame(Http3FrameType.HEADERS, streamId, fin, headerBlock);
    }

    /** Creates a SETTINGS frame (connection-level, no stream ID). */
    public static Http3Frame settings(byte[] payload) {
        return new Http3Frame(Http3FrameType.SETTINGS, 0, false, payload);
    }

    /** Creates a GOAWAY frame (connection-level, no stream ID). */
    public static Http3Frame goaway(byte[] payload) {
        return new Http3Frame(Http3FrameType.GOAWAY, 0, false, payload);
    }

    // ========================= Getters =========================

    /** Returns the frame type code (e.g., {@link Http3FrameType#HEADERS}). */
    public long type() {
        return type;
    }

    /** Returns the QUIC stream identifier (transport metadata). */
    public long streamId() {
        return streamId;
    }

    /** Returns true if QUIC FIN is set (end of stream). */
    public boolean fin() {
        return fin;
    }

    /** Returns the raw payload byte array. Use with {@link #payloadOffset()} and {@link #payloadLength()}. */
    public byte[] payload() {
        return payload;
    }

    /** Returns the offset into the payload array. */
    public int payloadOffset() {
        return payloadOffset;
    }

    /** Returns the number of payload bytes. */
    public int payloadLength() {
        return payloadLength;
    }

    @Override
    public String toString() {
        return "Http3Frame{type=" + Http3FrameType.name(type) + ", stream=" + streamId + ", fin=" + fin + ", len=" + payloadLength + "}";
    }
}
