package net.hasor.neta.codec.http.h2;
import java.nio.charset.StandardCharsets;
import java.util.*;
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

public class H2CUpgradeServerDuplexeTest extends AbstractHttp2Test {
    private static final int    MAX_CONTENT_LENGTH = 1048576;
    private static final String ENCODED_SETTINGS   = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] { 0x00, 0x03, 0x00, 0x00, 0x00, 0x64 });

    @Test
    public void testUpgradeSingleEnd() throws Throwable {
        autoCloseNeta(neta -> {
            // server
            VirtualPipe serverPipe = openVirtualPipe(neta, (ProtoInitializer) ctx -> {
                ProtoHelper.standard().nextRouteAsStatic("protocol-detect", new HttpAggregatorRoute(), routing -> {
                    ProtoRoutingControl routingControl = routing.control();
                    routing.branch(HttpRouteKey.BRANCH_H1, branch -> {
                        branch.nextDuplex("http-codec", new HttpServerDuplexe()).nextDecoder("http-aggregator", new HttpRequestAggregator(MAX_CONTENT_LENGTH)).nextDecoder("http-handler", new InlineDispatchHandler("h1"));
                    }).branch(HttpRouteKey.BRANCH_H2, branch -> {
                        branch.nextDuplex("h2-frame", new Http2FrameDuplexe(true)).nextDuplex("h2-message", new Http2ObjectDuplexe(true, routingControl)).nextPartition("h2-stream", new Http2ObjectPartitionSelector(), partition -> {
                            Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                            partition.policy(policy).byDefault(partitionCtx -> {
                                partitionCtx.addLast("h2-control-lifecycle", new Http2ObjectStreamManager(partition.control(), policy));
                            }).byInitializer(partitionCtx -> {
                                partitionCtx.addLast("h2-aggregator", new HttpServerDuplexeAggregator(MAX_CONTENT_LENGTH));
                                partitionCtx.addLastDecoder("h2-handler", new InlineDispatchHandler("h2"));
                            });
                        });
                    }).branch(HttpRouteKey.BRANCH_H2C, branch -> {
                        branch.nextDuplex("http-codec", new HttpServerDuplexe()).nextDuplex("h2c-upgrade", new H2CUpgradeServerDuplexe(routingControl)).nextDecoder("h2c-handler", new InlineDispatchHandler("h2c"));
                    });
                }).config(ctx);
            }, VrtSoConfig.asServer());

            // client
            VirtualPipe clientDecoder = openVirtualPipe(neta, ctx -> {
                ProtoHelper.standard()//
                        .nextDuplex("h2-frame", new Http2FrameDuplexe(false))//
                        .nextDuplex("h2-message", new Http2ObjectDuplexe(false))//
                        .nextDuplex("h2-client-aggregator", new HttpClientDuplexeAggregator(MAX_CONTENT_LENGTH))//
                        .config(ctx);
            }, VrtSoConfig.asClient());

            serverPipe.channel().receiveData(ascii("GET /upgrade HTTP/1.1\r\n"//
                    + "Host: example.com\r\n"//
                    + "Connection: Upgrade, HTTP2-Settings\r\n"//
                    + "Upgrade: h2c\r\n"//
                    + "HTTP2-Settings: " + ENCODED_SETTINGS + "\r\n"//
                    + "\r\n"));

            assertTrue(waitUntil(() -> !serverPipe.channelOutbound().isEmpty() || !serverPipe.channelInboundErrors().isEmpty() || !serverPipe.channelOutboundErrors().isEmpty(), 1000L));
            assertTrue(serverPipe.channelInboundErrors().isEmpty());
            assertTrue(serverPipe.channelOutboundErrors().isEmpty());

            byte[] firstOutbound = drainRawBytes(serverPipe.channelOutbound());
            int headerEnd = findHeaderEnd(firstOutbound);
            assertTrue(headerEnd > 0);

            String responseHead = new String(firstOutbound, 0, headerEnd, StandardCharsets.US_ASCII);
            byte[] handshakeBytes = Arrays.copyOfRange(firstOutbound, headerEnd, firstOutbound.length);
            assertTrue(responseHead.startsWith("HTTP/1.1 101"));
            assertTrue(responseHead.toLowerCase().contains("upgrade: h2c"));

            List<HttpObject> handshakeObjects = receiveAndIntBound(clientDecoder, ByteBuf.wrap(handshakeBytes));
            try {
                assertTrue(handshakeObjects.isEmpty());
            } finally {
                free(handshakeObjects);
            }
            drainQueue(clientDecoder.channelOutbound());

            VirtualPipe frameDecoder = openVirtualPipe(neta, ctx -> {
                ctx.addLast("h2-frame", new Http2FrameDuplexe(false));
            }, VrtSoConfig.asClient());
            List<Http2Frame> serverPreface = receiveAndIntBound(frameDecoder, ByteBuf.wrap(handshakeBytes));
            assertEquals(1, serverPreface.size());
            assertEquals(Http2FrameType.SETTINGS, serverPreface.get(0).type());
            assertEquals(0, serverPreface.get(0).streamId());

            serverPipe.channel().receiveData(ByteBuf.wrap(concat(CLIENT_PREFACE, frame(0, Http2FrameType.SETTINGS, Http2Flags.NONE, 0), frame(0, Http2FrameType.SETTINGS, Http2Flags.ACK, 0))));

            assertTrue(waitUntil(() -> !serverPipe.channelOutbound().isEmpty() || !serverPipe.channelInboundErrors().isEmpty() || !serverPipe.channelOutboundErrors().isEmpty(), 1000L));
            assertTrue(serverPipe.channelInboundErrors().isEmpty());
            assertTrue(serverPipe.channelOutboundErrors().isEmpty());

            List<HttpObject> decoded = receiveAndIntBound(clientDecoder, ByteBuf.wrap(drainRawBytes(serverPipe.channelOutbound())));
            drainQueue(clientDecoder.channelOutbound());
            assertEquals(1, decoded.size());

            try {
                FullHttpResponse upgradedResponse = (FullHttpResponse) decoded.get(0);
                assertEquals(1, upgradedResponse.streamId());
                assertEquals(HttpStatus.OK, upgradedResponse.status());
                assertEquals("h2:/upgrade", utf8(upgradedResponse.content()));
            } finally {
                free(decoded);
            }
        });
    }

    @Test
    public void testUpgradeDualEnd() throws Throwable {
        autoCloseNeta(neta -> {
            // server
            VirtualPipe transport = openVirtualPipe(neta, null, ctx -> {
                ProtoHelper.standard().nextRouteAsStatic("protocol-detect", new HttpAggregatorRoute(), routing -> {
                    ProtoRoutingControl routingControl = routing.control();
                    routing.branch(HttpRouteKey.BRANCH_H1, branch -> {
                        branch.nextDuplex("http-codec", new HttpServerDuplexe()).nextDecoder("http-aggregator", new HttpRequestAggregator(MAX_CONTENT_LENGTH)).nextDecoder("http-handler", new InlineDispatchHandler("h1"));
                    }).branch(HttpRouteKey.BRANCH_H2, branch -> {
                        branch.nextDuplex("h2-frame", new Http2FrameDuplexe(true)).nextDuplex("h2-message", new Http2ObjectDuplexe(true, routingControl)).nextPartition("h2-stream", new Http2ObjectPartitionSelector(), partition -> {
                            Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                            partition.policy(policy).byDefault(partitionCtx -> {
                                partitionCtx.addLast("h2-control-lifecycle", new Http2ObjectStreamManager(partition.control(), policy));
                            }).byInitializer(partitionCtx -> {
                                partitionCtx.addLast("h2-aggregator", new HttpServerDuplexeAggregator(MAX_CONTENT_LENGTH));
                                partitionCtx.addLastDecoder("h2-handler", new InlineDispatchHandler("h2"));
                            });
                        });
                    }).branch(HttpRouteKey.BRANCH_H2C, branch -> {
                        branch.nextDuplex("http-codec", new HttpServerDuplexe()).nextDuplex("h2c-upgrade", new H2CUpgradeServerDuplexe(routingControl)).nextDecoder("h2c-handler", new InlineDispatchHandler("h2c"));
                    });
                }).config(ctx);
            });

            // client
            VirtualPipe clientDecoder = openVirtualPipe(neta, ctx -> {
                ProtoHelper.standard().nextDuplex("h2-frame", new Http2FrameDuplexe(false)).nextDuplex("h2-message", new Http2ObjectDuplexe(false)).nextDuplex("h2-client-aggregator", new HttpClientDuplexeAggregator(MAX_CONTENT_LENGTH)).build().config(ctx);
            }, VrtSoConfig.asClient());

            transport.client().sendData(ascii("GET /upgrade HTTP/1.1\r\n"//
                    + "Host: example.com\r\n"//
                    + "Connection: Upgrade, HTTP2-Settings\r\n"//
                    + "Upgrade: h2c\r\n"//
                    + "HTTP2-Settings: " + ENCODED_SETTINGS + "\r\n"//
                    + "\r\n")).get();

            assertTrue(waitUntil(() -> !transport.clientInbound().isEmpty() || !transport.serverInboundErrors().isEmpty() || !transport.clientOutboundErrors().isEmpty(), 1000L));
            byte[] firstOutbound = drainRawBytes(transport.clientInbound());
            int headerEnd = findHeaderEnd(firstOutbound);
            assertTrue(headerEnd > 0);

            String responseHead = new String(firstOutbound, 0, headerEnd, StandardCharsets.US_ASCII);
            assertTrue(responseHead.startsWith("HTTP/1.1 101"));
            assertTrue(responseHead.toLowerCase().contains("upgrade: h2c"));

            List<HttpObject> handshakeObjects = receiveAndIntBound(clientDecoder, ByteBuf.wrap(Arrays.copyOfRange(firstOutbound, headerEnd, firstOutbound.length)));
            try {
                assertTrue(handshakeObjects.isEmpty());
            } finally {
                free(handshakeObjects);
            }
            drainQueue(clientDecoder.channelOutbound());

            transport.client().sendData(ByteBuf.wrap(concat(CLIENT_PREFACE, frame(0, Http2FrameType.SETTINGS, Http2Flags.NONE, 0), frame(0, Http2FrameType.SETTINGS, Http2Flags.ACK, 0)))).get();
            byte[] headerBlock = encodeHeaders(headers(HttpHeaderNames.PSEUDO_METHOD, HttpMethod.GET.name(), HttpHeaderNames.PSEUDO_PATH, "/after", HttpHeaderNames.PSEUDO_SCHEME, "http", HttpHeaderNames.PSEUDO_AUTHORITY, "example.com"));
            transport.client().sendData(ByteBuf.wrap(frame(headerBlock.length, Http2FrameType.HEADERS, Http2Flags.END_HEADERS | Http2Flags.END_STREAM, 3, headerBlock))).get();

            List<FullHttpResponse> responses = new ArrayList<FullHttpResponse>();
            long deadline = System.currentTimeMillis() + 1500L;
            while (System.currentTimeMillis() < deadline && responses.size() < 2) {
                if (!transport.clientInbound().isEmpty()) {
                    List<HttpObject> decoded = receiveAndIntBound(clientDecoder, ByteBuf.wrap(drainRawBytes(transport.clientInbound())));
                    for (HttpObject item : decoded) {
                        if (item instanceof FullHttpResponse) {
                            responses.add((FullHttpResponse) item);
                        }
                    }
                    drainQueue(clientDecoder.channelOutbound());
                    continue;
                }
                Thread.sleep(10L);
            }

            assertTrue(transport.clientInboundErrors().isEmpty());
            assertTrue(transport.serverInboundErrors().isEmpty());
            assertTrue(transport.clientOutboundErrors().isEmpty());
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

    private byte[] drainRawBytes(Queue<Object> inboundQueue) {
        List<ByteBuf> payload = new ArrayList<ByteBuf>();
        for (Object item : drainQueue(inboundQueue)) {
            if (item instanceof ByteBuf) {
                payload.add((ByteBuf) item);
            }
        }
        return payload.isEmpty() ? new byte[0] : bytes(payload.toArray(new ByteBuf[0]));
    }

    private int findHeaderEnd(byte[] rawBytes) {
        for (int i = 0; i <= rawBytes.length - 4; i++) {
            if (rawBytes[i] == '\r' && rawBytes[i + 1] == '\n' && rawBytes[i + 2] == '\r' && rawBytes[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
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
                context.sendData(textResponse(request.streamId(), this.routeTag + ":" + request.uri())).get();
            }
            return ProtoStatus.Next;
        }

        @Override
        public boolean onEvent(ProtoContext context, SoEvent event) {
            return true;
        }
    }
}
