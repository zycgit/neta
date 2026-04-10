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
package net.hasor.neta.channel.transport.quic;

/**
 * Integer constant definitions for the various QUIC frame types.
 * <p>Covers the frame types defined in <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19">RFC 9000 Section 19</a>
 * and the DATAGRAM extension in <a href="https://www.rfc-editor.org/rfc/rfc9221">RFC 9221</a>.
 * <p><b>Frame categories and usage:</b>
 * <pre>
 *   Connection control
 *     PADDING (0x00)            — pads packets to satisfy minimum length or fill space
 *     PING (0x01)               — triggers an ACK from the peer
 *     ACK / ACK_ECN (0x02-0x03) — selective acknowledgment
 *     CONNECTION_CLOSE (0x1c)   — gracefully closes the connection with a transport error code
 *     CONNECTION_CLOSE_APP(0x1d)— gracefully closes the connection with an application error code
 *     HANDSHAKE_DONE (0x1e)     — indicates TLS handshake completion (server to client)
 *   Stream data
 *     STREAM (0x08–0x0F)        — carries application data; the low 3 bits are flags:
 *                                   FIN_BIT (0x01): last segment of the stream
 *                                   LEN_BIT (0x02): contains a length field
 *                                   OFF_BIT (0x04): contains an offset field
 *     RESET_STREAM (0x04)       — immediately terminates a stream
 *     STOP_SENDING (0x05)       — requests that the peer stop sending on a stream
 *     CRYPTO (0x06)             — carries TLS handshake data during the QUIC handshake
 *     NEW_TOKEN (0x07)          — issues a new address validation token
 *   Flow control
 *     MAX_DATA (0x10)           — raises the connection-level data limit
 *     MAX_STREAM_DATA (0x11)    — raises the data limit for a stream
 *     MAX_STREAMS_BIDI (0x12)   — raises the bidirectional stream count limit
 *     MAX_STREAMS_UNI (0x13)    — raises the unidirectional stream count limit
 *     DATA_BLOCKED (0x14)       — indicates connection-level flow-control blocking
 *     STREAM_DATA_BLOCKED (0x15)— indicates stream-level flow-control blocking
 *     STREAMS_BLOCKED_*(0x16-17)— indicates blocking due to stream count limits
 *   Connection migration
 *     NEW_CONNECTION_ID (0x18)  — provides a new spare Connection ID
 *     RETIRE_CONNECTION_ID (0x19)— retires an old Connection ID
 *     PATH_CHALLENGE (0x1a)     — validates an alternate path
 *     PATH_RESPONSE (0x1b)      — responds to PATH_CHALLENGE
 *   Unreliable DATAGRAM (RFC 9221)
 *     DATAGRAM (0x30)           — DATAGRAM without a length field
 *     DATAGRAM_LEN (0x31)       — DATAGRAM with a length field
 * </pre>
 * <p><b>Usage:</b> parsed frame types can be inspected with the static helper methods:
 * <pre>
 *   int type = readVarint(buf);  // Parse a VarInt from the QUIC packet.
 *   if (QuicFrameType.isStream(type)) {
 *       boolean fin = QuicFrameType.streamFin(type);
 *       boolean hasLen = QuicFrameType.streamLen(type);
 *       boolean hasOff = QuicFrameType.streamOff(type);
 *       ...
 *   }
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19">RFC 9000 §19</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9221">RFC 9221 — DATAGRAM extension</a>
 */
public final class QuicFrameType {
    public static final int PADDING              = 0x00;
    public static final int PING                 = 0x01;
    public static final int ACK                  = 0x02;
    public static final int ACK_ECN              = 0x03;
    public static final int RESET_STREAM         = 0x04;
    public static final int STOP_SENDING         = 0x05;
    public static final int CRYPTO               = 0x06;
    public static final int NEW_TOKEN            = 0x07;
    public static final int STREAM_BASE          = 0x08;
    public static final int STREAM_FIN_BIT       = 0x01;
    public static final int STREAM_LEN_BIT       = 0x02;
    public static final int STREAM_OFF_BIT       = 0x04;
    public static final int MAX_DATA             = 0x10;
    public static final int MAX_STREAM_DATA      = 0x11;
    public static final int MAX_STREAMS_BIDI     = 0x12;
    public static final int MAX_STREAMS_UNI      = 0x13;
    public static final int DATA_BLOCKED         = 0x14;
    public static final int STREAM_DATA_BLOCKED  = 0x15;
    public static final int STREAMS_BLOCKED_BIDI = 0x16;
    public static final int STREAMS_BLOCKED_UNI  = 0x17;
    public static final int NEW_CONNECTION_ID    = 0x18;
    public static final int RETIRE_CONNECTION_ID = 0x19;
    public static final int PATH_CHALLENGE       = 0x1a;
    public static final int PATH_RESPONSE        = 0x1b;
    public static final int CONNECTION_CLOSE     = 0x1c;
    public static final int CONNECTION_CLOSE_APP = 0x1d;
    public static final int HANDSHAKE_DONE       = 0x1e;

    /** DATAGRAM frame without a length field. */
    public static final int DATAGRAM     = 0x30;
    /** DATAGRAM frame with a length field. */
    public static final int DATAGRAM_LEN = 0x31;

    private QuicFrameType() {
    }

    /**
     * Returns whether the given frame type is a STREAM frame.
     */
    public static boolean isStream(int type) {
        return (type & 0xF8) == STREAM_BASE;
    }

    /**
     * Returns whether the STREAM frame carries the FIN flag.
     */
    public static boolean streamFin(int type) {
        return (type & 0x01) != 0;
    }

    /**
     * Returns whether the STREAM frame carries the LEN flag.
     */
    public static boolean streamLen(int type) {
        return (type & 0x02) != 0;
    }

    /**
     * Returns whether the STREAM frame carries the OFF flag.
     */
    public static boolean streamOff(int type) {
        return (type & 0x04) != 0;
    }

    /**
     * Returns whether the given frame type is a DATAGRAM frame.
     */
    public static boolean isDatagram(int type) {
        return type == DATAGRAM || type == DATAGRAM_LEN;
    }

    /**
     * Returns whether the DATAGRAM frame carries a length field.
     */
    public static boolean datagramHasLen(int type) {
        return (type & 0x01) != 0;
    }
}
