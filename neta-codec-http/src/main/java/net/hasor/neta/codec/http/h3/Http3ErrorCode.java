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
package net.hasor.neta.codec.http.h3;
/**
 * HTTP/3 error codes as defined in RFC 9114, Section 8.1.
 * <p>
 * These error codes are used in QUIC RESET_STREAM, STOP_SENDING, and
 * CONNECTION_CLOSE frames with type=0x1d (application protocol error).
 */
public final class Http3ErrorCode {
    /** No error. This is used when the connection or stream needs to be closed, but there is no error to signal. */
    public static final long H3_NO_ERROR               = 0x0100;
    /** Peer violated protocol requirements in a way not covered by more specific error codes. */
    public static final long H3_GENERAL_PROTOCOL_ERROR = 0x0101;
    /** An internal error has occurred in the HTTP stack. */
    public static final long H3_INTERNAL_ERROR         = 0x0102;
    /** The endpoint detected that its peer created a stream that it will not accept. */
    public static final long H3_STREAM_CREATION_ERROR  = 0x0103;
    /** A stream required by the HTTP/3 connection was closed or reset. */
    public static final long H3_CLOSED_CRITICAL_STREAM = 0x0104;
    /** A frame was received that was not permitted in the current state or on the current stream. */
    public static final long H3_FRAME_UNEXPECTED       = 0x0105;
    /** A frame that fails to satisfy layout requirements or exceeds the size limit was received. */
    public static final long H3_FRAME_ERROR            = 0x0106;
    /** The endpoint detected that its peer is exhibiting excessive load. */
    public static final long H3_EXCESSIVE_LOAD         = 0x0107;
    /** A Stream ID or Push ID was used incorrectly. */
    public static final long H3_ID_ERROR               = 0x0108;
    /** An endpoint detected an error in the payload of a SETTINGS frame. */
    public static final long H3_SETTINGS_ERROR         = 0x0109;
    /** No SETTINGS frame was received at the beginning of the control stream. */
    public static final long H3_MISSING_SETTINGS       = 0x010a;
    /** A server rejected a request without performing any application processing. */
    public static final long H3_REQUEST_REJECTED       = 0x010b;
    /** The request or its response (including pushed response) is cancelled. */
    public static final long H3_REQUEST_CANCELLED      = 0x010c;
    /** The client's stream terminated without containing a fully-formed request. */
    public static final long H3_REQUEST_INCOMPLETE     = 0x010d;
    /** An HTTP message was malformed and cannot be processed. */
    public static final long H3_MESSAGE_ERROR          = 0x010e;
    /** The TCP connection established in response to a CONNECT request was reset or abnormally closed. */
    public static final long H3_CONNECT_ERROR          = 0x010f;
    /** The requested operation cannot be served over HTTP/3. */
    public static final long H3_VERSION_FALLBACK       = 0x0110;

    // QPACK error codes (RFC 9204, Section 6)
    /** QPACK decompression failed. */
    public static final long QPACK_DECOMPRESSION_FAILED = 0x0200;
    /** QPACK encoder stream error. */
    public static final long QPACK_ENCODER_STREAM_ERROR = 0x0201;
    /** QPACK decoder stream error. */
    public static final long QPACK_DECODER_STREAM_ERROR = 0x0202;

    private Http3ErrorCode() {
    }

    /** Returns a human-readable name for the given error code. */
    public static String name(long code) {
        if (code == H3_NO_ERROR)
            return "H3_NO_ERROR";
        if (code == H3_GENERAL_PROTOCOL_ERROR)
            return "H3_GENERAL_PROTOCOL_ERROR";
        if (code == H3_INTERNAL_ERROR)
            return "H3_INTERNAL_ERROR";
        if (code == H3_STREAM_CREATION_ERROR)
            return "H3_STREAM_CREATION_ERROR";
        if (code == H3_CLOSED_CRITICAL_STREAM)
            return "H3_CLOSED_CRITICAL_STREAM";
        if (code == H3_FRAME_UNEXPECTED)
            return "H3_FRAME_UNEXPECTED";
        if (code == H3_FRAME_ERROR)
            return "H3_FRAME_ERROR";
        if (code == H3_EXCESSIVE_LOAD)
            return "H3_EXCESSIVE_LOAD";
        if (code == H3_ID_ERROR)
            return "H3_ID_ERROR";
        if (code == H3_SETTINGS_ERROR)
            return "H3_SETTINGS_ERROR";
        if (code == H3_MISSING_SETTINGS)
            return "H3_MISSING_SETTINGS";
        if (code == H3_REQUEST_REJECTED)
            return "H3_REQUEST_REJECTED";
        if (code == H3_REQUEST_CANCELLED)
            return "H3_REQUEST_CANCELLED";
        if (code == H3_REQUEST_INCOMPLETE)
            return "H3_REQUEST_INCOMPLETE";
        if (code == H3_MESSAGE_ERROR)
            return "H3_MESSAGE_ERROR";
        if (code == H3_CONNECT_ERROR)
            return "H3_CONNECT_ERROR";
        if (code == H3_VERSION_FALLBACK)
            return "H3_VERSION_FALLBACK";
        if (code == QPACK_DECOMPRESSION_FAILED)
            return "QPACK_DECOMPRESSION_FAILED";
        if (code == QPACK_ENCODER_STREAM_ERROR)
            return "QPACK_ENCODER_STREAM_ERROR";
        if (code == QPACK_DECODER_STREAM_ERROR)
            return "QPACK_DECODER_STREAM_ERROR";
        return "UNKNOWN(0x" + Long.toHexString(code) + ")";
    }
}
