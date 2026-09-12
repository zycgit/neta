/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h2;
/**
 * HTTP/2 frame types defined by RFC 9113 Section 4.
 * <p>
 * Each frame type is identified by an 8-bit type code.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-20
 */
final class Http2FrameType {
    /** DATA frame (type=0x00), carrying an arbitrary-length byte sequence. */
    public static final int DATA          = 0x00;
    /** HEADERS frame (type=0x01), used to open a stream and carry header-block fragments. */
    public static final int HEADERS       = 0x01;
    /** PRIORITY frame (type=0x02), used to declare the sender's suggested stream priority. */
    public static final int PRIORITY      = 0x02;
    /** RST_STREAM frame (type=0x03), used to terminate a stream immediately. */
    public static final int RST_STREAM    = 0x03;
    /** SETTINGS frame (type=0x04), used to carry configuration parameters. */
    public static final int SETTINGS      = 0x04;
    /** PUSH_PROMISE frame (type=0x05), used to notify the peer about an upcoming new stream. */
    public static final int PUSH_PROMISE  = 0x05;
    /** PING frame (type=0x06), used to measure RTT and perform liveness checks. */
    public static final int PING          = 0x06;
    /** GOAWAY frame (type=0x07), used to initiate connection shutdown. */
    public static final int GOAWAY        = 0x07;
    /** WINDOW_UPDATE frame (type=0x08), used to manage the flow-control window. */
    public static final int WINDOW_UPDATE = 0x08;
    /** CONTINUATION frame (type=0x09), used to continue transferring header-block fragments. */
    public static final int CONTINUATION  = 0x09;
    /** Synthetic type for the client connection preface. This value is internal only and is never encoded as a wire-level frame type. */
    public static final int PREFACE       = 0xFF;

    private Http2FrameType() {
    }

    /**
     * Returns a readable name for the given frame type code.
     * @param type the frame type code
     * @return the type name
     */
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
            case PREFACE:
                return "PREFACE";
            default:
                return "UNKNOWN(0x" + Integer.toHexString(type) + ")";
        }
    }
}
