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
 * HTTP/2 event published when one direction of a stream reaches END_STREAM.
 * <p>
 * This event does not correspond to an independent control frame. Instead, it is emitted by the
 * HTTP/2 message layer when either the inbound or outbound direction has completed a half-close.
 * The inbound direction is triggered after the decoder processes HEADERS or DATA carrying
 * {@code END_STREAM}, and the outbound direction is triggered after the encoder writes the final
 * header block or data block carrying {@code END_STREAM}.
 * </p>
 * <p>
 * Sequence diagram:
 * <pre>
 * Remote inbound END_STREAM
 *   Remote peer           Http2ObjectDecoder        ProtoContext        Application Handler
 *      |                        |                      |                      |
 *      | HEADERS / DATA         |                      |                      |
 *      | END_STREAM             |                      |                      |
 *      |----------------------->|                      |                      |
 *      |                        | fireEvent(remote,    |                      |
 *      |                        | inbound=true)        |                      |
 *      |                        |--------------------->|                      |
 *      |                        |                      | Http2StreamCloseEvent|
 *      |                        |                      |--------------------->|
 * </pre><pre>
 * Local outbound END_STREAM
 *   Application Handler   Http2ObjectEncoder       ProtoContext        Application Handler
 *          |                      |                      |                      |
 *          | LastHttpContent /    |                      |                      |
 *          | endStream headers    |                      |                      |
 *          |--------------------->|                      |                      |
 *          |                      | fireEventRcv(local,  |                      |
 *          |                      | inbound=false)       |                      |
 *          |                      |--------------------->|                      |
 *          |                      |                      | Http2StreamCloseEvent|
 *          |                      |                      |--------------------->|
 * </pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-03
 */
public class Http2StreamCloseEvent extends AbstractHttp2Event {
    private final boolean inbound;

    /**
     * Creates a stream-close event.
     * @param streamId the stream ID
     * @param inbound whether the closed direction is inbound
     */
    public Http2StreamCloseEvent(long streamId, boolean inbound) {
        this.streamId(streamId);
        this.inbound = inbound;
    }

    /**
     * Returns whether the closed direction is inbound.
     */
    public boolean inbound() {
        return this.inbound;
    }
}