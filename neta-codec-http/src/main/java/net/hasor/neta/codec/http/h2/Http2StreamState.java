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
 * HTTP/2 stream state machine as defined in RFC 9113, Section 5.1.
 * <p>
 * Stream states and valid transitions:
 * <pre>
 *                      +--------+
 *              send PP |        | recv PP
 *             ,--------|  idle  |--------.
 *            /         |        |         \
 *           v          +--------+          v
 *    +----------+          |           +----------+
 *    |          |          | send H /  |          |
 *    | reserved |          | recv H    | reserved |
 *    | (local)  |          |           | (remote) |
 *    +----------+          v           +----------+
 *           |          +--------+           |
 *           |          |        |           |
 *           |          |  open  |           |
 *           |          |        |           |
 *           |          +--------+           |
 *           |         /   |     \           |
 *           v        /    |      \          v
 *    +----------+   v     |       v   +----------+
 *    |   half   |         |           |   half   |
 *    |  closed  |         |           |  closed  |
 *    | (remote) |         |           |  (local) |
 *    +----------+         |           +----------+
 *           |             |                |
 *           v             v                v
 *           +----------+---+----------+
 *                       |             |
 *                       |   closed    |
 *                       |             |
 *                       +-------------+
 * </pre>
 */
public enum Http2StreamState {
    /** Stream has not been opened yet. */
    IDLE,
    /** Remote endpoint has reserved this stream via PUSH_PROMISE. */
    RESERVED_LOCAL,
    /** Local endpoint has reserved this stream via PUSH_PROMISE. */
    RESERVED_REMOTE,
    /** Stream is open and can send/receive frames. */
    OPEN,
    /** Local endpoint has sent END_STREAM; can only receive. */
    HALF_CLOSED_LOCAL,
    /** Remote endpoint has sent END_STREAM; can only send. */
    HALF_CLOSED_REMOTE,
    /** Stream is closed. */
    CLOSED
}
