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
package net.hasor.neta.codec.http.h3;
/**
 * Default implementation of {@link Http3Context} backed by the state
 * managed in {@link Http3FrameDecoder}.
 * <p>
 * Created via {@link Http3FrameDecoder#createContext()}, providing live
 * access to the HTTP/3 connection state.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
class Http3ContextImpl implements Http3Context {
    private final Http3FrameDecoder decoder;

    Http3ContextImpl(Http3FrameDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    public boolean isReady() {
        return this.decoder.isSettingsReceived();
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
    public long lastStreamId() {
        return this.decoder.lastStreamId();
    }

    @Override
    public long maxFieldSectionSize() {
        return this.decoder.peerSettings().maxFieldSectionSize();
    }

    @Override
    public long qpackMaxTableCapacity() {
        return this.decoder.peerSettings().qpackMaxTableCapacity();
    }

    @Override
    public long qpackBlockedStreams() {
        return this.decoder.peerSettings().qpackBlockedStreams();
    }
}
