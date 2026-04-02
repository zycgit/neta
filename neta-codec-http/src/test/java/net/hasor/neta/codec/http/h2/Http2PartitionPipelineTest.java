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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class Http2PartitionPipelineTest extends AbstractHttp2Test {
    private static final int MAX_CONTENT_LENGTH = 1048576;

    private static class PendingRequest {
        private final ProtoContext context;
        private final int          streamId;
        private final String       uri;
        private final String       body;

        private PendingRequest(ProtoContext context, FullHttpRequest request) {
            this.context = context;
            this.streamId = request.streamId();
            this.uri = request.uri();
            this.body = utf8(request.content());
        }

        private void sendResponse(String prefix) throws Throwable {
            this.context.sendData(textResponse(this.streamId, prefix + this.uri + ":" + this.body)).get();
        }
    }

    private static class EchoRequestHandler implements ProtoHandler<HttpObject, Object> {
        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Object> dst) throws Throwable {
            while (src.hasMore()) {
                HttpObject item = src.takeMessage();
                if (!(item instanceof FullHttpRequest)) {
                    continue;
                }
                FullHttpRequest request = (FullHttpRequest) item;
                try {
                    new PendingRequest(context, request).sendResponse("echo:");
                } finally {
                    request.release();
                }
            }
            return ProtoStatus.Next;
        }
    }

    private static class OutOfOrderResponseHandler implements ProtoHandler<HttpObject, Object> {
        private final AtomicReference<PendingRequest> slowRequest = new AtomicReference<>();

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Object> dst) throws Throwable {
            while (src.hasMore()) {
                HttpObject item = src.takeMessage();
                if (!(item instanceof FullHttpRequest)) {
                    continue;
                }

                FullHttpRequest request = (FullHttpRequest) item;
                try {
                    PendingRequest pending = new PendingRequest(context, request);
                    if ("/slow".equals(pending.uri)) {
                        this.slowRequest.set(pending);
                    } else {
                        pending.sendResponse("fast:");
                        PendingRequest slow = this.slowRequest.getAndSet(null);
                        if (slow != null) {
                            slow.sendResponse("slow:");
                        }
                    }
                } finally {
                    request.release();
                }
            }
            return ProtoStatus.Next;
        }
    }

    private static class HoldingRequestHandler implements ProtoHandler<HttpObject, Object> {
        private final Map<String, PendingRequest> pending = new LinkedHashMap<>();
        private final Queue<String>               seenUris;
        private final Queue<Class<?>>             seenEvents;

        private HoldingRequestHandler(Queue<String> seenUris) {
            this(seenUris, new ConcurrentLinkedQueue<Class<?>>());
        }

        private HoldingRequestHandler(Queue<String> seenUris, Queue<Class<?>> seenEvents) {
            this.seenUris = seenUris;
            this.seenEvents = seenEvents;
        }

        @Override
        public boolean onEvent(ProtoContext context, SoEvent event) {
            Object eventData = event != null ? event.getData() : null;
            if (eventData != null) {
                this.seenEvents.offer(eventData.getClass());
            }
            return true;
        }

        @Override
        public synchronized ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Object> dst) {
            while (src.hasMore()) {
                HttpObject item = src.takeMessage();
                if (!(item instanceof FullHttpRequest)) {
                    continue;
                }

                FullHttpRequest request = (FullHttpRequest) item;
                try {
                    PendingRequest pendingRequest = new PendingRequest(context, request);
                    this.seenUris.offer(pendingRequest.uri);
                    this.pending.put(pendingRequest.uri, pendingRequest);
                } finally {
                    request.release();
                }
            }
            return ProtoStatus.Next;
        }

        private synchronized PendingRequest get(String uri) {
            return this.pending.get(uri);
        }

        private synchronized int pendingSize() {
            return this.pending.size();
        }

        private Queue<Class<?>> seenEvents() {
            return this.seenEvents;
        }
    }

    private static DefaultFullHttpRequest fullRequest(String uri, String bodyText) {
        byte[] data = bodyText.getBytes(StandardCharsets.UTF_8);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, uri, ByteBuf.wrap(data));
        request.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(data.length));
        request.setHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        return request;
    }

    private static DefaultFullHttpResponse textResponse(int streamId, String bodyText) {
        byte[] data = bodyText.getBytes(StandardCharsets.UTF_8);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK, ByteBuf.wrap(data));
        response.streamId(streamId);
        response.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(data.length));
        response.setHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        return response;
    }

    @Test
    public void testFullStackRequestResponseClosesServerPartition() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoPartitionControl[] serverControl = new ProtoPartitionControl[1];
            VirtualPipe pipe = openVirtualPipe(neta, clientInitializer(), serverInitializer(serverControl, new EchoRequestHandler()));

            pipe.client().sendData(fullRequest("/echo", "hello")).get();

            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty(), 1000L));
            assertTrue(pipe.clientInboundErrors().isEmpty());
            assertTrue(pipe.serverInboundErrors().isEmpty());
            assertTrue(pipe.clientOutboundErrors().isEmpty());
            assertTrue(pipe.serverOutboundErrors().isEmpty());

            List<HttpObject> responses = castHttpObjects(drainQueue(pipe.clientInbound()));
            try {
                assertEquals(1, responses.size());
                assertTrue(responses.get(0) instanceof FullHttpResponse);

                FullHttpResponse response = (FullHttpResponse) responses.get(0);
                assertEquals(1, response.streamId());
                assertEquals(HttpStatus.OK, response.status());
                assertEquals("echo:/echo:hello", utf8(response.content()));
            } finally {
                free(responses);
            }

            assertTrue(waitUntil(() -> serverControl[0] != null && activeStreamPartitionCount(serverControl[0]) == 0, 1000L));
        });
    }

    @Test
    public void testConcurrentRequestsAllowOutOfOrderResponses() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoPartitionControl[] serverControl = new ProtoPartitionControl[1];
            VirtualPipe pipe = openVirtualPipe(neta, clientInitializer(), serverInitializer(serverControl, new OutOfOrderResponseHandler()));

            pipe.client().sendData(fullRequest("/slow", "alpha")).get();
            pipe.client().sendData(fullRequest("/fast", "beta")).get();

            assertTrue(waitUntil(() -> pipe.clientInbound().size() >= 2, 1000L));
            assertTrue(pipe.clientInboundErrors().isEmpty());
            assertTrue(pipe.serverInboundErrors().isEmpty());
            assertTrue(pipe.clientOutboundErrors().isEmpty());
            assertTrue(pipe.serverOutboundErrors().isEmpty());

            List<HttpObject> responses = castHttpObjects(drainQueue(pipe.clientInbound()));
            try {
                assertEquals(2, responses.size());
                Map<Integer, String> byStream = new LinkedHashMap<>();
                for (HttpObject item : responses) {
                    assertTrue(item instanceof FullHttpResponse);
                    FullHttpResponse response = (FullHttpResponse) item;
                    byStream.put(response.streamId(), utf8(response.content()));
                }

                assertEquals("slow:/slow:alpha", byStream.get(1));
                assertEquals("fast:/fast:beta", byStream.get(3));
            } finally {
                free(responses);
            }

            assertTrue(waitUntil(() -> serverControl[0] != null && activeStreamPartitionCount(serverControl[0]) == 0, 1000L));
        });
    }

    @Test
    public void testGoawayBlocksNewStreamsWhileActiveStreamCanComplete() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoPartitionControl[] serverControl = new ProtoPartitionControl[1];
            Queue<String> seenUris = new ConcurrentLinkedQueue<>();
            HoldingRequestHandler handler = new HoldingRequestHandler(seenUris);
            VirtualPipe pipe = openVirtualPipe(neta, clientInitializer(), serverInitializer(serverControl, handler));

            pipe.client().sendData(fullRequest("/hold", "one")).get();
            assertTrue(waitUntil(() -> handler.get("/hold") != null && serverControl[0] != null && activeStreamPartitionCount(serverControl[0]) == 1, 1000L));

            pipe.server().fireEvent(Http2GoawayEvent.class, new Http2GoawayEvent(0, 1, Http2ErrorCode.NO_ERROR, null));
            assertTrue(waitUntil(() -> findHttp2Event(pipe.clientEvents(), Http2GoawayEvent.class) != null, 1000L));

            pipe.client().sendData(fullRequest("/blocked", "two")).get();
            Thread.sleep(150L);

            assertNull(handler.get("/blocked"));
            assertEquals(1, seenUris.size());
            assertEquals("/hold", seenUris.peek());

            handler.get("/hold").sendResponse("hold:");
            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty(), 1000L));

            List<HttpObject> responses = castHttpObjects(drainQueue(pipe.clientInbound()));
            try {
                assertEquals(1, responses.size());
                FullHttpResponse response = (FullHttpResponse) responses.get(0);
                assertEquals(1, response.streamId());
                assertEquals("hold:/hold:one", utf8(response.content()));
            } finally {
                free(responses);
            }

            Http2GoawayEvent goawayEvent = findHttp2Event(pipe.clientEvents(), Http2GoawayEvent.class);
            assertNotNull(goawayEvent);
            assertTrue(goawayEvent.isRemote());
            assertEquals(1L, goawayEvent.lastAcceptedId());
            assertFalse(containsEvent(handler.seenEvents(), Http2GoawayEvent.class));

            assertTrue(waitUntil(() -> activeStreamPartitionCount(serverControl[0]) == 0, 1000L));
        });
    }

    @Test
    public void testPriorityEventDoesNotReachBusinessPartitionHandler() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoPartitionControl[] serverControl = new ProtoPartitionControl[1];
            Queue<String> seenUris = new ConcurrentLinkedQueue<>();
            Queue<Class<?>> seenEvents = new ConcurrentLinkedQueue<>();
            HoldingRequestHandler handler = new HoldingRequestHandler(seenUris, seenEvents);
            VirtualPipe pipe = openVirtualPipe(neta, clientInitializer(), serverInitializer(serverControl, handler));

            pipe.client().sendData(fullRequest("/priority", "one")).get();
            assertTrue(waitUntil(() -> handler.get("/priority") != null && serverControl[0] != null && activeStreamPartitionCount(serverControl[0]) == 1, 1000L));

            pipe.server().fireEvent(Http2PriorityEvent.class, new Http2PriorityEvent(1, 0, 16, false));
            assertTrue(waitUntil(() -> findHttp2Event(pipe.clientEvents(), Http2PriorityEvent.class) != null, 1000L));

            Http2PriorityEvent priorityEvent = findHttp2Event(pipe.clientEvents(), Http2PriorityEvent.class);
            assertNotNull(priorityEvent);
            assertTrue(priorityEvent.isRemote());
            assertEquals(1L, priorityEvent.streamId());
            assertFalse(containsEvent(handler.seenEvents(), Http2PriorityEvent.class));

            handler.get("/priority").sendResponse("priority:");
            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty(), 1000L));

            List<HttpObject> responses = castHttpObjects(drainQueue(pipe.clientInbound()));
            try {
                assertEquals(1, responses.size());
                FullHttpResponse response = (FullHttpResponse) responses.get(0);
                assertEquals(1, response.streamId());
                assertEquals("priority:/priority:one", utf8(response.content()));
            } finally {
                free(responses);
            }

            assertTrue(waitUntil(() -> activeStreamPartitionCount(serverControl[0]) == 0, 1000L));
        });
    }

    @Test
    public void testGoawayDoesNotCreateSyntheticPartition() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoPartitionControl[] serverControl = new ProtoPartitionControl[1];
            Queue<String> seenUris = new ConcurrentLinkedQueue<>();
            HoldingRequestHandler handler = new HoldingRequestHandler(seenUris);
            VirtualPipe pipe = openVirtualPipe(neta, clientInitializer(), serverInitializer(serverControl, handler));

            pipe.server().fireEvent(Http2GoawayEvent.class, new Http2GoawayEvent(0, 0, Http2ErrorCode.NO_ERROR, null));

            assertTrue(waitUntil(() -> serverControl[0] != null, 1000L));
            assertEquals(0, activeStreamPartitionCount(serverControl[0]));
            assertTrue(waitUntil(() -> findHttp2Event(pipe.clientEvents(), Http2GoawayEvent.class) != null, 1000L));

            pipe.client().sendData(fullRequest("/blocked", "two")).get();
            Thread.sleep(150L);

            assertNull(handler.get("/blocked"));
            assertTrue(seenUris.isEmpty());
            assertEquals(0, activeStreamPartitionCount(serverControl[0]));
            assertTrue(pipe.clientInbound().isEmpty());
            assertTrue(pipe.clientInboundErrors().isEmpty());
            assertTrue(pipe.serverInboundErrors().isEmpty());
        });
    }

    @Test
    public void testResetClosesOnlyAffectedStreamPartition() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoPartitionControl[] serverControl = new ProtoPartitionControl[1];
            Queue<String> seenUris = new ConcurrentLinkedQueue<>();
            HoldingRequestHandler handler = new HoldingRequestHandler(seenUris);
            VirtualPipe pipe = openVirtualPipe(neta, clientInitializer(), serverInitializer(serverControl, handler));

            pipe.client().sendData(fullRequest("/ok", "one")).get();
            pipe.client().sendData(fullRequest("/cancel", "two")).get();

            assertTrue(waitUntil(() -> handler.get("/ok") != null && handler.get("/cancel") != null && serverControl[0] != null && activeStreamPartitionCount(serverControl[0]) == 2, 1000L));

            PendingRequest okRequest = handler.get("/ok");
            PendingRequest cancelRequest = handler.get("/cancel");
            pipe.server().fireEvent(Http2ResetEvent.class, new Http2ResetEvent(cancelRequest.streamId, Http2ResetEvent.CANCEL));
            okRequest.sendResponse("ok:");

            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty() && findHttp2Event(pipe.clientEvents(), Http2ResetEvent.class) != null, 1000L));
            assertTrue(pipe.clientInboundErrors().isEmpty());
            assertTrue(pipe.serverInboundErrors().isEmpty());
            assertTrue(pipe.clientOutboundErrors().isEmpty());
            assertTrue(pipe.serverOutboundErrors().isEmpty());

            List<HttpObject> responses = castHttpObjects(drainQueue(pipe.clientInbound()));
            try {
                assertEquals(1, responses.size());
                FullHttpResponse response = (FullHttpResponse) responses.get(0);
                assertEquals(okRequest.streamId, response.streamId());
                assertEquals("ok:/ok:one", utf8(response.content()));
            } finally {
                free(responses);
            }

            Http2ResetEvent resetEvent = findHttp2Event(pipe.clientEvents(), Http2ResetEvent.class);
            assertNotNull(resetEvent);
            assertTrue(resetEvent.isRemote());
            assertEquals((long) cancelRequest.streamId, resetEvent.streamId());
            assertEquals(Http2ErrorCode.CANCEL, resetEvent.errorCode());
            assertFalse(containsEvent(handler.seenEvents(), Http2ResetEvent.class));

            assertTrue(waitUntil(() -> activeStreamPartitionCount(serverControl[0]) == 0, 1000L));
        });
    }

    @Test
    public void testResetWithoutExistingStreamDoesNotCreatePartition() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoPartitionControl[] serverControl = new ProtoPartitionControl[1];
            VirtualPipe pipe = openVirtualPipe(neta, clientInitializer(), serverInitializer(serverControl, new EchoRequestHandler()));

            pipe.server().fireEvent(Http2ResetEvent.class, new Http2ResetEvent(5, Http2ResetEvent.CANCEL));

            assertTrue(waitUntil(() -> serverControl[0] != null, 1000L));
            assertEquals(0, activeStreamPartitionCount(serverControl[0]));
            assertTrue(waitUntil(() -> findHttp2Event(pipe.clientEvents(), Http2ResetEvent.class) != null, 1000L));

            Http2ResetEvent resetEvent = findHttp2Event(pipe.clientEvents(), Http2ResetEvent.class);
            assertNotNull(resetEvent);
            assertTrue(resetEvent.isRemote());
            assertEquals(5L, resetEvent.streamId());
            assertEquals(Http2ErrorCode.CANCEL, resetEvent.errorCode());

            pipe.client().sendData(fullRequest("/ok", "one")).get();
            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty(), 1000L));

            List<HttpObject> responses = castHttpObjects(drainQueue(pipe.clientInbound()));
            try {
                assertEquals(1, responses.size());
                assertTrue(responses.get(0) instanceof FullHttpResponse);

                FullHttpResponse response = (FullHttpResponse) responses.get(0);
                assertEquals(1, response.streamId());
                assertEquals(HttpStatus.OK, response.status());
                assertEquals("echo:/ok:one", utf8(response.content()));
            } finally {
                free(responses);
            }

            assertTrue(waitUntil(() -> activeStreamPartitionCount(serverControl[0]) == 0, 1000L));
        });
    }

    private static ProtoInitializer clientInitializer() {
        return ctx -> ProtoHelper.standard()//
                .nextDuplex("h2-frame", new Http2FrameDuplexe(false))//
                .nextDuplex("h2-message", new Http2ObjectDuplexe(false))//
                .nextDuplex("h2-client-aggregator", new HttpClientDuplexeAggregator(MAX_CONTENT_LENGTH))//
                .build().config(ctx);
    }

    private static ProtoInitializer serverInitializer(ProtoPartitionControl[] controlRef, ProtoHandler<HttpObject, Object> handler) {
        return ctx -> ProtoHelper.standard()//
                .nextDuplex("h2-frame", new Http2FrameDuplexe(true))//
                .nextDuplex("h2-message", new Http2ObjectDuplexe(true))//
                .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), pb -> {
                    Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                    ProtoPartitionControl control = pb.control();
                    controlRef[0] = control;
                    pb.policy(policy).byInitializer(partitionCtx -> {
                        partitionCtx.addLast("h2-stream-lifecycle", new Http2ObjectLifecycleDuplexer(control, policy));
                        partitionCtx.addLast("h2-server-aggregator", new HttpServerDuplexeAggregator(MAX_CONTENT_LENGTH));
                        partitionCtx.addLastDecoder("h2-handler", handler);
                    }).byDefault(partitionCtx -> partitionCtx.addLast("h2-control-lifecycle", new Http2ObjectLifecycleDuplexer(control, policy)));
                })//
                .build().config(ctx);
    }

    private static String utf8(ByteBuf buffer) {
        return new String(bytes(buffer.retain()), StandardCharsets.UTF_8);
    }

    private static int activeStreamPartitionCount(ProtoPartitionControl control) {
        int count = 0;
        for (PartitionKey key : control.partitionKeys()) {
            if (key != null && !PartitionKey.defaultKey().equals(key)) {
                count++;
            }
        }
        return count;
    }

    private static boolean containsEvent(Queue<Class<?>> eventTypes, Class<?> targetType) {
        for (Class<?> eventType : eventTypes) {
            if (targetType.equals(eventType)) {
                return true;
            }
        }
        return false;
    }

    private static List<HttpObject> castHttpObjects(List<?> messages) {
        List<HttpObject> result = new java.util.ArrayList<HttpObject>(messages.size());
        for (Object item : messages) {
            result.add((HttpObject) item);
        }
        return result;
    }
}