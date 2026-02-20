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
package net.hasor.neta.codec.spdy;

/**
 * Default implementation of {@link SpdyContext} backed by the state
 * managed in {@link SpdyFrameDecoder}.
 * <p>
 * Registered on {@link net.hasor.neta.channel.ProtoContext} by
 * {@link SpdyServerDuplexe} or {@link SpdyClientDuplexe} during
 * {@code onInit()}, providing live access to the SPDY session state.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
class SpdyContextImpl implements SpdyContext {
    private final SpdyFrameDecoder decoder;

    SpdyContextImpl(SpdyFrameDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    public boolean isReady() {
        return true; // SPDY has no explicit handshake beyond the SSL/NPN negotiation
    }

    @Override
    public boolean isServer() {
        return this.decoder.isServerMode();
    }

    @Override
    public boolean isClient() {
        return !this.decoder.isServerMode();
    }

    @Override
    public int version() {
        return 3; // SPDY/3.1
    }

    @Override
    public int lastStreamId() {
        return this.decoder.lastStreamId();
    }

    @Override
    public int maxConcurrentStreams() {
        return Integer.MAX_VALUE; // SPDY/3.1 implementation does not negotiate this
    }
}
