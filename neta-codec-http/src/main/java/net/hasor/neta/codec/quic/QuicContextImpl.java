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
 * Default implementation of {@link QuicContext} backed by the state
 * managed in {@link QuicFrameDecoder}.
 * <p>
 * Registered on {@link net.hasor.neta.channel.ProtoContext} by the
 * HTTP/3 duplexer ({@link net.hasor.neta.codec.http3.Http3ServerDuplexe}
 * or {@link net.hasor.neta.codec.http3.Http3ClientDuplexe}) during
 * {@code onInit()}, providing live access to the QUIC transport state.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
class QuicContextImpl implements QuicContext {
    private final QuicFrameDecoder decoder;

    QuicContextImpl(QuicFrameDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    public boolean isReady() {
        // QUIC handshake readiness is approximated by having processed frames
        return true;
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
    public int negotiatedVersion() {
        // QUIC v1 (RFC 9000)
        return 0x00000001;
    }

    @Override
    public String peerConnectionId() {
        return this.decoder.peerConnectionId();
    }

    @Override
    public String localConnectionId() {
        return this.decoder.localConnectionId();
    }

    @Override
    public long maxBidiStreams() {
        return this.decoder.remoteSettings().initialMaxStreamsBidi();
    }

    @Override
    public long maxUniStreams() {
        return this.decoder.remoteSettings().initialMaxStreamsUni();
    }

    @Override
    public long idleTimeout() {
        return this.decoder.remoteSettings().maxIdleTimeout();
    }
}
