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
 * HTTP/2 stream state machine defined by RFC 9113 Section 5.1.
 * <p>
 * Legend:
 * <pre>
 *   H  = HEADERS frame that opens the stream
 *   PP = PUSH_PROMISE that reserves the stream
 * </pre>
 * The main legal transitions are shown below:
 * <pre>
 *   +--------+ -- send H / recv H --> +--------------------+
 *   |  idle  |                        |        open        |
 *   +--------+ &lt;-- send PP ---------- +--------------------+
 *       |                                  |            |
 *       | recv PP                          | send ES    | recv ES
 *       v                                  v            v
 *   +-------------------+          +---------------+  +-----------------+
 *   | reserved (local)  |          | half-closed   |  | half-closed     |
 *   | waiting recv H    |          |   (local)     |  |   (remote)      |
 *   +-------------------+          +---------------+  +-----------------+
 *       |                                  |                    |
 *       | recv H                           | recv ES            | send ES
 *       v                                  v                    v
 *   +-----------------+                +------------------------------+
 *   | half-closed     |--------------> |            closed            |
 *   |   (remote)      |                +------------------------------+
 *   +-----------------+
 * </pre><pre>
 *   +--------+ -- recv PP --> +--------------------+ -- send H --> +---------------+
 *   |  idle  |                | reserved (remote)  |               | half-closed   |
 *   +--------+                | waiting send H     |               |   (local)     |
 *                             +--------------------+               +---------------+
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-20
 */
public enum Http2StreamState {
    /** The stream has not been opened yet. */
    IDLE,
    /** The remote endpoint reserved the stream through PUSH_PROMISE. */
    RESERVED_LOCAL,
    /** The local endpoint reserved the stream through PUSH_PROMISE. */
    RESERVED_REMOTE,
    /** The stream is open and can send or receive frames. */
    OPEN,
    /** The local endpoint has sent END_STREAM and may only continue receiving. */
    HALF_CLOSED_LOCAL,
    /** The remote endpoint has sent END_STREAM and may only continue sending. */
    HALF_CLOSED_REMOTE,
    /** The stream is closed. */
    CLOSED
}