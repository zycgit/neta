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
 * Default implementation of {@link Http2Context}.
 * <p>
 * The underlying state is provided by {@link Http2DecoderContent} and is installed into the root
 * context during HTTP/2 decoder initialization before being exposed through the current
 * {@link net.hasor.neta.channel.ProtoContext}. This object does not own protocol state by itself;
 * it only exposes a live read-only view of the same HTTP/2 connection.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-20
 */
class Http2ContextImpl implements Http2Context {
    private final boolean             serverMode;
    private final Http2DecoderContent state;

    Http2ContextImpl(boolean serverMode, Http2DecoderContent state) {
        this.serverMode = serverMode;
        this.state = state;
    }

    @Override
    public boolean isReady() {
        return this.state.isPrefaceReceived();
    }

    @Override
    public boolean isServer() {
        return this.serverMode;
    }

    @Override
    public boolean isClient() {
        return !this.serverMode;
    }

    @Override
    public long lastStreamId() {
        return this.state.lastStreamId();
    }

    @Override
    public long maxConcurrentStreams() {
        return this.state.remoteMaxConcurrentStreams();
    }

    @Override
    public int initialWindowSize() {
        return this.state.remoteInitialWindowSize();
    }

    @Override
    public int maxFrameSize() {
        return this.state.remoteMaxFrameSize();
    }
}
