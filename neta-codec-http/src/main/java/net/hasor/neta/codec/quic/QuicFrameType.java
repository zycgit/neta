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
package net.hasor.neta.codec.quic;

/**
 * QUIC frame type constants as defined in RFC 9000, Section 19.
 * <p>
 * QUIC uses variable-length integer encoding for frame types.
 * These constants represent the frame types used in QUIC transport.
 */
public final class QuicFrameType {
    /** Padding frame (0x00) - used for path MTU discovery and congestion control. */
    public static final int PADDING              = 0x00;
    /** Ping frame (0x01) - verifies that peers are still alive. */
    public static final int PING                 = 0x01;
    /** ACK frame (0x02) - acknowledges received packets. */
    public static final int ACK                  = 0x02;
    /** ACK with ECN counts (0x03). */
    public static final int ACK_ECN              = 0x03;
    /** Reset Stream frame (0x04) - abruptly terminates the sending part of a stream. */
    public static final int RESET_STREAM         = 0x04;
    /** Stop Sending frame (0x05) - requests that the peer cease transmission on a stream. */
    public static final int STOP_SENDING         = 0x05;
    /** Crypto frame (0x06) - transmits cryptographic handshake messages. */
    public static final int CRYPTO               = 0x06;
    /** New Token frame (0x07) - provides a token for future connection attempts. */
    public static final int NEW_TOKEN            = 0x07;
    /** Stream frame (0x08..0x0f) - carries stream data. */
    public static final int STREAM               = 0x08;
    /** Max Data frame (0x10) - indicates maximum data that can be sent on the connection. */
    public static final int MAX_DATA             = 0x10;
    /** Max Stream Data frame (0x11) - indicates max data that can be sent on a stream. */
    public static final int MAX_STREAM_DATA      = 0x11;
    /** Max Streams (bidirectional) frame (0x12). */
    public static final int MAX_STREAMS_BIDI     = 0x12;
    /** Max Streams (unidirectional) frame (0x13). */
    public static final int MAX_STREAMS_UNI      = 0x13;
    /** Data Blocked frame (0x14) - indicates sender is blocked by connection-level flow control. */
    public static final int DATA_BLOCKED         = 0x14;
    /** Stream Data Blocked frame (0x15) - indicates sender is blocked by stream-level flow control. */
    public static final int STREAM_DATA_BLOCKED  = 0x15;
    /** Streams Blocked (bidirectional) frame (0x16). */
    public static final int STREAMS_BLOCKED_BIDI = 0x16;
    /** Streams Blocked (unidirectional) frame (0x17). */
    public static final int STREAMS_BLOCKED_UNI  = 0x17;
    /** New Connection ID frame (0x18) - provides alternative connection IDs. */
    public static final int NEW_CONNECTION_ID    = 0x18;
    /** Retire Connection ID frame (0x19). */
    public static final int RETIRE_CONNECTION_ID = 0x19;
    /** Path Challenge frame (0x1a) - used for path validation. */
    public static final int PATH_CHALLENGE       = 0x1a;
    /** Path Response frame (0x1b) - response to path challenge. */
    public static final int PATH_RESPONSE        = 0x1b;
    /** Connection Close frame (0x1c) - signals connection termination (QUIC layer). */
    public static final int CONNECTION_CLOSE     = 0x1c;
    /** Connection Close frame (0x1d) - signals connection termination (application layer). */
    public static final int CONNECTION_CLOSE_APP = 0x1d;
    /** Handshake Done frame (0x1e) - signals completion of the TLS handshake. */
    public static final int HANDSHAKE_DONE       = 0x1e;

    private QuicFrameType() {
    }

    /**
     * Returns true if the given frame type is a STREAM frame (0x08..0x0f).
     * Bits: 0x04 = FIN, 0x02 = LEN, 0x01 = OFF.
     */
    public static boolean isStream(int type) {
        return (type & 0xF8) == STREAM;
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

    /** Returns a human-readable name for the given frame type. */
    public static String name(int type) {
        if (isStream(type))
            return "STREAM";
        switch (type) {
            case PADDING:
                return "PADDING";
            case PING:
                return "PING";
            case ACK:
                return "ACK";
            case ACK_ECN:
                return "ACK_ECN";
            case RESET_STREAM:
                return "RESET_STREAM";
            case STOP_SENDING:
                return "STOP_SENDING";
            case CRYPTO:
                return "CRYPTO";
            case NEW_TOKEN:
                return "NEW_TOKEN";
            case MAX_DATA:
                return "MAX_DATA";
            case MAX_STREAM_DATA:
                return "MAX_STREAM_DATA";
            case MAX_STREAMS_BIDI:
                return "MAX_STREAMS_BIDI";
            case MAX_STREAMS_UNI:
                return "MAX_STREAMS_UNI";
            case DATA_BLOCKED:
                return "DATA_BLOCKED";
            case STREAM_DATA_BLOCKED:
                return "STREAM_DATA_BLOCKED";
            case STREAMS_BLOCKED_BIDI:
                return "STREAMS_BLOCKED_BIDI";
            case STREAMS_BLOCKED_UNI:
                return "STREAMS_BLOCKED_UNI";
            case NEW_CONNECTION_ID:
                return "NEW_CONNECTION_ID";
            case RETIRE_CONNECTION_ID:
                return "RETIRE_CONNECTION_ID";
            case PATH_CHALLENGE:
                return "PATH_CHALLENGE";
            case PATH_RESPONSE:
                return "PATH_RESPONSE";
            case CONNECTION_CLOSE:
                return "CONNECTION_CLOSE";
            case CONNECTION_CLOSE_APP:
                return "CONNECTION_CLOSE_APP";
            case HANDSHAKE_DONE:
                return "HANDSHAKE_DONE";
            default:
                return "UNKNOWN(0x" + Integer.toHexString(type) + ")";
        }
    }
}
