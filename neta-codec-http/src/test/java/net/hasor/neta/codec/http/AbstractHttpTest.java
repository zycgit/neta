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
package net.hasor.neta.codec.http;
import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import net.hasor.cobble.function.EConsumer;
import net.hasor.cobble.function.Release;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.ref.Tuple;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.virtual.*;

public class AbstractHttpTest {
    protected static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    protected static ByteBuf ascii(String value) {
        return ByteBuf.wrap(value.getBytes(StandardCharsets.US_ASCII));
    }

    protected static String body(HttpContent content) {
        return content == null || content.content() == null ? null : text(content.content());
    }

    protected static String text(List<ByteBuf> buffer) {
        return text(buffer.toArray(new ByteBuf[0]));
    }

    protected static String text(ByteBuf... buffer) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        try {
            for (ByteBuf b : buffer) {
                if (b == null) {
                    continue;
                }
                buf.writeBuffer(b);
                b.free();
            }
            buf.markWriter();

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes, 0, bytes.length);
            return new String(bytes, StandardCharsets.US_ASCII);
        } finally {
            buf.free();
        }
    }

    protected static byte[] bytes(ByteBuf... buffer) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        try {
            for (ByteBuf b : buffer) {
                if (b == null) {
                    continue;
                }
                buf.writeBuffer(b);
                b.free();
            }
            buf.markWriter();

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes, 0, bytes.length);
            return bytes;
        } finally {
            buf.free();
        }
    }

    protected static void free(Iterable<?> messages) {
        if (messages == null) {
            return;
        }
        for (Object message : messages) {
            if (message instanceof Closeable) {
                IOUtils.closeQuietly((Closeable) message);
            } else if (message instanceof ByteBuf) {
                ((ByteBuf) message).release();
            } else if (message instanceof Release) {
                ((Release) message).release();
            }
        }
    }

    protected static HttpByteBuf httpByteBuf(byte[] data) {
        return new DefaultHttpByteBuf(ByteBuf.wrap(data));
    }

    protected static DefaultFullHttpRequest emptyFullRequestGet(HttpMethod method, String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri);
    }

    protected static HttpHeaders joinHeaders(Class<?> type, Tuple... header) {
        DefaultHttpHeaders headers;
        if (type.isAssignableFrom(DefaultLastHttpHeaders.class)) {
            headers = new DefaultLastHttpHeaders();
        } else if (type.isAssignableFrom(DefaultHttpHeaders.class)) {
            headers = new DefaultHttpHeaders();
        } else if (type.isAssignableFrom(DefaultTrailerHttpHeaders.class)) {
            headers = new DefaultTrailerHttpHeaders();
        } else {
            throw new IllegalArgumentException("type must be DefaultHttpHeaders or DefaultLastHttpHeaders or DefaultTrailerHttpHeaders");
        }

        for (Tuple part : header) {
            headers.addHeader(part.getArg0(), part.getArg1());
        }
        return headers;
    }

    //

    protected <T> List<T> receiveAndOutBound(VirtualPipe pipe, Object... messages) {
        if (messages != null && messages.length > 0) {
            pipe.channel().receiveData(messages);
        }
        return drainQueue(pipe.channelOutbound());
    }

    protected <T> List<T> receiveAndIntBound(VirtualPipe pipe, Object... messages) {
        if (messages != null && messages.length > 0) {
            pipe.channel().receiveData(messages);
        }
        return drainQueue(pipe.channelInbound());
    }

    protected <T> List<T> sendAndOutBound(VirtualPipe pipe, Object... messages) throws Throwable {
        if (messages != null) {
            for (Object message : messages) {
                pipe.channel().sendData(message).get();
            }
        }
        return drainQueue(pipe.channelOutbound());
    }

    protected <T> List<T> sendAndInBound(VirtualPipe pipe, Object... messages) throws Throwable {
        if (messages != null) {
            for (Object message : messages) {
                pipe.channel().sendData(message).get();
            }
        }
        return drainQueue(pipe.clientInbound());
    }

    //

    protected List<HttpObject> sendAndReceiveRequest(VirtualPipe pipe, String... fragments) throws Throwable {
        for (String fragment : fragments) {
            pipe.client().sendData(ascii(fragment)).get();
        }
        List<HttpObject> result = new ArrayList<>();
        for (Object item : drainQueue(pipe.serverInbound())) {
            result.add((HttpObject) item);
        }
        return result;
    }

    protected List<HttpObject> sendAndReceiveRequest(VirtualPipe pipe, ByteBuf... fragments) throws Throwable {
        for (ByteBuf fragment : fragments) {
            pipe.client().sendData(fragment).get();
        }
        List<HttpObject> result = new ArrayList<>();
        for (Object item : drainQueue(pipe.serverInbound())) {
            result.add((HttpObject) item);
        }
        return result;
    }

    //

    // client send and return server rcv.
    protected List<ByteBuf> sendAndReceiveRequest(VirtualPipe pipe, HttpObject... messages) throws Throwable {
        for (HttpObject message : messages) {
            pipe.client().sendData(message).get();
        }
        List<ByteBuf> result = new ArrayList<>();
        for (Object item : drainQueue(pipe.serverInbound())) {
            result.add((ByteBuf) item);
        }
        return result;
    }

    // client send object and return server rcv object.
    protected List<HttpObject> clientSendRequestObjects(VirtualPipe pipe, HttpObject... messages) throws Throwable {
        for (HttpObject message : messages) {
            pipe.client().sendData(message).get();
        }
        List<HttpObject> result = new ArrayList<>();
        for (Object item : drainQueue(pipe.serverInbound())) {
            result.add((HttpObject) item);
        }
        return result;
    }

    // server send.
    protected List<HttpObject> serverSendResponse(VirtualPipe pipe, String... fragments) throws Throwable {
        for (String fragment : fragments) {
            pipe.server().sendData(ascii(fragment)).get();
        }
        return drainQueue(pipe.clientInbound());
    }

    // server send.
    protected List<ByteBuf> serverSendResponse(VirtualPipe pipe, HttpObject... messages) throws Throwable {
        for (HttpObject message : messages) {
            pipe.server().sendData(message).get();
        }
        List<ByteBuf> result = new ArrayList<>();
        for (Object item : drainQueue(pipe.clientInbound())) {
            result.add((ByteBuf) item);
        }
        return result;
    }

    // server send object and return client rcv object.
    protected List<HttpObject> serverSendResponseObjects(VirtualPipe pipe, HttpObject... messages) throws Throwable {
        for (HttpObject message : messages) {
            pipe.server().sendData(message).get();
        }
        List<HttpObject> result = new ArrayList<>();
        for (Object item : drainQueue(pipe.clientInbound())) {
            result.add((HttpObject) item);
        }
        return result;
    }

    //

    protected void autoCloseNeta(EConsumer<NetManager, Throwable> consumer) throws Throwable {
        NetManager neta = new NetManager();
        try {
            consumer.eAccept(neta);
        } finally {
            neta.shutdown();
        }
    }

    protected VirtualPipe openVirtualPipe(NetManager neta, ProtoInitializer clientInit, ProtoInitializer serverInit) throws Throwable {
        return openVirtualPipe(neta, clientInit, serverInit, VrtTransfer.duplicate());
    }

    protected VirtualPipe openVirtualPipe(NetManager neta, ProtoInitializer clientInit, ProtoInitializer serverInit, VrtTransferHandler transferHandler) throws Throwable {
        Queue<SoUserEvent> clientUserEvents = new ConcurrentLinkedQueue<>();
        Queue<SoUserEvent> serverUserEvents = new ConcurrentLinkedQueue<>();
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), attachUserEventCollector(clientInit, clientUserEvents), VrtSoConfig.asClient());
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), attachUserEventCollector(serverInit, serverUserEvents), VrtSoConfig.asServer());

        VrtTransfer transfer = new VrtTransfer(neta);
        VrtTransferHandler handler = transferHandler == null ? VrtTransfer.duplicate() : transferHandler;
        transfer.linkTo(client, server, handler);
        transfer.linkTo(server, client, handler);

        Queue<Object> clientInbound = new ConcurrentLinkedQueue<>();
        Queue<Object> serverInbound = new ConcurrentLinkedQueue<>();
        client.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> clientInbound.offer(d.getData()));
        server.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> serverInbound.offer(d.getData()));
        return new VirtualPipe(client, server, clientInbound, clientUserEvents, serverInbound, serverUserEvents);
    }

    protected VirtualPipe openVirtualPipe(NetManager neta, ProtoInitializer initializer, VrtSoConfig config) throws Throwable {
        Queue<SoUserEvent> channelUserEvents = new ConcurrentLinkedQueue<>();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), attachUserEventCollector(initializer, channelUserEvents), config);
        Queue<Object> channelInbound = new ConcurrentLinkedQueue<>();
        Queue<Object> channelOutbound = new ConcurrentLinkedQueue<>();
        channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> {
            if (d.getData() == null) {
                return;
            }
            channelInbound.offer(d.getData());
        });
        channel.subscribe(PlayLoad::isOutbound, SubscribeMode.SYNC, d -> {
            Object outbound = d.getData();
            if (d.getData() == null) {
                return;
            }
            if (outbound instanceof ByteBuf) {
                channelOutbound.offer(((ByteBuf) outbound).retain());
            } else {
                channelOutbound.offer(outbound);
            }
        });
        return new VirtualPipe(channel, channelInbound, channelOutbound, channelUserEvents);
    }

    //

    protected static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }

    protected <T> List<T> drainQueue(Queue<?> queue) {
        List<T> result = new ArrayList<>(queue.size());
        while (!queue.isEmpty()) {
            result.add((T) queue.poll());
        }
        return result;
    }

    protected static final class VirtualPipe {
        private final VrtChannel         channel;
        private final Queue<Object>      channelInbound;
        private final Queue<Object>      channelOutbound;
        private final Queue<SoUserEvent> channelUserEvents;
        //
        private final VrtChannel         client;
        private final Queue<Object>      clientInbound;
        private final Queue<SoUserEvent> clientUserEvents;
        private final VrtChannel         server;
        private final Queue<Object>      serverInbound;
        private final Queue<SoUserEvent> serverUserEvents;

        private VirtualPipe(VrtChannel channel, Queue<Object> channelInbound, Queue<Object> channelOutbound, Queue<SoUserEvent> channelUserEvents) {
            this.channel = channel;
            this.channelInbound = channelInbound;
            this.channelOutbound = channelOutbound;
            this.channelUserEvents = channelUserEvents;
            this.client = null;
            this.server = null;
            this.clientInbound = null;
            this.serverInbound = null;
            this.clientUserEvents = null;
            this.serverUserEvents = null;
        }

        private VirtualPipe(VrtChannel client, VrtChannel server, Queue<Object> clientInbound, Queue<SoUserEvent> clientUserEvents, Queue<Object> serverInbound, Queue<SoUserEvent> serverUserEvents) {
            this.channel = null;
            this.channelInbound = null;
            this.channelOutbound = null;
            this.channelUserEvents = null;
            this.client = client;
            this.server = server;
            this.clientInbound = clientInbound;
            this.clientUserEvents = clientUserEvents;
            this.serverInbound = serverInbound;
            this.serverUserEvents = serverUserEvents;
        }

        public VrtChannel client() {
            return this.client;
        }

        public VrtChannel server() {
            return this.server;
        }

        public Queue<Object> clientInbound() {
            return this.clientInbound;
        }

        public Queue<SoUserEvent> clientUserEvents() {
            return this.clientUserEvents;
        }

        public Queue<Object> serverInbound() {
            return this.serverInbound;
        }

        public Queue<SoUserEvent> serverUserEvents() {
            return this.serverUserEvents;
        }

        //

        public VrtChannel channel() {
            return this.channel;
        }

        public Queue<Object> channelInbound() {
            return this.channelInbound;
        }

        public Queue<Object> channelOutbound() {
            return this.channelOutbound;
        }

        public Queue<SoUserEvent> channelUserEvents() {
            return this.channelUserEvents;
        }
    }

    protected static final class UserEventCollectDuplexer implements ProtoDuplexer<Object, Object, Object, Object> {
        private final Queue<SoUserEvent> userEvents;

        private UserEventCollectDuplexer(Queue<SoUserEvent> userEvents) {
            this.userEvents = userEvents;
        }

        @Override
        public boolean onUserEvent(ProtoContext context, SoUserEvent event, boolean isRcv) {
            this.userEvents.offer(event);
            return true;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, boolean isRcv, ProtoRcvQueue<Object> rcvUp, ProtoSndQueue<Object> rcvDown, ProtoRcvQueue<Object> sndUp, ProtoSndQueue<Object> sndDown) {
            if (isRcv) {
                while (rcvUp.hasMore()) {
                    rcvDown.offerMessage(rcvUp.takeMessage());
                }
            } else {
                while (sndUp.hasMore()) {
                    sndDown.offerMessage(sndUp.takeMessage());
                }
            }
            return ProtoStatus.Next;
        }
    }

    private static ProtoInitializer attachUserEventCollector(ProtoInitializer initializer, Queue<SoUserEvent> userEvents) {
        return ctx -> {
            if (initializer != null) {
                initializer.config(ctx);
            }
            ctx.addLast("__test-user-event-collector__", new UserEventCollectDuplexer(userEvents));
        };
    }

    protected abstract static class ThroughProtoHandler<T> implements ProtoHandler<T, T> {
        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<T> src, ProtoSndQueue<T> dst) throws Throwable {
            while (src.hasMore()) {
                dst.offerMessage(src.takeMessage());
            }
            return ProtoStatus.Next;
        }
    }
}