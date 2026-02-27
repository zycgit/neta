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
 * QUIC transport error codes as defined in RFC 9000, Section 20.1.
 * <p>These codes are carried in the {@code error_code} field of
 * {@code CONNECTION_CLOSE} frames (type 0x1c).
 * @author 赵永春 (zyc@hasor.net)
 */
public interface QuicErrorCode {
    /** No error. Used in graceful shutdown. */
    long NO_ERROR = 0x00;

    /** The endpoint encountered an internal error and cannot continue processing. */
    long INTERNAL_ERROR = 0x01;

    /**
     * The server refused the connection attempt.
     * Only applicable during the handshake.
     */
    long CONNECTION_REFUSED = 0x02;

    /**
     * An endpoint received more data than it permitted in its advertised
     * data limits (MAX_DATA / MAX_STREAM_DATA).
     */
    long FLOW_CONTROL_ERROR = 0x03;

    /**
     * An endpoint received a frame for a stream identifier that exceeded its
     * advertised stream limit for the corresponding stream type.
     * @see <a href="https://www.rfc-editor.org/rfc/rfc9000#section-20.1">RFC 9000 §20.1</a>
     */
    long STREAM_LIMIT_ERROR = 0x04;

    /**
     * An endpoint received a frame for a stream that was not in a state that
     * permitted that frame to be received.
     */
    long STREAM_STATE_ERROR = 0x05;

    /**
     * A STREAM frame was received that indicated that the final size changed
     * after it had been established.
     */
    long FINAL_SIZE_ERROR = 0x06;

    /**
     * A frame that contained prohibited data was received, or a frame of
     * prohibited type was received.
     */
    long FRAME_ENCODING_ERROR = 0x07;

    /**
     * An endpoint received transport parameters that it found invalid, or it
     * received transport parameters before the end of the handshake.
     */
    long TRANSPORT_PARAMETER_ERROR = 0x08;

    /**
     * The number of Connection IDs provided by the peer exceeded the advertised
     * active_connection_id_limit.
     */
    long CONNECTION_ID_LIMIT_ERROR = 0x09;

    /**
     * An endpoint detected an error with protocol compliance that was not
     * covered by more specific error codes; also used when it does not wish to
     * reveal more specific information.
     */
    long PROTOCOL_VIOLATION = 0x0a;

    /**
     * A server received a client Initial that contained an invalid Token field.
     */
    long INVALID_TOKEN = 0x0b;

    /** The application or application protocol caused the connection to be closed. */
    long APPLICATION_ERROR = 0x0c;

    /**
     * An endpoint could not keep up with the rate at which its peer created
     * new CRYPTO data.
     */
    long CRYPTO_BUFFER_EXCEEDED = 0x0d;

    /**
     * An endpoint detected errors in performing key updates.
     */
    long KEY_UPDATE_ERROR = 0x0e;

    /**
     * An endpoint has reached the confidentiality or integrity limit for the
     * AEAD algorithm used by the given connection.
     */
    long AEAD_LIMIT_REACHED = 0x0f;

    /**
     * An endpoint has determined that the network path is incapable of supporting
     * QUIC. An endpoint is unlikely to receive a CONNECTION_CLOSE frame carrying
     * this code except when the path does not support a large enough MTU.
     */
    long NO_VIABLE_PATH = 0x10;
}