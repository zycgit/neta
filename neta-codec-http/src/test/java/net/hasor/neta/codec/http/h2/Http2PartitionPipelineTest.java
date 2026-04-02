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
    private HttpRequest postStreamRequest(int streamId, String uri) {
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.POST, uri);
        request.streamId(streamId);
        return request;
    }

    private HttpByteBuf streamContent(int streamId, String bodyText) {
        DefaultHttpByteBuf content = new DefaultHttpByteBuf(ByteBuf.wrap(bodyText.getBytes(StandardCharsets.UTF_8)));
        content.streamId(streamId);
        return content;
    }

    private LastHttpContent lastStreamContent(int streamId, String bodyText) {
        DefaultLastHttpContent content = new DefaultLastHttpContent(ByteBuf.wrap(bodyText.getBytes(StandardCharsets.UTF_8)));
        content.streamId(streamId);
        return content;
    }

    protected VirtualPipe openPartitionPipe(NetManager neta, ProtoHandler<HttpObject, Object> handler) throws Throwable {
        return this.openHttpServer(neta, handler, new ProtoPartitionControl[1]);
    }

    protected VirtualPipe openRawClientPartitionPipe(NetManager neta, ProtoHandler<HttpObject, Object> handler) throws Throwable {
        return openVirtualPipe(neta, clientCtx -> {
            ProtoHelper.standard()//
                    .nextDuplex("h2-frame", new Http2FrameDuplexe(false))   //
                    .nextDuplex("h2-message", new Http2ObjectDuplexe(false))//
                    .build().config(clientCtx);
        }, serverCtx -> {
            ProtoHelper.standard()//
                    .nextDuplex("h2-frame", new Http2FrameDuplexe(true))    //
                    .nextDuplex("h2-message", new Http2ObjectDuplexe(true)) //
                    .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), pb -> {
                        Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                        pb.policy(policy).byInitializer(pbc -> {
                            pbc.addLast("h2-server-aggregator", new HttpServerDuplexeAggregator(1048576));
                            pbc.addLastDecoder("h2-handler", handler);
                        }).byDefault(pbc -> {
                            pbc.addLast("h2-control-events", new Http2ObjectStreamManager(pb.control(), policy));
                        });
                    }).build().config(serverCtx);
        });
    }

    protected ProtoHandler<HttpObject, Object> outOfOrderResponseHandler() {
        AtomicReference<ProtoContext> slowContext = new AtomicReference<>();
        AtomicReference<Integer> slowStreamId = new AtomicReference<>();
        AtomicReference<String> slowUri = new AtomicReference<>();
        AtomicReference<String> slowBody = new AtomicReference<>();
        return (context, src, dst) -> {
            while (src.hasMore()) {
                HttpObject item = src.takeMessage();
                if (!(item instanceof FullHttpRequest)) {
                    continue;
                }

                FullHttpRequest request = (FullHttpRequest) item;
                try {
                    String uri = request.uri();
                    String body = utf8(request.content());
                    int streamId = request.streamId();
                    if ("/slow".equals(uri)) {
                        slowContext.set(context);
                        slowStreamId.set(streamId);
                        slowUri.set(uri);
                        slowBody.set(body);
                    } else {
                        sendResponse(context, streamId, uri, body, "fast:");

                        ProtoContext savedContext = slowContext.getAndSet(null);
                        Integer savedStreamId = slowStreamId.getAndSet(null);
                        String savedUri = slowUri.getAndSet(null);
                        String savedBody = slowBody.getAndSet(null);
                        if (savedContext != null && savedStreamId != null && savedUri != null && savedBody != null) {
                            sendResponse(savedContext, savedStreamId, savedUri, savedBody, "slow:");
                        }
                    }
                } finally {
                    request.release();
                }
            }

            return ProtoStatus.Next;
        };
    }

    @Test
    public void testFullStackRequestResponseClosesServerPartition() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openPartitionPipe(neta, echoRequestHandler());

            pipe.client().sendData(postRequest("/echo", "hello")).get();

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
        });
    }

    @Test
    public void testConcurrentRequestsAllowOutOfOrderResponses() throws Throwable {
        autoCloseNeta(neta -> {
            VirtualPipe pipe = openPartitionPipe(neta, outOfOrderResponseHandler());

            pipe.client().sendData(postRequest("/slow", "alpha")).get();
            pipe.client().sendData(postRequest("/fast", "beta")).get();

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
        });
    }

    @Test
    public void testConcurrentRequestsAllowAlternatingDataPackets() throws Throwable {
        autoCloseNeta(neta -> {
            Queue<FullHttpRequest> received = new ConcurrentLinkedQueue<>();
            VirtualPipe pipe = openRawClientPartitionPipe(neta, (context, src, dst) -> {
                while (src.hasMore()) {
                    HttpObject item = src.takeMessage();
                    if (item instanceof FullHttpRequest) {
                        received.offer((FullHttpRequest) item);
                    }
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

            if (!waitUntil(() -> received.size() >= 2, 1000L)) {
                fail("received=" + received.size());
            }
            assertTrue(pipe.clientInboundErrors().isEmpty());
            assertTrue(pipe.serverInboundErrors().isEmpty());
            assertTrue(pipe.clientOutboundErrors().isEmpty());
            assertTrue(pipe.serverOutboundErrors().isEmpty());

            List<FullHttpRequest> requests = drainQueue(received);
            assertEquals(2, requests.size());
            Map<Integer, String> byStream = new LinkedHashMap<>();
            for (FullHttpRequest request : requests) {
                byStream.put(request.streamId(), body(request));
            }

            assertEquals("ABCD", byStream.get(1));
            assertEquals("1234", byStream.get(3));
        });
    }
}