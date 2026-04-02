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

/** HTTP/2 event emitted when one direction of a stream reaches END_STREAM. */
public class Http2StreamCloseEvent extends AbstractHttp2Event {
    private final boolean inbound;

    public Http2StreamCloseEvent(int streamId, boolean inbound) {
        this.streamId(streamId);
        this.inbound = inbound;
    }

    public boolean inbound() {
        return this.inbound;
    }
}