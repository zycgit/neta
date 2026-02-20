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
 * QUIC stream states as defined in RFC 9000, Section 3.
 * <p>
 * QUIC streams have separate state machines for sending and receiving sides.
 * This enum represents the combined logical states for codec-layer tracking.
 */
public enum QuicStreamState {
    /** Stream has been created but no data has been sent or received. */
    IDLE,
    /** Stream is open for sending and receiving data. */
    OPEN,
    /** Local side has sent FIN; only receiving data. */
    HALF_CLOSED_LOCAL,
    /** Remote side has sent FIN; only sending data. */
    HALF_CLOSED_REMOTE,
    /** Stream is fully closed in both directions. */
    CLOSED,
    /** Stream was reset by the local endpoint (RESET_STREAM sent). */
    RESET_LOCAL,
    /** Stream was reset by the remote endpoint (RESET_STREAM received). */
    RESET_REMOTE
}
