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
package net.hasor.neta.codec.http2;

/**
 * HTTP/2 frame types as defined in RFC 9113 (Section 4).
 * <p>
 * Each frame type is identified by an 8-bit type code.
 */
public final class Http2FrameType {
    /** DATA frame (type=0x00) - conveys arbitrary, variable-length sequences of octets. */
    public static final int DATA          = 0x00;
    /** HEADERS frame (type=0x01) - opens a stream and carries header block fragment. */
    public static final int HEADERS       = 0x01;
    /** PRIORITY frame (type=0x02) - specifies sender-advised priority of a stream. */
    public static final int PRIORITY      = 0x02;
    /** RST_STREAM frame (type=0x03) - allows immediate termination of a stream. */
    public static final int RST_STREAM    = 0x03;
    /** SETTINGS frame (type=0x04) - conveys configuration parameters. */
    public static final int SETTINGS      = 0x04;
    /** PUSH_PROMISE frame (type=0x05) - notify peer of an intent to initiate streams. */
    public static final int PUSH_PROMISE  = 0x05;
    /** PING frame (type=0x06) - mechanism for measuring RTT and performing liveness checks. */
    public static final int PING          = 0x06;
    /** GOAWAY frame (type=0x07) - initiate shutdown of a connection. */
    public static final int GOAWAY        = 0x07;
    /** WINDOW_UPDATE frame (type=0x08) - manage flow control. */
    public static final int WINDOW_UPDATE = 0x08;
    /** CONTINUATION frame (type=0x09) - continue a sequence of header block fragments. */
    public static final int CONTINUATION  = 0x09;

    private Http2FrameType() {
    }

    /** Returns a human-readable name for the given frame type code. */
    public static String name(int type) {
        switch (type) {
            case DATA:
                return "DATA";
            case HEADERS:
                return "HEADERS";
            case PRIORITY:
                return "PRIORITY";
            case RST_STREAM:
                return "RST_STREAM";
            case SETTINGS:
                return "SETTINGS";
            case PUSH_PROMISE:
                return "PUSH_PROMISE";
            case PING:
                return "PING";
            case GOAWAY:
                return "GOAWAY";
            case WINDOW_UPDATE:
                return "WINDOW_UPDATE";
            case CONTINUATION:
                return "CONTINUATION";
            default:
                return "UNKNOWN(0x" + Integer.toHexString(type) + ")";
        }
    }
}
