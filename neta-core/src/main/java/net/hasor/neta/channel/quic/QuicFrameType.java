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
 * QUIC frame type constants as defined in RFC 9000, Section 19.
 * @author 赵永春 (zyc@hasor.net)
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
