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
 * HTTP/2 error codes as defined in RFC 9113, Section 7.
 * <p>
 * These are used in RST_STREAM and GOAWAY frames to indicate the reason
 * for terminating a stream or a connection.
 */
public final class Http2ErrorCode {
    /** Graceful shutdown. */
    public static final long NO_ERROR            = 0x00;
    /** Unknown or unspecified error. */
    public static final long PROTOCOL_ERROR      = 0x01;
    /** The endpoint detected an internal error. */
    public static final long INTERNAL_ERROR      = 0x02;
    /** The endpoint detected that its peer is exhibiting flow-control errors. */
    public static final long FLOW_CONTROL_ERROR  = 0x03;
    /** The endpoint sent a SETTINGS frame but did not receive ACK in time. */
    public static final long SETTINGS_TIMEOUT    = 0x04;
    /** The endpoint received a frame after a stream was half-closed. */
    public static final long STREAM_CLOSED       = 0x05;
    /** The endpoint received a frame with an invalid size. */
    public static final long FRAME_SIZE_ERROR    = 0x06;
    /** The endpoint refused the stream. */
    public static final long REFUSED_STREAM      = 0x07;
    /** Indicate that the stream is no longer needed. */
    public static final long CANCEL              = 0x08;
    /** HPACK header compression context is not being used. */
    public static final long COMPRESSION_ERROR   = 0x09;
    /** The connection established in response to a CONNECT request was reset. */
    public static final long CONNECT_ERROR       = 0x0a;
    /** The endpoint detected that its peer is generating excessive load. */
    public static final long ENHANCE_YOUR_CALM   = 0x0b;
    /** The transport-layer security is not sufficient. */
    public static final long INADEQUATE_SECURITY = 0x0c;
    /** The endpoint requires HTTP/1.1 instead of HTTP/2. */
    public static final long HTTP_1_1_REQUIRED   = 0x0d;

    private Http2ErrorCode() {
    }

    /** Returns a human-readable name for the given error code. */
    public static String name(long code) {
        switch ((int) code) {
            case (int) NO_ERROR:
                return "NO_ERROR";
            case (int) PROTOCOL_ERROR:
                return "PROTOCOL_ERROR";
            case (int) INTERNAL_ERROR:
                return "INTERNAL_ERROR";
            case (int) FLOW_CONTROL_ERROR:
                return "FLOW_CONTROL_ERROR";
            case (int) SETTINGS_TIMEOUT:
                return "SETTINGS_TIMEOUT";
            case (int) STREAM_CLOSED:
                return "STREAM_CLOSED";
            case (int) FRAME_SIZE_ERROR:
                return "FRAME_SIZE_ERROR";
            case (int) REFUSED_STREAM:
                return "REFUSED_STREAM";
            case (int) CANCEL:
                return "CANCEL";
            case (int) COMPRESSION_ERROR:
                return "COMPRESSION_ERROR";
            case (int) CONNECT_ERROR:
                return "CONNECT_ERROR";
            case (int) ENHANCE_YOUR_CALM:
                return "ENHANCE_YOUR_CALM";
            case (int) INADEQUATE_SECURITY:
                return "INADEQUATE_SECURITY";
            case (int) HTTP_1_1_REQUIRED:
                return "HTTP_1_1_REQUIRED";
            default:
                return "UNKNOWN(0x" + Long.toHexString(code) + ")";
        }
    }
}
