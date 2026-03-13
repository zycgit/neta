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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import net.hasor.cobble.function.EConsumer;
import net.hasor.cobble.function.Release;
import net.hasor.cobble.io.IOUtils;
import net.hasor.cobble.ref.Tuple;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.PlayLoad;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SubscribeMode;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import net.hasor.neta.channel.virtual.VrtTransfer;

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
        return text(content.content());
    }

    protected static String text(List<ByteBuf> buffer) {
        return text(buffer.toArray(new ByteBuf[0]));
    }

    protected static String text(ByteBuf... buffer) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        try {
            for (ByteBuf b : buffer) {
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
    //
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

    protected void autoCloseNeta(EConsumer<NetManager, Throwable> consumer) throws Throwable {
        NetManager neta = new NetManager();
        try {
            consumer.eAccept(neta);
        } finally {
            neta.shutdown();
        }
    }

    protected VirtualPipe openVirtualPipe(NetManager neta, ProtoInitializer clientInit, ProtoInitializer serverInit) throws Throwable {
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), clientInit != null ? clientInit : ctx -> {
        }, VrtSoConfig.asClient());
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), serverInit != null ? serverInit : ctx -> {
        }, VrtSoConfig.asServer());

        VrtTransfer transfer = new VrtTransfer(neta);
        transfer.linkTo(client, server, VrtTransfer.duplicate());
        transfer.linkTo(server, client, VrtTransfer.duplicate());

        Queue<Object> clientInbound = new ArrayDeque<>();
        Queue<Object> serverInbound = new ArrayDeque<>();
        client.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> clientInbound.offer(d.getData()));
        server.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> serverInbound.offer(d.getData()));
        return new VirtualPipe(client, server, clientInbound, serverInbound);
    }

    protected VirtualPipe openVirtualPipe(NetManager neta, ProtoInitializer initializer, VrtSoConfig config) throws Throwable {
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), initializer, config);
        Queue<Object> channelInbound = new ArrayDeque<>();
        Queue<Object> channelOutbound = new ArrayDeque<>();
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
        return new VirtualPipe(channel, channelInbound, channelOutbound);
    }

    protected <T> List<T> drainQueue(Queue<Object> queue) {
        List<T> result = new ArrayList<>(queue.size());
        while (!queue.isEmpty()) {
            result.add((T) queue.poll());
        }
        return result;
    }

    protected static final class VirtualPipe {
        private final VrtChannel    channel;
        private final Queue<Object> channelInbound;
        private final Queue<Object> channelOutbound;
        //
        private final VrtChannel    client;
        private final Queue<Object> clientInbound;
        private final VrtChannel    server;
        private final Queue<Object> serverInbound;

        private VirtualPipe(VrtChannel channel, Queue<Object> channelInbound, Queue<Object> channelOutbound) {
            this.channel = channel;
            this.channelInbound = channelInbound;
            this.channelOutbound = channelOutbound;
            this.client = null;
            this.server = null;
            this.clientInbound = null;
            this.serverInbound = null;
        }

        private VirtualPipe(VrtChannel client, VrtChannel server, Queue<Object> clientInbound, Queue<Object> serverInbound) {
            this.channel = null;
            this.channelInbound = null;
            this.channelOutbound = null;
            this.client = client;
            this.server = server;
            this.clientInbound = clientInbound;
            this.serverInbound = serverInbound;
        }

        protected VrtChannel client() {
            return this.client;
        }

        protected VrtChannel server() {
            return this.server;
        }

        protected Queue<Object> clientInbound() {
            return this.clientInbound;
        }

        protected Queue<Object> serverInbound() {
            return this.serverInbound;
        }

        //

        protected VrtChannel channel() {
            return this.channel;
        }

        protected Queue<Object> channelInbound() {
            return this.channelInbound;
        }

        protected Queue<Object> channelOutbound() {
            return this.channelOutbound;
        }
    }
}