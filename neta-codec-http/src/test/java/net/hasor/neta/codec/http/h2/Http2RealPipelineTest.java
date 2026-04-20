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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoHelper;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.codec.http.*;

public class Http2RealPipelineTest extends AbstractHttp2Test {
    private static class RequestSnapshot {
        private final long   streamId;
        private final String uri;
        private final String body;

        private RequestSnapshot(long streamId, String uri, String body) {
            this.streamId = streamId;
            this.uri = uri;
            this.body = body;
        }
    }

    private HttpRequest postStreamRequest(long streamId, String uri) {
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, uri);
        request.streamId(streamId);
        return request;
    }

    private HttpByteBuf streamContent(long streamId, String bodyText) {
        DefaultHttpByteBuf content = new DefaultHttpByteBuf(ByteBuf.wrap(bodyText.getBytes(StandardCharsets.UTF_8)));
        content.streamId(streamId);
        return content;
    }

    private LastHttpContent lastStreamContent(long streamId, String bodyText) {
        DefaultLastHttpContent content = new DefaultLastHttpContent(ByteBuf.wrap(bodyText.getBytes(StandardCharsets.UTF_8)));
        content.streamId(streamId);
        return content;
    }

    private HttpResponse streamResponse(long streamId) {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_2_0, HttpStatus.OK);
        response.streamId(streamId);
        return response;
    }

    private LastHttpHeaders streamResponseHeaders(long streamId, int contentLength) {
        DefaultLastHttpHeaders headers = new DefaultLastHttpHeaders();
        headers.streamId(streamId);
        headers.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(contentLength));
        headers.setHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        return headers;
    }

    protected VirtualPipe openHttp2VirtualPipeAsClientStream(NetManager neta, ProtoHandler<HttpObject, Object> handler) throws Throwable {
        int MAX_CONTENT_LENGTH = 1048576;
        return openVirtualPipe(neta, clientCtx -> {
            ProtoHelper.standard()//
                    .nextDuplex("h2-frame", new Http2FrameDuplexe(false))   //
                    .nextDuplex("h2-message", new Http2ObjectDuplexe(false))//
                    .config(clientCtx);
        }, serverCtx -> {
            ProtoHelper.standard()//
                    .nextDuplex("h2-frame", new Http2FrameDuplexe(true))    //
                    .nextDuplex("h2-message", new Http2ObjectDuplexe(true)) //
                    .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), pb -> {
                        Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                        pb.policy(policy).byInitializer(pbc -> {
                            pbc.addLast("h2-server-aggregator", new HttpServerDuplexeAggregator(MAX_CONTENT_LENGTH));
                            pbc.addLastDecoder("h2-handler", handler);
                        }).byDefault(pbc -> {
                            pbc.addLast("h2-control-events", new Http2ObjectStreamManager(pb.control(), policy));
                        });
                    }).config(serverCtx);
        });
    }

    @Test
    public void testServerSeqRequests() throws Throwable {
        autoCloseNeta(neta -> {
            Queue<RequestSnapshot> received = new ConcurrentLinkedQueue<>();
            VirtualPipe pipe = openHttp2VirtualPipe(neta, (context, src, dst) -> {
                while (src.hasMore()) {
                    HttpObject item = src.takeMessage();
                    if (!(item instanceof FullHttpRequest)) {
                        continue;
                    }

                    FullHttpRequest request = (FullHttpRequest) item;
                    received.offer(new RequestSnapshot(request.streamId(), request.uri(), body(request)));
                }
                return ProtoStatus.Next;
            });

            pipe.client().sendData(postRequest("/alpha", "A")).get();
            pipe.client().sendData(postRequest("/beta", "BB")).get();
            pipe.client().sendData(postRequest("/gamma", "CCC")).get();

            assertTrue(waitUntil(() -> received.size() >= 3, 1000L));
            assertTrue(pipe.clientInboundErrors().isEmpty());
            assertTrue(pipe.serverInboundErrors().isEmpty());
            assertTrue(pipe.clientOutboundErrors().isEmpty());
            assertTrue(pipe.serverOutboundErrors().isEmpty());

            List<RequestSnapshot> requests = drainQueue(received);
            assertEquals(3, requests.size());
            assertEquals(1L, requests.get(0).streamId);
            assertEquals(3L, requests.get(1).streamId);
            assertEquals(5L, requests.get(2).streamId);
            assertEquals("/alpha", requests.get(0).uri);
            assertEquals("/beta", requests.get(1).uri);
            assertEquals("/gamma", requests.get(2).uri);
            assertEquals("A", requests.get(0).body);
            assertEquals("BB", requests.get(1).body);
            assertEquals("CCC", requests.get(2).body);
        });
    }

    @Test
    public void testServerInterleavedRequests() throws Throwable {
        autoCloseNeta(neta -> {
            Queue<RequestSnapshot> received = new ConcurrentLinkedQueue<>();
            VirtualPipe pipe = openHttp2VirtualPipeAsClientStream(neta, (context, src, dst) -> {
                while (src.hasMore()) {
                    HttpObject item = src.takeMessage();
                    if (!(item instanceof FullHttpRequest)) {
                        continue;
                    }

                    FullHttpRequest request = (FullHttpRequest) item;
                    received.offer(new RequestSnapshot(request.streamId(), request.uri(), body(request)));
                }
                return ProtoStatus.Next;
            });

            pipe.client().sendData(postStreamRequest(1, "/alpha")).get();
            pipe.client().sendData(streamContent(1, "A")).get();
            pipe.client().sendData(postStreamRequest(3, "/beta")).get();
            pipe.client().sendData(streamContent(3, "1")).get();
            pipe.client().sendData(streamContent(1, "B")).get();
            pipe.client().sendData(streamContent(3, "2")).get();
            pipe.client().sendData(lastStreamContent(1, "CD")).get();
            pipe.client().sendData(lastStreamContent(3, "34")).get();

            assertTrue(waitUntil(() -> received.size() >= 2, 1000L));
            assertTrue(pipe.clientInboundErrors().isEmpty());
            assertTrue(pipe.serverInboundErrors().isEmpty());
            assertTrue(pipe.clientOutboundErrors().isEmpty());
            assertTrue(pipe.serverOutboundErrors().isEmpty());

            List<RequestSnapshot> requests = drainQueue(received);
            Map<Long, String> byStream = new LinkedHashMap<>();
            assertEquals(2, requests.size());
            for (RequestSnapshot request : requests) {
                byStream.put(request.streamId, request.body);
            }

            assertEquals("ABCD", byStream.get(1L));
            assertEquals("1234", byStream.get(3L));
        });
    }

    @Test
    public void testClientSeqResponses() throws Throwable {
        autoCloseNeta(neta -> {
            Queue<RequestSnapshot> received = new ConcurrentLinkedQueue<>();
            VirtualPipe pipe = openHttp2VirtualPipe(neta, (context, src, dst) -> {
                while (src.hasMore()) {
                    HttpObject item = src.takeMessage();
                    if (!(item instanceof FullHttpRequest)) {
                        continue;
                    }

                    FullHttpRequest request = (FullHttpRequest) item;
                    received.offer(new RequestSnapshot(request.streamId(), request.uri(), body(request)));
                }
                return ProtoStatus.Next;
            });

            pipe.client().sendData(postRequest("/alpha", "A").streamId(1)).get();
            pipe.client().sendData(postRequest("/beta", "BB").streamId(3)).get();
            pipe.client().sendData(postRequest("/gamma", "CCC").streamId(5)).get();

            assertTrue(waitUntil(() -> received.size() >= 3, 1000L));

            pipe.server().sendData(textResponse(1, "client:/alpha:A")).get();
            pipe.server().sendData(textResponse(3, "client:/beta:BB")).get();
            pipe.server().sendData(textResponse(5, "client:/gamma:CCC")).get();

            assertTrue(waitUntil(() -> pipe.clientInbound().size() >= 3, 1000L));
            assertTrue(pipe.clientInboundErrors().isEmpty());
            assertTrue(pipe.serverInboundErrors().isEmpty());
            assertTrue(pipe.clientOutboundErrors().isEmpty());
            assertTrue(pipe.serverOutboundErrors().isEmpty());

            List<HttpObject> responses = castHttpObjects(drainQueue(pipe.clientInbound()));
            try {
                assertEquals(3, responses.size());
                Map<Long, String> byStream = new LinkedHashMap<>();
                for (HttpObject item : responses) {
                    assertTrue(item instanceof FullHttpResponse);
                    FullHttpResponse response = (FullHttpResponse) item;
                    byStream.put(response.streamId(), utf8(response.content()));
                }

                assertEquals("client:/alpha:A", byStream.get(1L));
                assertEquals("client:/beta:BB", byStream.get(3L));
                assertEquals("client:/gamma:CCC", byStream.get(5L));
            } finally {
                free(responses);
            }
        });
    }

    @Test
    public void testClientInterleavedResponses() throws Throwable {
        autoCloseNeta(neta -> {
            AtomicReference<Long> alphaStreamId = new AtomicReference<>();
            AtomicReference<Long> betaStreamId = new AtomicReference<>();
            VirtualPipe pipe = openHttp2VirtualPipeAsClientStream(neta, (context, src, dst) -> {
                while (src.hasMore()) {
                    HttpObject item = src.takeMessage();
                    if (!(item instanceof FullHttpRequest)) {
                        continue;
                    }

                    FullHttpRequest request = (FullHttpRequest) item;
                    if ("/alpha".equals(request.uri())) {
                        alphaStreamId.set(request.streamId());
                    } else if ("/beta".equals(request.uri())) {
                        betaStreamId.set(request.streamId());
                    }
                }
                return ProtoStatus.Next;
            });

            pipe.client().sendData(postRequest("/alpha", "").streamId(1)).get();
            pipe.client().sendData(postRequest("/beta", "").streamId(3)).get();

            assertTrue(waitUntil(() -> alphaStreamId.get() != null && betaStreamId.get() != null, 1000L));

            long alpha = alphaStreamId.get();
            long beta = betaStreamId.get();
            pipe.server().sendData(streamResponse(alpha)).get();
            pipe.server().sendData(streamResponseHeaders(alpha, 4)).get();
            pipe.server().sendData(streamResponse(beta)).get();
            pipe.server().sendData(streamResponseHeaders(beta, 4)).get();
            pipe.server().sendData(streamContent(alpha, "A")).get();
            pipe.server().sendData(streamContent(beta, "1")).get();
            pipe.server().sendData(streamContent(alpha, "B")).get();
            pipe.server().sendData(streamContent(beta, "2")).get();
            pipe.server().sendData(lastStreamContent(alpha, "CD")).get();
            pipe.server().sendData(lastStreamContent(beta, "34")).get();

            assertTrue(waitUntil(() -> {
                int completed = 0;
                for (Object item : pipe.clientInbound()) {
                    if (item instanceof LastHttpContent) {
                        completed++;
                    }
                }
                return completed >= 2;
            }, 1000L));
            assertTrue(pipe.clientInboundErrors().isEmpty());
            assertTrue(pipe.serverInboundErrors().isEmpty());
            assertTrue(pipe.clientOutboundErrors().isEmpty());
            assertTrue(pipe.serverOutboundErrors().isEmpty());

            List<HttpObject> responses = castHttpObjects(drainQueue(pipe.clientInbound()));
            try {
                Map<Long, HttpStatus> statusByStream = new LinkedHashMap<>();
                Map<Long, StringBuilder> bodyByStream = new LinkedHashMap<>();
                Map<Long, Integer> endByStream = new LinkedHashMap<>();
                for (HttpObject item : responses) {
                    if (item instanceof HttpResponse) {
                        HttpResponse response = (HttpResponse) item;
                        statusByStream.put(response.streamId(), response.status());
                    }
                    if (item instanceof HttpContent) {
                        HttpContent content = (HttpContent) item;
                        bodyByStream.computeIfAbsent(content.streamId(), key -> new StringBuilder()).append(utf8(content.content()));
                    }
                    if (item instanceof LastHttpContent) {
                        endByStream.put(item.streamId(), endByStream.getOrDefault(item.streamId(), 0) + 1);
                    }
                }

                assertEquals(HttpStatus.OK, statusByStream.get(1L));
                assertEquals(HttpStatus.OK, statusByStream.get(3L));
                assertEquals("ABCD", bodyByStream.get(1L).toString());
                assertEquals("1234", bodyByStream.get(3L).toString());
                assertEquals(Integer.valueOf(1), endByStream.get(1L));
                assertEquals(Integer.valueOf(1), endByStream.get(3L));
            } finally {
                free(responses);
            }
        });
    }
}