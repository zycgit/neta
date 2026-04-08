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
 * {@link Http3Context} 的默认实现，底层状态由 {@link Http3DecoderContent} 管理。
 * <p>
 * 该实现提供 HTTP/3 连接状态的实时只读访问。
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
class Http3ContextImpl implements Http3Context {
    private final boolean             serverMode;
    private final Http3DecoderContent content;

    Http3ContextImpl(boolean serverMode, Http3DecoderContent content) {
        this.serverMode = serverMode;
        this.content = content;
    }

    @Override
    public boolean isReady() {
        return this.content.isSettingsReceived();
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
        return this.content.lastStreamId();
    }

    @Override
    public long maxFieldSectionSize() {
        return this.content.maxFieldSectionSize();
    }

    @Override
    public long qpackMaxTableCapacity() {
        return this.content.qpackMaxTableCapacity();
    }

    @Override
    public long qpackBlockedStreams() {
        return this.content.qpackBlockedStreams();
    }
}
