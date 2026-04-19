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
package net.hasor.nhttp.server.connector;

import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/**
 * Outermost pipeline handler for TCP connection lifecycle events.
 *
 * <p>Placed at the top of the pipeline (before any protocol codec or routing node)
 * so that {@link #onActive} and {@link #onClose} fire exactly once per TCP connection,
 * regardless of whether HTTP/1.1, HTTP/2, or HTTP/3 is in use. This gives accurate
 * connection counts for {@code maxConnections} enforcement and metrics.</p>
 *
 * <p>{@link #onMessage} is a transparent pass-through that forwards all raw bytes
 * downstream unchanged.</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
class ConnectionLifecycleHandler implements ProtoHandler<ByteBuf, ByteBuf> {
    private static final Logger logger = Logger.getLogger(ConnectionLifecycleHandler.class);

    private final RequestDispatchCallback callback;

    ConnectionLifecycleHandler(RequestDispatchCallback callback) {
        this.callback = callback;
    }

    @Override
    public void onActive(ProtoContext ctx) {
        NetChannel channel = (NetChannel) ctx.getChannel();
        try {
            this.callback.onConnectionOpen(channel);
        } catch (Throwable e) {
            logger.warn("RequestDispatchCallback.onConnectionOpen() threw an exception", e);
        }
    }

    @Override
    public void onClose(ProtoContext ctx) {
        NetChannel channel = (NetChannel) ctx.getChannel();
        try {
            this.callback.onConnectionClose(channel);
        } catch (Throwable e) {
            logger.warn("RequestDispatchCallback.onConnectionClose() threw an exception", e);
        }
    }

    /** Transparent pass-through: forwards all raw bytes downstream unchanged. */
    @Override
    public ProtoStatus onMessage(ProtoContext ctx, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) {
        while (src.hasMore()) {
            dst.offerMessage(src.takeMessage());
        }
        return ProtoStatus.Next;
    }
}
