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
 * QUIC transport error codes as defined in RFC 9000, Section 20.1.
 * <p>
 * These error codes are used in CONNECTION_CLOSE and RESET_STREAM frames
 * to indicate the reason for terminating a connection or stream.
 */
public final class QuicErrorCode {
    /** No error. This is used when the connection or stream needs to be closed, but there is no error to signal. */
    public static final long NO_ERROR                  = 0x00;
    /** The endpoint encountered an internal error and cannot continue with the connection. */
    public static final long INTERNAL_ERROR            = 0x01;
    /** The server refused to accept a new connection. */
    public static final long CONNECTION_REFUSED        = 0x02;
    /** An endpoint received more data than it permitted in its advertised data limits. */
    public static final long FLOW_CONTROL_ERROR        = 0x03;
    /** An endpoint received a frame for a stream identifier that exceeded its advertised stream limit. */
    public static final long STREAM_LIMIT_ERROR        = 0x04;
    /** An endpoint received a frame for a stream that was not in a state permitting that frame. */
    public static final long STREAM_STATE_ERROR        = 0x05;
    /** An endpoint received a frame with a size that was invalid for that frame type. */
    public static final long FINAL_SIZE_ERROR          = 0x06;
    /** An endpoint received a frame that was badly formatted. */
    public static final long FRAME_ENCODING_ERROR      = 0x07;
    /** An endpoint received transport parameters that were badly formatted. */
    public static final long TRANSPORT_PARAMETER_ERROR = 0x08;
    /** The number of connection IDs provided by the peer exceeds the advertised active_connection_id_limit. */
    public static final long CONNECTION_ID_LIMIT_ERROR = 0x09;
    /** An endpoint detected an error with protocol compliance. */
    public static final long PROTOCOL_VIOLATION        = 0x0a;
    /** A server received a client Initial that contained an invalid Token field. */
    public static final long INVALID_TOKEN             = 0x0b;
    /** The application or application protocol caused the connection to be closed. */
    public static final long APPLICATION_ERROR         = 0x0c;
    /** An endpoint has received more data than the maximum data size it allows (in CRYPTO frames). */
    public static final long CRYPTO_BUFFER_EXCEEDED    = 0x0d;
    /** An endpoint detected errors in performing key updates. */
    public static final long KEY_UPDATE_ERROR          = 0x0e;
    /** An endpoint has reached the maximum number of acknowledgment delays. */
    public static final long AEAD_LIMIT_REACHED        = 0x0f;
    /** No viable network path exists. */
    public static final long NO_VIABLE_PATH            = 0x10;
    /** TLS handshake error base (0x0100 + TLS alert code). */
    public static final long CRYPTO_ERROR_BASE         = 0x0100;

    private QuicErrorCode() {
    }

    /** Returns a human-readable name for the given error code. */
    public static String name(long code) {
        if (code == NO_ERROR)
            return "NO_ERROR";
        if (code == INTERNAL_ERROR)
            return "INTERNAL_ERROR";
        if (code == CONNECTION_REFUSED)
            return "CONNECTION_REFUSED";
        if (code == FLOW_CONTROL_ERROR)
            return "FLOW_CONTROL_ERROR";
        if (code == STREAM_LIMIT_ERROR)
            return "STREAM_LIMIT_ERROR";
        if (code == STREAM_STATE_ERROR)
            return "STREAM_STATE_ERROR";
        if (code == FINAL_SIZE_ERROR)
            return "FINAL_SIZE_ERROR";
        if (code == FRAME_ENCODING_ERROR)
            return "FRAME_ENCODING_ERROR";
        if (code == TRANSPORT_PARAMETER_ERROR)
            return "TRANSPORT_PARAMETER_ERROR";
        if (code == CONNECTION_ID_LIMIT_ERROR)
            return "CONNECTION_ID_LIMIT_ERROR";
        if (code == PROTOCOL_VIOLATION)
            return "PROTOCOL_VIOLATION";
        if (code == INVALID_TOKEN)
            return "INVALID_TOKEN";
        if (code == APPLICATION_ERROR)
            return "APPLICATION_ERROR";
        if (code == CRYPTO_BUFFER_EXCEEDED)
            return "CRYPTO_BUFFER_EXCEEDED";
        if (code == KEY_UPDATE_ERROR)
            return "KEY_UPDATE_ERROR";
        if (code == AEAD_LIMIT_REACHED)
            return "AEAD_LIMIT_REACHED";
        if (code == NO_VIABLE_PATH)
            return "NO_VIABLE_PATH";
        if (code >= CRYPTO_ERROR_BASE && code < CRYPTO_ERROR_BASE + 256) {
            return "CRYPTO_ERROR(0x" + Long.toHexString(code - CRYPTO_ERROR_BASE) + ")";
        }
        return "UNKNOWN(0x" + Long.toHexString(code) + ")";
    }
}
