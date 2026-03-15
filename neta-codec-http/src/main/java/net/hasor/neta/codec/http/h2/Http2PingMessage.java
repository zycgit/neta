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

/** Semantic PING message carrying the opaque 8-byte payload. */
public class Http2PingMessage extends AbstractHttp2Message {
    private final boolean ack;
    private final byte[]  opaqueData;

    public Http2PingMessage(boolean ack, byte[] opaqueData) {
        if (opaqueData == null || opaqueData.length != 8) {
            throw new IllegalArgumentException("opaqueData must be exactly 8 bytes");
        }
        this.ack = ack;
        this.opaqueData = opaqueData.clone();
    }

    @Override
    public Type messageType() {
        return Type.PING;
    }

    public boolean ack() {
        return this.ack;
    }

    public byte[] opaqueData() {
        return this.opaqueData.clone();
    }
}