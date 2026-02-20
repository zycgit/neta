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
package net.hasor.neta.codec.http3;

/**
 * HTTP/3 frame type constants as defined in RFC 9114, Section 7.2.
 * <p>
 * HTTP/3 frames are exchanged on QUIC streams. Each frame begins with a
 * type and length field, both encoded as QUIC variable-length integers.
 * <p>
 * Frame format:
 * <pre>
 *   HTTP/3 Frame {
 *     Type (i),
 *     Length (i),
 *     Frame Payload (..),
 *   }
 * </pre>
 */
public final class Http3FrameType {
    /** DATA frame (0x00) - conveys arbitrary, variable-length sequences of bytes. */
    public static final long DATA         = 0x00;
    /** HEADERS frame (0x01) - carries an HTTP field section, encoded using QPACK. */
    public static final long HEADERS      = 0x01;
    /** CANCEL_PUSH frame (0x03) - used to request cancellation of a server push. */
    public static final long CANCEL_PUSH  = 0x03;
    /** SETTINGS frame (0x04) - conveys configuration parameters. */
    public static final long SETTINGS     = 0x04;
    /** PUSH_PROMISE frame (0x05) - carries a request header section for server push. */
    public static final long PUSH_PROMISE = 0x05;
    /** GOAWAY frame (0x07) - initiates graceful connection shutdown. */
    public static final long GOAWAY       = 0x07;
    /** MAX_PUSH_ID frame (0x0d) - controls the maximum push ID the server can use. */
    public static final long MAX_PUSH_ID  = 0x0d;

    // Reserved frame types that MUST be ignored (RFC 9114, Section 7.2.8)
    // These are used for grease: 0x1f * N + 0x21 for any non-negative integer N

    private Http3FrameType() {
    }

    /**
     * Returns true if the frame type is reserved for greasing and must be ignored.
     * Reserved types use the formula: 0x1f * N + 0x21
     */
    public static boolean isReserved(long type) {
        return type >= 0x21 && ((type - 0x21) % 0x1f) == 0;
    }

    /** Returns a human-readable name for the given frame type. */
    public static String name(long type) {
        if (type == DATA)
            return "DATA";
        if (type == HEADERS)
            return "HEADERS";
        if (type == CANCEL_PUSH)
            return "CANCEL_PUSH";
        if (type == SETTINGS)
            return "SETTINGS";
        if (type == PUSH_PROMISE)
            return "PUSH_PROMISE";
        if (type == GOAWAY)
            return "GOAWAY";
        if (type == MAX_PUSH_ID)
            return "MAX_PUSH_ID";
        if (isReserved(type))
            return "RESERVED(0x" + Long.toHexString(type) + ")";
        return "UNKNOWN(0x" + Long.toHexString(type) + ")";
    }
}
