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
import net.hasor.neta.codec.http.DefaultHttpHeaders;

/** Complete HTTP/2 header-block message assembled from HEADERS and CONTINUATION frames. */
public class Http2HeadersMessage extends AbstractHttp2Message {
    private final DefaultHttpHeaders headers;
    private final boolean            endStream;

    public Http2HeadersMessage(int streamId, DefaultHttpHeaders headers, boolean endStream) {
        if (headers == null) {
            throw new IllegalArgumentException("headers must not be null");
        }
        this.streamId(streamId);
        this.headers = headers;
        this.endStream = endStream;
    }

    @Override
    public Type messageType() {
        return Type.HEADERS;
    }

    public DefaultHttpHeaders headers() {
        return this.headers;
    }

    public boolean endStream() {
        return this.endStream;
    }

    @Override
    public String toString() {
        return "Http2HeadersMessage{streamId=" + streamId() + ", endStream=" + this.endStream + ", headers=" + this.headers + '}';
    }
}