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
package net.hasor.neta.codec.spdy;

/**
 * SPDY/3.1 frame types.
 * <p>
 * SPDY defines two categories of frames:
 * <ul>
 *   <li><b>Data frames</b>: identified by the control bit = 0 in the frame header</li>
 *   <li><b>Control frames</b>: identified by the control bit = 1 in the frame header</li>
 * </ul>
 * The type values below are for control frames.
 */
public final class SpdyFrameType {
    /** SYN_STREAM (type=1) - creates a new stream. */
    public static final int SYN_STREAM    = 1;
    /** SYN_REPLY (type=2) - acknowledges a SYN_STREAM and sends response headers. */
    public static final int SYN_REPLY     = 2;
    /** RST_STREAM (type=3) - abnormal termination of a stream. */
    public static final int RST_STREAM    = 3;
    /** SETTINGS (type=4) - conveys configuration parameters. */
    public static final int SETTINGS      = 4;
    /** PING (type=6) - used to measure RTT. */
    public static final int PING          = 6;
    /** GOAWAY (type=7) - graceful shutdown of a connection. */
    public static final int GOAWAY        = 7;
    /** HEADERS (type=8) - augments a stream with additional headers. */
    public static final int HEADERS       = 8;
    /** WINDOW_UPDATE (type=9) - flow control. */
    public static final int WINDOW_UPDATE = 9;

    private SpdyFrameType() {
    }

    /** Returns a human-readable name for the given frame type. */
    public static String name(int type) {
        switch (type) {
            case SYN_STREAM:
                return "SYN_STREAM";
            case SYN_REPLY:
                return "SYN_REPLY";
            case RST_STREAM:
                return "RST_STREAM";
            case SETTINGS:
                return "SETTINGS";
            case PING:
                return "PING";
            case GOAWAY:
                return "GOAWAY";
            case HEADERS:
                return "HEADERS";
            case WINDOW_UPDATE:
                return "WINDOW_UPDATE";
            default:
                return "UNKNOWN(" + type + ")";
        }
    }
}
