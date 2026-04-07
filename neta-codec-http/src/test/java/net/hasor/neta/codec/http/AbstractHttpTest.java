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
import net.hasor.neta.codec.http.websocket.WebSocketFrame;

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

    protected static String utf8(ByteBuf buffer) {
        return new String(bytes(buffer.retain()), StandardCharsets.UTF_8);
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

    protected static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
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

    protected static boolean containsEvent(Iterable<Class<?>> eventTypes, Class<?> targetType) {
        for (Class<?> eventType : eventTypes) {
            if (targetType.equals(eventType)) {
                return true;
            }
        }
        return false;
    }

    //

    protected static FullHttpRequest emptyFullRequestGet(HttpMethod method, String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri);
    }

    protected static FullHttpRequest postRequest(String uri, String bodyText) {
        byte[] data = bodyText.getBytes(StandardCharsets.UTF_8);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, uri, ByteBuf.wrap(data));
        request.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(data.length));
        request.setHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        return request;
    }

    protected static FullHttpResponse textResponse(int streamId, String bodyText) {
        byte[] data = bodyText.getBytes(StandardCharsets.UTF_8);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, ByteBuf.wrap(data));
        response.streamId(streamId);
        response.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(data.length));
        response.setHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        return response;
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

    protected static HttpHeaders headers(String... pairs) {
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        for (int i = 0; i < pairs.length; i += 2) {
            headers.addHeader(pairs[i], pairs[i + 1]);
        }
        return headers;
    }

    protected static List<HttpObject> castHttpObjects(List<?> messages) {
        List<HttpObject> result = new ArrayList<HttpObject>(messages.size());
        for (Object item : messages) {
            result.add((HttpObject) item);
        }
        return result;
    }

    protected static HttpByteBuf httpByteBuf(byte[] data) {
        return new DefaultHttpByteBuf(ByteBuf.wrap(data));
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

    protected <T extends Throwable> List<T> receiveAndIntError(VirtualPipe pipe, Object... messages) {
        if (messages != null && messages.length > 0) {
            pipe.channel().receiveData(messages);
        }
        return drainQueue(pipe.channelInboundErrors());
    }

    protected <T extends Throwable> List<T> receiveAndOutError(VirtualPipe pipe, Object... messages) {
        if (messages != null && messages.length > 0) {
            pipe.channel().receiveData(messages);
        }
        return drainQueue(pipe.channelOutboundErrors());
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

    protected <T extends Throwable> List<T> sendAndOutError(VirtualPipe pipe, Object... messages) throws Throwable {
        if (messages != null) {
            for (Object message : messages) {
                pipe.channel().sendData(message).get();
            }
        }
        return drainQueue(pipe.channelOutboundErrors());
    }

    protected <T extends Throwable> List<T> sendAndInError(VirtualPipe pipe, Object... messages) throws Throwable {
        if (messages != null) {
            for (Object message : messages) {
                pipe.channel().sendData(message).get();
            }
        }
        return drainQueue(pipe.clientInboundErrors());
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
        waitUntil(() -> !pipe.serverInbound().isEmpty() || !pipe.serverInboundErrors().isEmpty() || !pipe.clientOutboundErrors().isEmpty(), 200L);
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
        waitUntil(() -> !pipe.clientInbound().isEmpty() || !pipe.clientInboundErrors().isEmpty() || !pipe.serverOutboundErrors().isEmpty(), 200L);
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
        Queue<SoEvent> clientEvents = new ConcurrentLinkedQueue<>();
        Queue<SoEvent> serverEvents = new ConcurrentLinkedQueue<>();
        VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), attachEventCollector(clientInit, clientEvents), VrtSoConfig.asClient());
        VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), attachEventCollector(serverInit, serverEvents), VrtSoConfig.asServer());

        VrtTransfer transfer = new VrtTransfer(neta);
        VrtTransferHandler handler = transferHandler == null ? VrtTransfer.duplicate() : transferHandler;
        transfer.linkTo(client, server, handler);
        transfer.linkTo(server, client, handler);

        Queue<Object> clientInbound = new ConcurrentLinkedQueue<>();
        Queue<Object> serverInbound = new ConcurrentLinkedQueue<>();
        Queue<Throwable> clientInboundErrors = new ConcurrentLinkedQueue<>();
        Queue<Throwable> clientOutboundErrors = new ConcurrentLinkedQueue<>();
        Queue<Throwable> serverInboundErrors = new ConcurrentLinkedQueue<>();
        Queue<Throwable> serverOutboundErrors = new ConcurrentLinkedQueue<>();
        client.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> clientInbound.offer(d.getData()));
        server.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> serverInbound.offer(d.getData()));
        client.subscribe(d -> d.isInbound() && !d.isSuccess(), SubscribeMode.SYNC, d -> clientInboundErrors.offer(d.getError()));
        client.subscribe(d -> d.isOutbound() && !d.isSuccess(), SubscribeMode.SYNC, d -> clientOutboundErrors.offer(d.getError()));
        server.subscribe(d -> d.isInbound() && !d.isSuccess(), SubscribeMode.SYNC, d -> serverInboundErrors.offer(d.getError()));
        server.subscribe(d -> d.isOutbound() && !d.isSuccess(), SubscribeMode.SYNC, d -> serverOutboundErrors.offer(d.getError()));
        return new VirtualPipe(client, server, clientInbound, clientInboundErrors, clientOutboundErrors, clientEvents, serverInbound, serverInboundErrors, serverOutboundErrors, serverEvents);
    }

    protected VirtualPipe openVirtualPipe(NetManager neta, ProtoInitializer initializer, VrtSoConfig config) throws Throwable {
        Queue<SoEvent> channelEvents = new ConcurrentLinkedQueue<>();
        VrtChannel channel = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), attachEventCollector(initializer, channelEvents), config);
        Queue<Object> channelInbound = new ConcurrentLinkedQueue<>();
        Queue<Object> channelOutbound = new ConcurrentLinkedQueue<>();
        Queue<Throwable> channelInboundErrors = new ConcurrentLinkedQueue<>();
        Queue<Throwable> channelOutboundErrors = new ConcurrentLinkedQueue<>();
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
                channelOutbound.offer(snapshotOutbound(outbound));
            }
        });
        channel.subscribe(d -> d.isInbound() && !d.isSuccess(), SubscribeMode.SYNC, d -> channelInboundErrors.offer(d.getError()));
        channel.subscribe(d -> d.isOutbound() && !d.isSuccess(), SubscribeMode.SYNC, d -> channelOutboundErrors.offer(d.getError()));
        return new VirtualPipe(channel, channelInbound, channelOutbound, channelInboundErrors, channelOutboundErrors, channelEvents);
    }

    private Object snapshotOutbound(Object outbound) {
        if (outbound instanceof WebSocketFrame) {
            return snapshotWebSocketFrame((WebSocketFrame) outbound);
        }
        if (outbound instanceof FullHttpResponse) {
            return snapshotFullHttpResponse((FullHttpResponse) outbound);
        }
        if (outbound instanceof FullHttpRequest) {
            return snapshotFullHttpRequest((FullHttpRequest) outbound);
        }
        if (outbound instanceof HttpResponse) {
            return snapshotHttpResponse((HttpResponse) outbound);
        }
        if (outbound instanceof HttpRequest) {
            return snapshotHttpRequest((HttpRequest) outbound);
        }
        if (outbound instanceof LastHttpHeaders) {
            return snapshotLastHttpHeaders((LastHttpHeaders) outbound);
        }
        if (outbound instanceof HttpHeaders) {
            return snapshotHttpHeaders((HttpHeaders) outbound);
        }
        if (outbound instanceof LastHttpContent) {
            return snapshotLastHttpContent((LastHttpContent) outbound);
        }
        if (outbound instanceof HttpContent) {
            return snapshotHttpContent((HttpContent) outbound);
        }
        if (outbound instanceof HttpByteBuf) {
            return snapshotHttpByteBuf((HttpByteBuf) outbound);
        }
        return outbound;
    }

    private DefaultHttpRequest snapshotHttpRequest(HttpRequest request) {
        DefaultHttpRequest copy = new DefaultHttpRequest(request.protocolVersion(), request.method(), request.uri());
        return inheritHttpObjectState(copy, request);
    }

    private DefaultHttpResponse snapshotHttpResponse(HttpResponse response) {
        DefaultHttpResponse copy = new DefaultHttpResponse(response.protocolVersion(), response.status());
        return inheritHttpObjectState(copy, response);
    }

    private DefaultHttpHeaders snapshotHttpHeaders(HttpHeaders headers) {
        DefaultHttpHeaders copy = new DefaultHttpHeaders();
        copy.appendHeaders(headers);
        return inheritHttpObjectState(copy, headers);
    }

    private DefaultLastHttpHeaders snapshotLastHttpHeaders(LastHttpHeaders headers) {
        DefaultLastHttpHeaders copy = new DefaultLastHttpHeaders(headers);
        return inheritHttpObjectState(copy, headers);
    }

    private DefaultHttpContent snapshotHttpContent(HttpContent content) {
        DefaultHttpContent copy = new DefaultHttpContent(retainContent(content.content()));
        return inheritHttpObjectState(copy, content);
    }

    private DefaultLastHttpContent snapshotLastHttpContent(LastHttpContent content) {
        DefaultLastHttpContent copy = new DefaultLastHttpContent(retainContent(content.content()));
        return inheritHttpObjectState(copy, content);
    }

    private DefaultHttpByteBuf snapshotHttpByteBuf(HttpByteBuf content) {
        DefaultHttpByteBuf copy = new DefaultHttpByteBuf(retainContent(content.content()));
        return inheritHttpObjectState(copy, content);
    }

    private WebSocketFrame snapshotWebSocketFrame(WebSocketFrame frame) {
        ByteBuf content = frame.content();
        byte[] maskKey = frame.maskingKey();
        byte[] maskCopy = null;
        if (maskKey != null && maskKey.length > 0) {
            maskCopy = new byte[maskKey.length];
            System.arraycopy(maskKey, 0, maskCopy, 0, maskKey.length);
        }

        WebSocketFrame copy = WebSocketFrame.create(frame.opcode(), frame.isFinalFragment(), frame.isRsv1(), frame.isRsv2(), frame.isRsv3(), frame.isMasked(), maskCopy, retainContent(content), frame.payloadLength());
        return inheritHttpObjectState(copy, frame);
    }

    private DefaultFullHttpRequest snapshotFullHttpRequest(FullHttpRequest request) {
        DefaultFullHttpRequest copy = new DefaultFullHttpRequest(request.protocolVersion(), request.method(), request.uri(), retainContent(request.content()), new DefaultHttpHeaders(), new DefaultLastHttpHeaders());
        copy.appendHeaders(request);
        return inheritHttpObjectState(copy, request);
    }

    private DefaultFullHttpResponse snapshotFullHttpResponse(FullHttpResponse response) {
        DefaultFullHttpResponse copy = new DefaultFullHttpResponse(response.protocolVersion(), response.status(), retainContent(response.content()), new DefaultHttpHeaders(), new DefaultLastHttpHeaders());
        copy.appendHeaders(response);
        return inheritHttpObjectState(copy, response);
    }

    private ByteBuf retainContent(ByteBuf content) {
        return content == null ? ByteBuf.EMPTY : content.retain();
    }

    private <T extends HttpObject> T inheritHttpObjectState(T target, HttpObject source) {
        target.streamId(source.streamId());
        if (source.isBad()) {
            target.markBad(source.badReason());
        }
        return target;
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
        private final VrtChannel       channel;
        private final Queue<Object>    channelInbound;
        private final Queue<Object>    channelOutbound;
        private final Queue<Throwable> channelInboundErrors;
        private final Queue<Throwable> channelOutboundErrors;
        private final Queue<SoEvent>   channelEvents;
        //
        private final VrtChannel       client;
        private final Queue<Object>    clientInbound;
        private final Queue<Throwable> clientInboundErrors;
        private final Queue<Throwable> clientOutboundErrors;
        private final Queue<SoEvent>   clientEvents;
        private final VrtChannel       server;
        private final Queue<Object>    serverInbound;
        private final Queue<Throwable> serverInboundErrors;
        private final Queue<Throwable> serverOutboundErrors;
        private final Queue<SoEvent>   serverEvents;

        private VirtualPipe(VrtChannel channel, Queue<Object> channelInbound, Queue<Object> channelOutbound, Queue<Throwable> channelInboundErrors, Queue<Throwable> channelOutboundErrors, Queue<SoEvent> channelEvents) {
            this.channel = channel;
            this.channelInbound = channelInbound;
            this.channelOutbound = channelOutbound;
            this.channelInboundErrors = channelInboundErrors;
            this.channelOutboundErrors = channelOutboundErrors;
            this.channelEvents = channelEvents;
            this.client = null;
            this.server = null;
            this.clientInbound = null;
            this.serverInbound = null;
            this.clientInboundErrors = null;
            this.clientOutboundErrors = null;
            this.serverInboundErrors = null;
            this.serverOutboundErrors = null;
            this.clientEvents = null;
            this.serverEvents = null;
        }

        private VirtualPipe(VrtChannel client, VrtChannel server, Queue<Object> clientInbound, Queue<Throwable> clientInboundErrors, Queue<Throwable> clientOutboundErrors, Queue<SoEvent> clientEvents, Queue<Object> serverInbound, Queue<Throwable> serverInboundErrors, Queue<Throwable> serverOutboundErrors, Queue<SoEvent> serverEvents) {
            this.channel = null;
            this.channelInbound = null;
            this.channelOutbound = null;
            this.channelInboundErrors = null;
            this.channelOutboundErrors = null;
            this.channelEvents = null;
            this.client = client;
            this.server = server;
            this.clientInbound = clientInbound;
            this.clientInboundErrors = clientInboundErrors;
            this.clientOutboundErrors = clientOutboundErrors;
            this.clientEvents = clientEvents;
            this.serverInbound = serverInbound;
            this.serverInboundErrors = serverInboundErrors;
            this.serverOutboundErrors = serverOutboundErrors;
            this.serverEvents = serverEvents;
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

        public Queue<Throwable> clientInboundErrors() {
            return this.clientInboundErrors;
        }

        public Queue<Throwable> clientOutboundErrors() {
            return this.clientOutboundErrors;
        }

        public Queue<SoEvent> clientEvents() {
            return this.clientEvents;
        }

        public Queue<Object> serverInbound() {
            return this.serverInbound;
        }

        public Queue<Throwable> serverInboundErrors() {
            return this.serverInboundErrors;
        }

        public Queue<Throwable> serverOutboundErrors() {
            return this.serverOutboundErrors;
        }

        public Queue<SoEvent> serverEvents() {
            return this.serverEvents;
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

        public Queue<Throwable> channelInboundErrors() {
            return this.channelInboundErrors;
        }

        public Queue<Throwable> channelOutboundErrors() {
            return this.channelOutboundErrors;
        }

        public Queue<SoEvent> channelEvents() {
            return this.channelEvents;
        }
    }

    protected static final class EventCollectDuplexer implements ProtoDuplexer<Object, Object, Object, Object> {
        private final Queue<SoEvent> events;

        private EventCollectDuplexer(Queue<SoEvent> events) {
            this.events = events;
        }

        @Override
        public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) {
            this.events.offer(event);
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

    private static ProtoInitializer attachEventCollector(ProtoInitializer initializer, Queue<SoEvent> events) {
        return ctx -> {
            if (initializer != null) {
                initializer.config(ctx);
            }
            ctx.addLast("__test-user-event-collector__", new EventCollectDuplexer(events));
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