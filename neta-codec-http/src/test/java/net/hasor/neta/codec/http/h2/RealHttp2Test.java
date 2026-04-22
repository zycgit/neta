package net.hasor.neta.codec.http.h2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.ProtoPartitionControl;
import net.hasor.neta.codec.http.*;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

public class RealHttp2Test extends AbstractHttpTest {
    private static final int MAX_CONTENT_LENGTH = 1048576;

    private static OkHttpClient h2PriorKnowledgeClient() {
        return new OkHttpClient.Builder().protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE)).connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS).build();
    }

    @Test
    public void testRealServerH2Prior() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        OkHttpClient client = h2PriorKnowledgeClient();
        try {
            neta.bind(new InetSocketAddress("127.0.0.1", port), ctx -> ProtoHelper.standard()//
                    .nextDuplex("h2-frame", new Http2FrameDuplex(true))//
                    .nextDuplex("h2-message", new Http2ObjectDuplex(true))//
                    .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), pp -> {
                        Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                        ProtoPartitionControl control = pp.control();
                        pp.policy(policy).byDefault(p -> {
                            p.addLast("h2-control-lifecycle", new Http2ObjectStreamManager(control, policy));
                        }).byInitializer(p -> {
                            p.addLast("h2-aggregator", new HttpServerDuplexAggregator(MAX_CONTENT_LENGTH));
                            p.addLastDecoder("h2-handler", new InlineServerHandler("server"));
                        });
                    }).config(ctx), SoConfig.TCP());

            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/hello").get().build();
            try (Response response = client.newCall(request).execute()) {
                assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
                assertEquals(200, response.code());
                assertEquals("server:/hello", response.body().string());
            }
        } finally {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
            neta.shutdown();
        }
    }

    @Test
    public void testRealClientH2() throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server, new HTTP2CServerConnectionFactory(new HttpConfiguration()));
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);

        CountDownLatch requestSeen = new CountDownLatch(1);
        AtomicReference<String> requestMethod = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        server.setHandler(new AbstractHandler() {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
                requestMethod.set(request.getMethod());
                requestPath.set(request.getRequestURI());

                byte[] body = "client:/hello".getBytes(StandardCharsets.UTF_8);
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType("text/plain; charset=utf-8");
                response.setContentLength(body.length);
                response.getOutputStream().write(body);

                baseRequest.setHandled(true);
                requestSeen.countDown();
            }
        });
        server.start();

        NetManager neta = new NetManager();
        NetaH2ClientHarness client = null;
        try {
            client = new NetaH2ClientHarness(neta.connectSync(new InetSocketAddress("127.0.0.1", connector.getLocalPort()), ctx -> {
                ProtoHelper.standard().nextDuplex("h2-frame", new Http2FrameDuplex(false)).nextDuplex("h2-message", new Http2ObjectDuplex(false)).nextDuplex("h2-client-aggregator", new HttpClientDuplexAggregator(MAX_CONTENT_LENGTH)).build().config(ctx);
            }, SoConfig.TCP()));

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/hello");
            request.addHeader(HttpHeaderNames.HOST, "127.0.0.1:" + connector.getLocalPort());
            FullHttpResponse response = client.sendRequest(request, 5000L);
            try {
                assertEquals(HttpStatus.OK, response.status());
                assertEquals("client:/hello", utf8(response.content()));
            } finally {
                response.release();
            }

            assertTrue(requestSeen.await(5, TimeUnit.SECONDS));
            assertEquals("GET", requestMethod.get());
            assertEquals("/hello", requestPath.get());
        } finally {
            if (client != null) {
                client.close();
            }
            neta.shutdown();
            server.stop();
            server.destroy();
        }
    }

    private static final class NetaH2ClientHarness implements Closeable {
        private final NetChannel    channel;
        private final Queue<Object> inbound;

        private NetaH2ClientHarness(NetChannel channel) {
            this.channel = channel;
            this.inbound = new ConcurrentLinkedQueue<Object>();
            this.channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> {
                if (data.getData() != null) {
                    this.inbound.offer(data.getData());
                }
            });
        }

        private FullHttpResponse sendRequest(DefaultFullHttpRequest request, long timeoutMs) throws Exception {
            this.channel.sendData(request).get();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                Object message = this.inbound.poll();
                if (message instanceof FullHttpResponse) {
                    return (FullHttpResponse) message;
                }
                Thread.sleep(10L);
            }
            throw new AssertionError("Timed out waiting for FullHttpResponse");
        }

        @Override
        public void close() {
            this.channel.close().await();
        }
    }

    private static final class InlineServerHandler implements ProtoHandler<HttpObject, Object> {
        private final String prefix;

        private InlineServerHandler(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Object> dst) throws Throwable {
            while (src.hasMore()) {
                HttpObject item = src.takeMessage();
                if (!(item instanceof FullHttpRequest)) {
                    continue;
                }
                FullHttpRequest request = (FullHttpRequest) item;
                try {
                    byte[] bodyBytes = (this.prefix + ":" + request.uri()).getBytes(StandardCharsets.UTF_8);
                    DefaultFullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), HttpStatus.OK, ByteBuf.wrap(bodyBytes));
                    if (request.streamId() > 0) {
                        response.streamId(request.streamId());
                    }
                    response.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(bodyBytes.length));
                    response.setHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
                    context.sendData(response).get();
                } finally {
                    request.release();
                }
            }
            return ProtoStatus.Next;
        }
    }
}