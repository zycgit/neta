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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.FullHttpRequest;
import net.hasor.neta.codec.http.FullHttpResponse;
import net.hasor.neta.codec.http.HttpObject;
import net.hasor.neta.codec.http.HttpStatus;
import org.junit.Test;
import static org.junit.Assert.*;

public class Http2LifecycleTest extends AbstractHttp2Test {
    private static class HoldingRequestHandler implements ProtoHandler<HttpObject, Object> {
        private final Map<String, ProtoContext> pendingContexts  = new LinkedHashMap<>();
        private final Map<String, Integer>      pendingStreamIds = new LinkedHashMap<>();
        private final Map<String, String>       pendingBodies    = new LinkedHashMap<>();
        private final Queue<String>             seenUris;
        private final Queue<Class<?>>           seenEvents;

        protected HoldingRequestHandler(Queue<String> seenUris) {
            this(seenUris, new ConcurrentLinkedQueue<Class<?>>());
        }

        protected HoldingRequestHandler(Queue<String> seenUris, Queue<Class<?>> seenEvents) {
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
                    String uri = request.uri();
                    this.seenUris.offer(uri);
                    this.pendingContexts.put(uri, context);
                    this.pendingStreamIds.put(uri, request.streamId());
                    this.pendingBodies.put(uri, utf8(request.content()));
                } finally {
                    request.release();
                }
            }

            return ProtoStatus.Next;
        }

        protected synchronized boolean contains(String uri) {
            return this.pendingContexts.containsKey(uri);
        }

        protected synchronized int streamId(String uri) {
            Integer streamId = this.pendingStreamIds.get(uri);
            return streamId != null ? streamId : -1;
        }

        protected synchronized void sendResponse(String uri, String prefix) throws Throwable {
            ProtoContext context = this.pendingContexts.remove(uri);
            Integer streamId = this.pendingStreamIds.remove(uri);
            String body = this.pendingBodies.remove(uri);
            if (context == null || streamId == null || body == null) {
                return;
            }

            AbstractHttp2Test.sendResponse(context, streamId, uri, body, prefix);
        }

        protected Queue<Class<?>> seenEvents() {
            return this.seenEvents;
        }
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

    @Test
    public void testGoawayBlocksNewStreamsWhileActiveStreamCanComplete() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoPartitionControl[] serverControl = new ProtoPartitionControl[1];
            Queue<String> seenUris = new ConcurrentLinkedQueue<>();
            HoldingRequestHandler handler = new HoldingRequestHandler(seenUris);
            VirtualPipe pipe = openHttpServer(neta, handler, serverControl);

            pipe.client().sendData(postRequest("/hold", "one")).get();
            assertTrue(waitUntil(() -> handler.contains("/hold") && serverControl[0] != null && activeStreamPartitionCount(serverControl[0]) == 1, 1000L));

            pipe.server().fireEvent(Http2GoawayEvent.class, new Http2GoawayEvent(0, 1, Http2ErrorCode.NO_ERROR, null));
            assertTrue(waitUntil(() -> findEvent(pipe.clientEvents(), Http2GoawayEvent.class) != null, 1000L));

            pipe.client().sendData(postRequest("/blocked", "two")).get();
            Thread.sleep(150L);

            assertFalse(handler.contains("/blocked"));
            assertEquals(1, seenUris.size());
            assertEquals("/hold", seenUris.peek());

            handler.sendResponse("/hold", "hold:");
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

            Http2GoawayEvent goawayEvent = findEvent(pipe.clientEvents(), Http2GoawayEvent.class);
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
            VirtualPipe pipe = openHttpServer(neta, handler, serverControl);

            pipe.client().sendData(postRequest("/priority", "one")).get();
            assertTrue(waitUntil(() -> handler.contains("/priority") && serverControl[0] != null && activeStreamPartitionCount(serverControl[0]) == 1, 1000L));

            pipe.server().fireEvent(Http2PriorityEvent.class, new Http2PriorityEvent(1, 0, 16, false));
            assertTrue(waitUntil(() -> findEvent(pipe.clientEvents(), Http2PriorityEvent.class) != null, 1000L));

            Http2PriorityEvent priorityEvent = findEvent(pipe.clientEvents(), Http2PriorityEvent.class);
            assertNotNull(priorityEvent);
            assertTrue(priorityEvent.isRemote());
            assertEquals(1L, priorityEvent.streamId());
            assertFalse(containsEvent(handler.seenEvents(), Http2PriorityEvent.class));

            handler.sendResponse("/priority", "priority:");
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
            VirtualPipe pipe = openHttpServer(neta, handler, serverControl);

            pipe.server().fireEvent(Http2GoawayEvent.class, new Http2GoawayEvent(0, 0, Http2ErrorCode.NO_ERROR, null));

            assertTrue(waitUntil(() -> serverControl[0] != null, 1000L));
            assertEquals(0, activeStreamPartitionCount(serverControl[0]));
            assertTrue(waitUntil(() -> findEvent(pipe.clientEvents(), Http2GoawayEvent.class) != null, 1000L));

            pipe.client().sendData(postRequest("/blocked", "two")).get();
            Thread.sleep(150L);

            assertFalse(handler.contains("/blocked"));
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
            VirtualPipe pipe = openHttpServer(neta, handler, serverControl);

            pipe.client().sendData(postRequest("/ok", "one")).get();
            pipe.client().sendData(postRequest("/cancel", "two")).get();

            assertTrue(waitUntil(() -> handler.contains("/ok") && handler.contains("/cancel") && serverControl[0] != null && activeStreamPartitionCount(serverControl[0]) == 2, 1000L));

            int okStreamId = handler.streamId("/ok");
            int cancelStreamId = handler.streamId("/cancel");
            pipe.server().fireEvent(Http2ResetEvent.class, new Http2ResetEvent(cancelStreamId, Http2ResetEvent.CANCEL));
            handler.sendResponse("/ok", "ok:");

            assertTrue(waitUntil(() -> !pipe.clientInbound().isEmpty() && findEvent(pipe.clientEvents(), Http2ResetEvent.class) != null, 1000L));
            assertTrue(pipe.clientInboundErrors().isEmpty());
            assertTrue(pipe.serverInboundErrors().isEmpty());
            assertTrue(pipe.clientOutboundErrors().isEmpty());
            assertTrue(pipe.serverOutboundErrors().isEmpty());

            List<HttpObject> responses = castHttpObjects(drainQueue(pipe.clientInbound()));
            try {
                assertEquals(1, responses.size());
                FullHttpResponse response = (FullHttpResponse) responses.get(0);
                assertEquals(okStreamId, response.streamId());
                assertEquals("ok:/ok:one", utf8(response.content()));
            } finally {
                free(responses);
            }

            Http2ResetEvent resetEvent = findEvent(pipe.clientEvents(), Http2ResetEvent.class);
            assertNotNull(resetEvent);
            assertTrue(resetEvent.isRemote());
            assertEquals(cancelStreamId, resetEvent.streamId());
            assertEquals(Http2ErrorCode.CANCEL, resetEvent.errorCode());
            assertFalse(containsEvent(handler.seenEvents(), Http2ResetEvent.class));

            assertTrue(waitUntil(() -> activeStreamPartitionCount(serverControl[0]) == 0, 1000L));
        });
    }

    @Test
    public void testResetWithoutExistingStreamDoesNotCreatePartition() throws Throwable {
        autoCloseNeta(neta -> {
            ProtoPartitionControl[] serverControl = new ProtoPartitionControl[1];
            VirtualPipe pipe = openHttpServer(neta, echoRequestHandler(), serverControl);

            pipe.server().fireEvent(Http2ResetEvent.class, new Http2ResetEvent(5, Http2ResetEvent.CANCEL));

            assertTrue(waitUntil(() -> serverControl[0] != null, 1000L));
            assertEquals(0, activeStreamPartitionCount(serverControl[0]));
            assertTrue(waitUntil(() -> findEvent(pipe.clientEvents(), Http2ResetEvent.class) != null, 1000L));

            Http2ResetEvent resetEvent = findEvent(pipe.clientEvents(), Http2ResetEvent.class);
            assertNotNull(resetEvent);
            assertTrue(resetEvent.isRemote());
            assertEquals(5L, resetEvent.streamId());
            assertEquals(Http2ErrorCode.CANCEL, resetEvent.errorCode());

            pipe.client().sendData(postRequest("/ok", "one")).get();
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
}