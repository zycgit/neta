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
import net.hasor.neta.codec.http.HttpHeaders;

/**
 * HTTP/2 PUSH_PROMISE frame event.
 * <p>
 * The event is published by the message layer after the promised request header block
 * has been fully reassembled and HPACK-decoded.
 */
public class Http2PushPromiseEvent extends AbstractHttp2Event {
    private final int         promisedStreamId;
    private final HttpHeaders headers;

    public Http2PushPromiseEvent(int streamId, int promisedStreamId, HttpHeaders headers) {
        this.streamId(streamId);
        this.promisedStreamId = promisedStreamId;
        this.headers = headers;
    }

    public int promisedStreamId() {
        return this.promisedStreamId;
    }

    public HttpHeaders headers() {
        return this.headers;
    }

    @Override
    public String toString() {
        return "Http2PushPromiseEvent{streamId=" + this.streamId() + ", promisedStreamId=" + this.promisedStreamId + ", headerCount=" + this.headers.headerSize() + '}';
    }
}
