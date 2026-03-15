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

/** Semantic GOAWAY message. */
public class Http2GoAwayMessage extends AbstractHttp2Message {
    private final int    lastStreamId;
    private final long   errorCode;
    private final byte[] debugData;

    public Http2GoAwayMessage(int lastStreamId, long errorCode, byte[] debugData) {
        this.lastStreamId = lastStreamId;
        this.errorCode = errorCode;
        this.debugData = debugData == null ? new byte[0] : debugData.clone();
    }

    @Override
    public Type messageType() {
        return Type.GOAWAY;
    }

    public int lastStreamId() {
        return this.lastStreamId;
    }

    public long errorCode() {
        return this.errorCode;
    }

    public byte[] debugData() {
        return this.debugData.clone();
    }
}