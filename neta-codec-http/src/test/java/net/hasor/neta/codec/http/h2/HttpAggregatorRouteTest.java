package net.hasor.neta.codec.http.h2;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.routing.HttpAggregatorRoute;
import net.hasor.neta.codec.http.routing.HttpRouteKey;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HttpAggregatorRouteTest extends AbstractHttp2Test {
    private static final int    MAX_CONTENT_LENGTH = 1048576;
    private static final String ENCODED_SETTINGS   = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] { 0x00, 0x03, 0x00, 0x00, 0x00, 0x64 });

    @Test
    public void testRouteH1() throws Throwable {
        autoCloseNeta(neta -> {
            // server
            VirtualPipe transport = openVirtualPipe(neta, null, ctx -> ProtoHelper.standard()//
                    .nextRouteAsStatic("protocol-detect", new HttpAggregatorRoute(), routing -> {
                        ProtoRoutingControl routingControl = routing.control();

                        routing.branch(HttpRouteKey.BRANCH_H1, b -> b//
                                        .nextDuplex("http-codec", new HttpServerDuplexe())//
                                        .nextDecoder("http-aggregator", new HttpRequestAggregator(MAX_CONTENT_LENGTH))//
                                        .nextDecoder("http-handler", new InlineDispatchHandler("h1")))//
                                .branch(HttpRouteKey.BRANCH_H2, b -> b//
                                        .nextDuplex("h2-frame", new Http2FrameDuplexe(true))//
                                        .nextDuplex("h2-message", new Http2ObjectDuplexe(true, routingControl))//
                                        .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), p -> {
                                            Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                                            p.policy(policy).byDefault(partitionCtx -> {
                                                partitionCtx.addLast("h2-control-lifecycle", new Http2ObjectStreamManager(p.control(), policy));
                                            }).byInitializer(partitionCtx -> {
                                                partitionCtx.addLast("h2-aggregator", new HttpServerDuplexeAggregator(MAX_CONTENT_LENGTH));
                                                partitionCtx.addLastDecoder("h2-handler", new InlineDispatchHandler("h2"));
                                            });
                                        }))//
                                .branch(HttpRouteKey.BRANCH_H2C, b -> b //
                                        .nextDuplex("http-codec", new HttpServerDuplexe())  //
                                        .nextDuplex("h2c-upgrade", new H2CUpgradeServerDuplexe(routingControl))//
                                        .nextDecoder("h2c-handler", new InlineDispatchHandler("h2c")));
                    }).config(ctx));
            // client
            VirtualPipe clientHttp = openVirtualPipe(neta, ctx -> {
                ctx.addLast("http-client", new HttpClientDuplexe());
                ctx.addLastDecoder("http-agg", new HttpResponseAggregator());
            }, VrtSoConfig.asClient());

            transport.client().sendData(ascii("GET /plain HTTP/1.1\r\nHost: example.com\r\n\r\n")).get();

            assertTrue(waitUntil(() -> !transport.clientInbound().isEmpty() || !transport.serverInboundErrors().isEmpty() || !transport.clientOutboundErrors().isEmpty(), 1000L));
            List<ByteBuf> rawResponse = new ArrayList<ByteBuf>();
            for (Object item : drainQueue(transport.clientInbound())) {
                if (item instanceof ByteBuf) {
                    rawResponse.add((ByteBuf) item);
                }
            }

            List<HttpObject> decoded = receiveAndIntBound(clientHttp, ByteBuf.wrap(bytes(rawResponse.toArray(new ByteBuf[0]))));
            assertTrue(transport.clientOutboundErrors().isEmpty());
            assertTrue(transport.serverInboundErrors().isEmpty());
            assertEquals(1, decoded.size());
            try {
                FullHttpResponse response = (FullHttpResponse) decoded.get(0);
                assertEquals(HttpStatus.OK, response.status());
                assertEquals("h1:/plain", utf8(response.content()));
            } finally {
                free(decoded);
            }
        });
    }

    @Test
    public void testRouteH2Prior() throws Throwable {
        autoCloseNeta(neta -> {
            // server
            VirtualPipe transport = openVirtualPipe(neta, null, ctx -> {
                ProtoHelper.standard()//
                        .nextRouteAsStatic("protocol-detect", new HttpAggregatorRoute(), routing -> {
                            ProtoRoutingControl routingControl = routing.control();

                            routing.branch(HttpRouteKey.BRANCH_H1, b -> b//
                                            .nextDuplex("http-codec", new HttpServerDuplexe())//
                                            .nextDecoder("http-aggregator", new HttpRequestAggregator(MAX_CONTENT_LENGTH))//
                                            .nextDecoder("http-handler", new InlineDispatchHandler("h1")))//
                                    .branch(HttpRouteKey.BRANCH_H2, b -> b//
                                            .nextDuplex("h2-frame", new Http2FrameDuplexe(true))//
                                            .nextDuplex("h2-message", new Http2ObjectDuplexe(true, routingControl))//
                                            .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), p -> {
                                                Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                                                p.policy(policy).byDefault(partitionCtx -> {
                                                    partitionCtx.addLast("h2-control-lifecycle", new Http2ObjectStreamManager(p.control(), policy));
                                                }).byInitializer(partitionCtx -> {
                                                    partitionCtx.addLast("h2-aggregator", new HttpServerDuplexeAggregator(MAX_CONTENT_LENGTH));
                                                    partitionCtx.addLastDecoder("h2-handler", new InlineDispatchHandler("h2"));
                                                });
                                            }))//
                                    .branch(HttpRouteKey.BRANCH_H2C, b -> b //
                                            .nextDuplex("http-codec", new HttpServerDuplexe())  //
                                            .nextDuplex("h2c-upgrade", new H2CUpgradeServerDuplexe(routingControl))//
                                            .nextDecoder("h2c-handler", new InlineDispatchHandler("h2c")));
                        }).config(ctx);
            });
            // client
            VirtualPipe clientH2 = openVirtualPipe(neta, ctx -> {
                ProtoHelper.standard()//
                        .nextDuplex("h2-frame", new Http2FrameDuplexe(false))//
                        .nextDuplex("h2-message", new Http2ObjectDuplexe(false))//
                        .nextDuplex("h2-client-aggregator", new HttpClientDuplexeAggregator(MAX_CONTENT_LENGTH))//
                        .config(ctx);
            }, VrtSoConfig.asClient());

            byte[] headerBlock = encodeHeaders(headers(//
                    HttpHeaderNames.PSEUDO_METHOD, HttpMethod.GET.name(),//
                    HttpHeaderNames.PSEUDO_PATH, "/direct",//
                    HttpHeaderNames.PSEUDO_SCHEME, "http",//
                    HttpHeaderNames.PSEUDO_AUTHORITY, "example.com"));
            transport.client().sendData(ByteBuf.wrap(concat(//
                    CLIENT_PREFACE,//
                    frame(0, Http2FrameType.SETTINGS, Http2Flags.NONE, 0),//
                    frame(headerBlock.length, Http2FrameType.HEADERS, Http2Flags.END_HEADERS | Http2Flags.END_STREAM, 1, headerBlock)))).get();

            assertTrue(waitUntil(() -> !transport.clientInbound().isEmpty() || !transport.serverInboundErrors().isEmpty() || !transport.clientOutboundErrors().isEmpty(), 1000L));
            List<ByteBuf> rawResponse = new ArrayList<ByteBuf>();
            for (Object item : drainQueue(transport.clientInbound())) {
                if (item instanceof ByteBuf) {
                    rawResponse.add((ByteBuf) item);
                }
            }

            List<HttpObject> decoded = receiveAndIntBound(clientH2, ByteBuf.wrap(bytes(rawResponse.toArray(new ByteBuf[0]))));
            drainQueue(clientH2.channelOutbound());
            assertTrue(transport.clientOutboundErrors().isEmpty());
            assertTrue(transport.serverInboundErrors().isEmpty());
            assertEquals(1, decoded.size());
            try {
                FullHttpResponse response = (FullHttpResponse) decoded.get(0);
                assertEquals(1, response.streamId());
                assertEquals(HttpStatus.OK, response.status());
                assertEquals("h2:/direct", utf8(response.content()));
            } finally {
                free(decoded);
            }
        });
    }

    @Test
    public void testRouteH2cUpgrade() throws Throwable {
        autoCloseNeta(neta -> {
            // server
            VirtualPipe transport = openVirtualPipe(neta, null, ctx -> ProtoHelper.standard()//
                    .nextRouteAsStatic("protocol-detect", new HttpAggregatorRoute(), routing -> {
                        ProtoRoutingControl routingControl = routing.control();

                        routing.branch(HttpRouteKey.BRANCH_H1, b -> b//
                                        .nextDuplex("http-codec", new HttpServerDuplexe())//
                                        .nextDecoder("http-aggregator", new HttpRequestAggregator(MAX_CONTENT_LENGTH))//
                                        .nextDecoder("http-handler", new InlineDispatchHandler("h1")))//
                                .branch(HttpRouteKey.BRANCH_H2, b -> b//
                                        .nextDuplex("h2-frame", new Http2FrameDuplexe(true))//
                                        .nextDuplex("h2-message", new Http2ObjectDuplexe(true, routingControl))//
                                        .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), p -> {
                                            Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                                            p.policy(policy).byDefault(partitionCtx -> {
                                                partitionCtx.addLast("h2-control-lifecycle", new Http2ObjectStreamManager(p.control(), policy));
                                            }).byInitializer(partitionCtx -> {
                                                partitionCtx.addLast("h2-aggregator", new HttpServerDuplexeAggregator(MAX_CONTENT_LENGTH));
                                                partitionCtx.addLastDecoder("h2-handler", new InlineDispatchHandler("h2"));
                                            });
                                        }))//
                                .branch(HttpRouteKey.BRANCH_H2C, b -> b //
                                        .nextDuplex("http-codec", new HttpServerDuplexe())  //
                                        .nextDuplex("h2c-upgrade", new H2CUpgradeServerDuplexe(routingControl))//
                                        .nextDecoder("h2c-handler", new InlineDispatchHandler("h2c")));
                    }).config(ctx));
            // client
            VirtualPipe clientH2 = openVirtualPipe(neta, ctx -> {
                ProtoHelper.standard()//
                        .nextDuplex("h2-frame", new Http2FrameDuplexe(false))//
                        .nextDuplex("h2-message", new Http2ObjectDuplexe(false))//
                        .nextDuplex("h2-client-aggregator", new HttpClientDuplexeAggregator(MAX_CONTENT_LENGTH))//
                        .config(ctx);
            }, VrtSoConfig.asClient());

            transport.client().sendData(ascii("GET /upgrade HTTP/1.1\r\n"//
                    + "Host: example.com\r\n"//
                    + "Connection: Upgrade, HTTP2-Settings\r\n"//
                    + "Upgrade: h2c\r\n"//
                    + "HTTP2-Settings: " + ENCODED_SETTINGS + "\r\n"//
                    + "\r\n")).get();

            assertTrue(waitUntil(() -> !transport.clientInbound().isEmpty() || !transport.serverInboundErrors().isEmpty() || !transport.clientOutboundErrors().isEmpty(), 1000L));
            List<ByteBuf> firstBatch = new ArrayList<ByteBuf>();
            for (Object item : drainQueue(transport.clientInbound())) {
                if (item instanceof ByteBuf) {
                    firstBatch.add((ByteBuf) item);
                }
            }
            byte[] firstOutbound = bytes(firstBatch.toArray(new ByteBuf[0]));
            int headerEnd = -1;
            for (int i = 0; i <= firstOutbound.length - 4; i++) {
                if (firstOutbound[i] == '\r' && firstOutbound[i + 1] == '\n' && firstOutbound[i + 2] == '\r' && firstOutbound[i + 3] == '\n') {
                    headerEnd = i + 4;
                    break;
                }
            }
            assertTrue(headerEnd > 0);

            String responseHead = new String(firstOutbound, 0, headerEnd, StandardCharsets.US_ASCII);
            assertTrue(responseHead.startsWith("HTTP/1.1 101"));
            assertTrue(responseHead.toLowerCase().contains("upgrade: h2c"));

            List<HttpObject> handshakeObjects = receiveAndIntBound(clientH2, ByteBuf.wrap(Arrays.copyOfRange(firstOutbound, headerEnd, firstOutbound.length)));
            try {
                assertTrue(handshakeObjects.isEmpty());
            } finally {
                free(handshakeObjects);
            }
            drainQueue(clientH2.channelOutbound());

            byte[] afterHeaderBlock = encodeHeaders(headers(//
                    HttpHeaderNames.PSEUDO_METHOD, HttpMethod.GET.name(),//
                    HttpHeaderNames.PSEUDO_PATH, "/after",//
                    HttpHeaderNames.PSEUDO_SCHEME, "http",//
                    HttpHeaderNames.PSEUDO_AUTHORITY, "example.com"));
            transport.client().sendData(ByteBuf.wrap(concat(//
                    CLIENT_PREFACE,//
                    frame(0, Http2FrameType.SETTINGS, Http2Flags.NONE, 0),//
                    frame(0, Http2FrameType.SETTINGS, Http2Flags.ACK, 0),//
                    frame(afterHeaderBlock.length, Http2FrameType.HEADERS, Http2Flags.END_HEADERS | Http2Flags.END_STREAM, 3, afterHeaderBlock)))).get();

            List<FullHttpResponse> responses = new ArrayList<FullHttpResponse>();
            long deadline = System.currentTimeMillis() + 1500L;
            while (System.currentTimeMillis() < deadline && responses.size() < 2) {
                if (!transport.clientInbound().isEmpty()) {
                    List<ByteBuf> rawBatch = new ArrayList<ByteBuf>();
                    for (Object item : drainQueue(transport.clientInbound())) {
                        if (item instanceof ByteBuf) {
                            rawBatch.add((ByteBuf) item);
                        }
                    }
                    List<HttpObject> decoded = receiveAndIntBound(clientH2, ByteBuf.wrap(bytes(rawBatch.toArray(new ByteBuf[0]))));
                    for (HttpObject item : decoded) {
                        if (item instanceof FullHttpResponse) {
                            responses.add((FullHttpResponse) item);
                        }
                    }
                    drainQueue(clientH2.channelOutbound());
                    continue;
                }
                Thread.sleep(10L);
            }

            assertTrue(transport.clientOutboundErrors().isEmpty());
            assertTrue(transport.serverInboundErrors().isEmpty());
            assertTrue(transport.serverOutboundErrors().isEmpty());
            assertEquals(2, responses.size());
            try {
                assertEquals(1, responses.get(0).streamId());
                assertEquals("h2:/upgrade", utf8(responses.get(0).content()));
                assertEquals(3, responses.get(1).streamId());
                assertEquals("h2:/after", utf8(responses.get(1).content()));
            } finally {
                free(responses);
            }
        });
    }

    private static class InlineDispatchHandler implements ProtoHandler<HttpObject, Object> {
        private final String routeTag;

        private InlineDispatchHandler(String routeTag) {
            this.routeTag = routeTag;
        }

        @Override
        public void onInit(String name, int poolSize, ProtoContext context) {
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Object> dst) throws Throwable {
            while (src.hasMore()) {
                HttpObject item = src.takeMessage();
                if (!(item instanceof net.hasor.neta.codec.http.FullHttpRequest)) {
                    continue;
                }
                net.hasor.neta.codec.http.FullHttpRequest request = (net.hasor.neta.codec.http.FullHttpRequest) item;
                try {
                    byte[] body = (this.routeTag + ":" + request.uri()).getBytes(StandardCharsets.UTF_8);
                    DefaultFullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), HttpStatus.OK, ByteBuf.wrap(body));
                    if (request.streamId() > 0) {
                        response.streamId(request.streamId());
                    }
                    response.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(body.length));
                    response.setHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
                    context.sendData(response).get();
                } finally {
                    request.release();
                }
            }
            return ProtoStatus.Next;
        }

        @Override
        public boolean onEvent(ProtoContext context, SoEvent event) {
            return true;
        }
    }
}
