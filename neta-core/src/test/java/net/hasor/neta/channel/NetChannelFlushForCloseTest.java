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
package net.hasor.neta.channel;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;

/**
 * Regression tests for {@link NetChannel#flushForClose()}.
 */
public class NetChannelFlushForCloseTest {
    @Test
    public void flushForClose_skipsFarewellWriteWhenTransportAlreadyClosed() throws Throwable {
        SoContextService context = WriteRetryTestHelper.createContextService();
        TestAsyncChannel asyncChannel = new TestAsyncChannel(false, false);
        NetChannel channel = null;
        try {
            channel = createChannel(context, asyncChannel);

            context.notifySndEvent(channel.getChannelId(), null, SoEventObject.of(channel, SoCloseEvent.class, SoCloseEvent.INSTANCE));
            channel.flushForClose();

            assert asyncChannel.writeCount.get() == 0 : "flushForClose must not write when transport is already closed";
            assert channel.wContext.isEmpty() : "flushForClose must not enqueue farewell data when transport is closed";
        } finally {
            if (channel != null) {
                channel.protoStack.onClose(channel.protoCtx);
            }
            context.shutdown();
        }
    }

    @Test
    public void flushForClose_purgesFarewellWriteWhenTransportClosesDuringWrite() throws Throwable {
        SoContextService context = WriteRetryTestHelper.createContextService();
        TestAsyncChannel asyncChannel = new TestAsyncChannel(true, true);
        NetChannel channel = null;
        try {
            channel = createChannel(context, asyncChannel);

            context.notifySndEvent(channel.getChannelId(), null, SoEventObject.of(channel, SoCloseEvent.class, SoCloseEvent.INSTANCE));
            channel.flushForClose();

            assert asyncChannel.writeCount.get() == 1 : "flushForClose should attempt the farewell write once";
            assert channel.wContext.isEmpty() : "failed farewell writes must be purged from the send queue";
        } finally {
            if (channel != null) {
                channel.protoStack.onClose(channel.protoCtx);
            }
            context.shutdown();
        }
    }

    private static NetChannel createChannel(SoContextService context, TestAsyncChannel asyncChannel) throws Throwable {
        AtomicBoolean pendingFarewell = new AtomicBoolean(false);
        ProtoDuplexer<ByteBuf, ByteBuf, ByteBuf, ByteBuf> handler = new ProtoDuplexer<ByteBuf, ByteBuf, ByteBuf, ByteBuf>() {
            @Override
            public boolean onEvent(ProtoContext protoContext, SoEvent event, boolean isRcv) {
                if (!isRcv && event.getData() instanceof SoCloseEvent) {
                    pendingFarewell.set(true);
                }
                return true;
            }

            @Override
            public ProtoStatus onMessage(ProtoContext protoContext, boolean isRcv, ProtoRcvQueue<ByteBuf> rcvUp, ProtoSndQueue<ByteBuf> rcvDown, ProtoRcvQueue<ByteBuf> sndUp, ProtoSndQueue<ByteBuf> sndDown) {
                if (isRcv) {
                    while (rcvUp.hasMore()) {
                        rcvDown.offerMessage(rcvUp.takeMessage());
                    }
                } else {
                    while (sndUp.hasMore()) {
                        sndDown.offerMessage(sndUp.takeMessage());
                    }
                    if (pendingFarewell.compareAndSet(true, false)) {
                        sndDown.offerMessage(ByteBuf.wrap(new byte[] { 1, 2, 3 }));
                    }
                }
                return ProtoStatus.Next;
            }

            @Override
            public ProtoStatus onError(ProtoContext protoContext, boolean isRcv, Throwable e, ProtoExceptionHolder eh) {
                return ProtoStatus.Next;
            }

            @Override
            public void onClose(ProtoContext protoContext) {
            }
        };

        NetChannel channel = new NetChannel(1001L, new NetMonitor(), null, ctx -> ctx.addLast("handler", handler), asyncChannel, context) {
        };
        asyncChannel.attach(channel);
        context.initChannel(channel, true);
        return channel;
    }

    private static class TestAsyncChannel implements AsyncChannel {
        private final AtomicBoolean open;
        private final boolean       closeOnWrite;
        private final AtomicInteger writeCount;
        private NetChannel          channel;

        private TestAsyncChannel(boolean open, boolean closeOnWrite) {
            this.open = new AtomicBoolean(open);
            this.closeOnWrite = closeOnWrite;
            this.writeCount = new AtomicInteger();
        }

        private void attach(NetChannel channel) {
            this.channel = channel;
        }

        @Override
        public long getChannelId() {
            return this.channel != null ? this.channel.getChannelId() : 0L;
        }

        @Override
        public SoConfig getSoConfig() {
            return SoConfig.TCP();
        }

        @Override
        public SocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public boolean isOpen() {
            return this.open.get();
        }

        @Override
        public void close() throws IOException {
            this.open.set(false);
        }

        @Override
        public void write(NetChannel channel, SoSndContext wContext) {
            this.writeCount.incrementAndGet();
            if (this.closeOnWrite) {
                this.open.set(false);
                throw new IllegalStateException(new ClosedChannelException());
            }
        }

        @Override
        public void connectTo(ProtoInitializer initializer, Future<NetChannel> future) {
        }
    }
}