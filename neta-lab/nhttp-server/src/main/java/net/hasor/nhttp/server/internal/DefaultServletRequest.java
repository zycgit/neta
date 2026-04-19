/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.nhttp.server.internal;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.codec.http.DefaultLastHttpContent;
import net.hasor.neta.codec.http.FullHttpRequest;
import net.hasor.nhttp.server.SessionManager;
import net.hasor.nhttp.server.connector.BackpressureStrategy;

/**
 * Compatibility wrapper for tests and legacy callers that still provide a fully
 * aggregated {@link FullHttpRequest}. Internally it adapts the request into the
 * streaming request model used by {@link StreamingServletRequest}.
 */
@Deprecated
public class DefaultServletRequest extends StreamingServletRequest {
    private final InternalBodyChannel bodyChannel;

    public DefaultServletRequest(FullHttpRequest httpRequest, NetChannel channel, boolean secure, SessionManager sessionManager) {
        this(httpRequest, channel, secure, sessionManager, 30_000L);
    }

    public DefaultServletRequest(FullHttpRequest httpRequest, NetChannel channel, boolean secure, SessionManager sessionManager, long chunkReadTimeoutMillis) {
        this(httpRequest, channel, secure, sessionManager, chunkReadTimeoutMillis, createBodyChannel(httpRequest, channel));
    }

    private DefaultServletRequest(FullHttpRequest httpRequest, NetChannel channel, boolean secure, SessionManager sessionManager, long chunkReadTimeoutMillis, InternalBodyChannel bodyChannel) {
        super(httpRequest, httpRequest, bodyChannel, channel, secure, sessionManager, chunkReadTimeoutMillis);
        this.bodyChannel = bodyChannel;
    }

    private static InternalBodyChannel createBodyChannel(FullHttpRequest httpRequest, NetChannel channel) {
        InternalBodyChannel bodyChannel = new InternalBodyChannel(1, BackpressureStrategy.FAST_FAIL, channel);
        ByteBuf body = httpRequest.content();
        bodyChannel.offer(new DefaultLastHttpContent(body != null ? body.copy() : ByteBuf.EMPTY));
        return bodyChannel;
    }

    public void release() {
        super.release();
    }
}
