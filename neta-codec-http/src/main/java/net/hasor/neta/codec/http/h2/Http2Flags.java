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
 * HTTP/2 frame flag constants as defined in RFC 9113.
 * <p>
 * Flags are specific to individual frame types and occupy 8 bits.
 */
final class Http2Flags {
    /** No flags set. */
    public static final int NONE        = 0x00;
    /** ACK flag (0x01) - used with SETTINGS and PING frames. */
    public static final int ACK         = 0x01;
    /** END_STREAM flag (0x01) - indicates the last frame for a stream. */
    public static final int END_STREAM  = 0x01;
    /** END_HEADERS flag (0x04) - indicates the end of a header block. */
    public static final int END_HEADERS = 0x04;
    /** PADDED flag (0x08) - indicates the frame is padded. */
    public static final int PADDED      = 0x08;
    /** PRIORITY flag (0x20) - indicates that the priority fields are present. */
    public static final int PRIORITY    = 0x20;

    private Http2Flags() {
    }

    /** Returns true if the specified flag is set in flags. */
    public static boolean hasFlag(int flags, int flag) {
        return (flags & flag) != 0;
    }

    /** Returns true if END_STREAM is set. */
    public static boolean endStream(int flags) {
        return hasFlag(flags, END_STREAM);
    }

    /** Returns true if END_HEADERS is set. */
    public static boolean endHeaders(int flags) {
        return hasFlag(flags, END_HEADERS);
    }

    /** Returns true if PADDED is set. */
    public static boolean padded(int flags) {
        return hasFlag(flags, PADDED);
    }

    /** Returns true if PRIORITY is set. */
    public static boolean priority(int flags) {
        return hasFlag(flags, PRIORITY);
    }

    /** Returns true if ACK is set. */
    public static boolean ack(int flags) {
        return hasFlag(flags, ACK);
    }

    /**
     * Returns a human-readable description of the flags for the given frame type.
     * Example: {@code "END_STREAM|END_HEADERS"} for HEADERS frame with 0x05.
     */
    public static String describe(int frameType, int flags) {
        if (flags == 0) {
            return "NONE";
        }
        StringBuilder sb = new StringBuilder();
        // 0x01: END_STREAM (DATA/HEADERS/CONTINUATION) or ACK (SETTINGS/PING)
        if (hasFlag(flags, 0x01)) {
            if (frameType == Http2FrameType.SETTINGS || frameType == Http2FrameType.PING) {
                sb.append("ACK|");
            } else {
                sb.append("END_STREAM|");
            }
        }
        if (hasFlag(flags, END_HEADERS)) {
            sb.append("END_HEADERS|");
        }
        if (hasFlag(flags, PADDED)) {
            sb.append("PADDED|");
        }
        if (hasFlag(flags, PRIORITY)) {
            sb.append("PRIORITY|");
        }
        // report any unknown bits
        int known = 0x01 | END_HEADERS | PADDED | PRIORITY;
        int unknown = flags & ~known;
        if (unknown != 0) {
            sb.append("0x").append(Integer.toHexString(unknown)).append("|");
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '|') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }
}
