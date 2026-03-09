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
package net.hasor.neta.channel.quic;

/**
 * Integer constants for every QUIC frame type defined in
 * <a href="https://www.rfc-editor.org/rfc/rfc9000#section-19">RFC 9000, Section 19</a>
 * and the DATAGRAM extension
 * <a href="https://www.rfc-editor.org/rfc/rfc9221">RFC 9221</a>.
 * <p><b>Frame categories and their usage:</b>
 * <pre>
 *   Connection control
 *     PADDING (0x00)            — pad packets to a minimum size or fill space
 *     PING (0x01)               — elicit an ACK from the peer
 *     ACK / ACK_ECN (0x02-0x03) — selective acknowledgement
 *     CONNECTION_CLOSE (0x1c)   — graceful close with a transport error code
 *     CONNECTION_CLOSE_APP(0x1d)— graceful application-level close
 *     HANDSHAKE_DONE (0x1e)     — signals completion of the TLS handshake (server → client)
 *   Stream data
 *     STREAM (0x08–0x0F)        — carry application bytes; flags embedded in low 3 bits:
 *                                   FIN_BIT (0x01): final segment of the stream
 *                                   LEN_BIT (0x02): Length field present
 *                                   OFF_BIT (0x04): Offset field present
 *     RESET_STREAM (0x04)       — abruptly terminate a stream
 *     STOP_SENDING (0x05)       — request the peer stop sending on a stream
 *     CRYPTO (0x06)             — carry TLS handshake data during QUIC handshake
 *     NEW_TOKEN (0x07)          — provide a new address-validation token
 *   Flow-control
 *     MAX_DATA (0x10)           — increase the stream-data limit at connection level
 *     MAX_STREAM_DATA (0x11)    — increase the per-stream data limit
 *     MAX_STREAMS_BIDI (0x12)   — increase the max number of bidirectional streams
 *     MAX_STREAMS_UNI (0x13)    — increase the max number of unidirectional streams
 *     DATA_BLOCKED (0x14)       — signal connection-level flow-control blockage
 *     STREAM_DATA_BLOCKED (0x15)— signal stream-level flow-control blockage
 *     STREAMS_BLOCKED_*(0x16-17)— signal stream-count limit
 *   Connection migration
 *     NEW_CONNECTION_ID (0x18)  — provide alternative connection IDs
 *     RETIRE_CONNECTION_ID (0x19)—retire a previously issued connection ID
 *     PATH_CHALLENGE (0x1a)     — validate an alternate path
 *     PATH_RESPONSE (0x1b)      — respond to PATH_CHALLENGE
 *   Unreliable datagrams (RFC 9221)
 *     DATAGRAM (0x30)           — unreliable datagram without Length field
 *     DATAGRAM_LEN (0x31)       — unreliable datagram with Length field
 * </pre>
 * <p><b>Usage:</b> call the static helpers to inspect a parsed frame type:
 * <pre>
 *   int type = readVarint(buf);  // parse VarInt from QUIC packet
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

    /** DATAGRAM frame without Length field (RFC 9221). */
    public static final int DATAGRAM     = 0x30;
    /** DATAGRAM frame with Length field (RFC 9221). */
    public static final int DATAGRAM_LEN = 0x31;

    private QuicFrameType() {
    }

    /** Returns true if the frame type is a STREAM frame (0x08–0x0F). */
    public static boolean isStream(int type) {
        return (type & 0xF8) == STREAM_BASE;
    }

    /** Returns true if the STREAM frame has the FIN bit set. */
    public static boolean streamFin(int type) {
        return (type & 0x01) != 0;
    }

    /** Returns true if the STREAM frame has the LEN bit set. */
    public static boolean streamLen(int type) {
        return (type & 0x02) != 0;
    }

    /** Returns true if the STREAM frame has the OFF bit set. */
    public static boolean streamOff(int type) {
        return (type & 0x04) != 0;
    }

    /** Returns true if the frame type is a DATAGRAM frame (0x30 or 0x31). */
    public static boolean isDatagram(int type) {
        return type == DATAGRAM || type == DATAGRAM_LEN;
    }

    /** Returns true if the DATAGRAM frame has a Length field (0x31). */
    public static boolean datagramHasLen(int type) {
        return (type & 0x01) != 0;
    }
}
