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
package net.hasor.neta.channel.transport.quic;
/**
 * QUIC transport-layer error code constants.
 * <p>These error codes are defined in RFC 9000 Section 20.1 and are typically reported through CONNECTION_CLOSE frames when the connection is closed.
 * @author 赵永春 (zyc@hasor.net)
 */
public interface QuicErrorCode {
    /** No error, typically used for graceful shutdown. */
    long NO_ERROR = 0x00;

    /** An internal error occurred at the endpoint and processing cannot continue. */
    long INTERNAL_ERROR = 0x01;

    /** The server rejected the connection during the handshake phase. */
    long CONNECTION_REFUSED = 0x02;

    /** Received data exceeded the limit allowed by MAX_DATA or MAX_STREAM_DATA. */
    long FLOW_CONTROL_ERROR = 0x03;

    /** A received stream frame referenced a stream ID beyond the advertised stream count limit. */
    long STREAM_LIMIT_ERROR = 0x04;

    /** A received frame referred to a stream for which that frame is not allowed in the current state. */
    long STREAM_STATE_ERROR = 0x05;

    /** A received STREAM frame attempted to change the final size after it had already been determined. */
    long FINAL_SIZE_ERROR = 0x06;

    /** Received prohibited data contents or a prohibited frame type. */
    long FRAME_ENCODING_ERROR = 0x07;

    /** Received invalid transport parameters or transport parameters at the wrong time. */
    long TRANSPORT_PARAMETER_ERROR = 0x08;

    /** The number of Connection IDs provided by the peer exceeded the advertised active_connection_id_limit. */
    long CONNECTION_ID_LIMIT_ERROR = 0x09;

    /** A generic protocol violation that does not fit a more specific error code. */
    long PROTOCOL_VIOLATION = 0x0a;

    /** The Initial packet received by the server carried an invalid Token. */
    long INVALID_TOKEN = 0x0b;

    /** The connection was closed for application-layer or higher-level protocol reasons. */
    long APPLICATION_ERROR = 0x0c;

    /** The endpoint could not keep up with the rate at which the peer sent new CRYPTO data. */
    long CRYPTO_BUFFER_EXCEEDED = 0x0d;

    /** The endpoint detected an error during key update. */
    long KEY_UPDATE_ERROR = 0x0e;

    /** The AEAD confidentiality or integrity usage limit for the current connection has been reached. */
    long AEAD_LIMIT_REACHED = 0x0f;

    /** The current network path cannot carry QUIC, for example because the MTU is too small. */
    long NO_VIABLE_PATH = 0x10;
}