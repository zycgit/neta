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
 * QUIC transport error codes (RFC 9000 §20.1), carried in CONNECTION_CLOSE frames.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface QuicErrorCode {
    /** No error. Used in graceful shutdown. */
    long NO_ERROR = 0x00;

    /** The endpoint encountered an internal error and cannot continue processing. */
    long INTERNAL_ERROR = 0x01;

    /** Server refused the connection attempt during handshake. */
    long CONNECTION_REFUSED = 0x02;

    /** Received more data than permitted by MAX_DATA / MAX_STREAM_DATA limits. */
    long FLOW_CONTROL_ERROR = 0x03;

    /** Received a stream frame for a stream ID exceeding the advertised stream limit. */
    long STREAM_LIMIT_ERROR = 0x04;

    /** Received a frame for a stream that was not in a permitted state. */
    long STREAM_STATE_ERROR = 0x05;

    /** Received a STREAM frame that changed the final size after it was established. */
    long FINAL_SIZE_ERROR = 0x06;

    /** Received a frame with prohibited data or of a prohibited type. */
    long FRAME_ENCODING_ERROR = 0x07;

    /** Received invalid or untimely transport parameters. */
    long TRANSPORT_PARAMETER_ERROR = 0x08;

    /** Peer provided more Connection IDs than the advertised active_connection_id_limit. */
    long CONNECTION_ID_LIMIT_ERROR = 0x09;

    /** Generic protocol compliance error not covered by more specific codes. */
    long PROTOCOL_VIOLATION = 0x0a;

    /** Server received a client Initial containing an invalid Token field. */
    long INVALID_TOKEN = 0x0b;

    /** The application or application protocol caused the connection to be closed. */
    long APPLICATION_ERROR = 0x0c;

    /** Endpoint could not keep pace with the peer's rate of new CRYPTO data. */
    long CRYPTO_BUFFER_EXCEEDED = 0x0d;

    /** Endpoint detected errors during key update. */
    long KEY_UPDATE_ERROR = 0x0e;

    /** AEAD confidentiality or integrity limit reached for this connection. */
    long AEAD_LIMIT_REACHED = 0x0f;

    /** Network path cannot support QUIC (e.g., MTU too small). */
    long NO_VIABLE_PATH = 0x10;
}