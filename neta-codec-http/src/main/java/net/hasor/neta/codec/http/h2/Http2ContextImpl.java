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
import java.util.Objects;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.codec.http.HttpProtocolStateException;

/**
 * Default implementation of {@link Http2Context}.
 * <p>
 * The underlying state is provided by {@link Http2DecoderContent} and is installed into the root
 * context during HTTP/2 decoder initialization before being exposed through the current
 * {@link net.hasor.neta.channel.ProtoContext}. This object does not own protocol state by itself;
 * it only exposes a live read-only view of the same HTTP/2 connection.
 * Bridge code that must promote an already-consumed h2c upgrade request into HTTP/2 connection
 * state should depend on this implementation directly, rather than expanding the read-only
 * {@link Http2Context} interface.
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2026-02-20
 */
public class Http2ContextImpl implements Http2Context {
    private final boolean             serverMode;
    private final Http2DecoderContent state;

    public Http2ContextImpl(boolean serverMode, Http2DecoderContent state) {
        this.serverMode = serverMode;
        this.state = state;
    }

    /**
     * Ensures that the shared HTTP/2 connection state is installed on the given context.
     */
    public static Http2ContextImpl ensureInitialized(ProtoContext context, boolean serverMode, Http2Settings localSettings) {
        Objects.requireNonNull(context, "context is null");
        Http2Settings settings = localSettings != null ? new Http2Settings(localSettings) : Http2Settings.defaultLocalSettings(serverMode);

        Http2DecoderContent state = context.rootContext(Http2DecoderContent.class);
        if (state == null) {
            state = new Http2DecoderContent(serverMode, settings);
            Http2DecoderContent shared = context.rootContext(Http2DecoderContent.class, state);
            if (shared != null) {
                state = shared;
            }
        }

        if (context.context(Http2DecoderContent.class) == null) {
            context.context(Http2DecoderContent.class, state);
        }

        Http2Context existing = context.rootContext(Http2Context.class);
        Http2ContextImpl impl = existing instanceof Http2ContextImpl ? (Http2ContextImpl) existing : null;
        if (impl == null) {
            impl = new Http2ContextImpl(serverMode, state);
            Http2Context shared = context.rootContext(Http2Context.class, impl);
            if (shared instanceof Http2ContextImpl) {
                impl = (Http2ContextImpl) shared;
            }
        }

        if (context.context(Http2Context.class) == null) {
            context.context(Http2Context.class, impl);
        }
        return impl;
    }

    /**
     * Returns the current HTTP/2 context implementation or throws if it has not been initialized.
     */
    public static Http2ContextImpl require(ProtoContext context) {
        Objects.requireNonNull(context, "context is null");
        Http2Context h2Context = context.context(Http2Context.class);
        if (h2Context == null) {
            throw new HttpProtocolStateException("HTTP/2: connection context is not initialized");
        }
        if (!(h2Context instanceof Http2ContextImpl)) {
            throw new HttpProtocolStateException("HTTP/2: unsupported Http2Context implementation " + h2Context.getClass().getName());
        }
        return (Http2ContextImpl) h2Context;
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

    public void applyH2cUpgradeSettings(byte[] settingsPayload) {
        if (settingsPayload == null) {
            return;
        }
        for (int i = 0; i < settingsPayload.length; i += 6) {
            int id = ((settingsPayload[i] & 0xFF) << 8) | (settingsPayload[i + 1] & 0xFF);
            long value = ((settingsPayload[i + 2] & 0xFFL) << 24) | ((settingsPayload[i + 3] & 0xFFL) << 16) | ((settingsPayload[i + 4] & 0xFFL) << 8) | (settingsPayload[i + 5] & 0xFFL);
            this.state.applyRemoteSetting(id, value);
        }
    }

    public void openH2cUpgradeStream(long streamId) {
        Http2Stream stream = this.state.getOrCreateStream(streamId);
        stream.state(Http2StreamState.HALF_CLOSED_REMOTE);
        this.state.offerResponseStreamId(streamId);
    }

    public void markH2cUpgradedRequestEmitted(long streamId) {
        this.state.setLastEmittedStreamId(streamId);
    }
}
