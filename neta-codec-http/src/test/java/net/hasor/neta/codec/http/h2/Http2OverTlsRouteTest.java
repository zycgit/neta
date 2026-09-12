/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h2;

import static org.junit.Assert.*;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.ProtoPartitionControl;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.channel.transport.virtual.VrtChannel;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.channel.transport.virtual.VrtSocketAddress;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.routing.Http2OverTlsRoute;
import net.hasor.neta.codec.http.routing.HttpRouteKey;
import net.hasor.neta.codec.ssl.*;

public class Http2OverTlsRouteTest extends AbstractHttp2Test {
    private static final int    MAX_CONTENT_LENGTH = 1048576;
    private static final String CERT_PATH          = "../neta-core/src/test/resources/ssl/ca/server.crt";
    private static final String KEY_PATH           = "../neta-core/src/test/resources/ssl/ca/server.pem";
    private static final String ENCODED_SETTINGS   = "AAMAAABk";

    @Test
    public void testTlsRouteH2WhenBothSupport() throws Throwable {
        SslConfig serverConf = serverSslConfig("h2", "http/1.1");
        SslConfig clientConf = clientSslConfig("h2", "http/1.1");
        RoutePair pair = openPair(serverConf, clientH2Proto(clientConf));
        try {
            assertEquals("h2", pair.serverSsl.getApplicationProtocol());
            assertEquals("h2", pair.clientSsl.getApplicationProtocol());

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_2_0, HttpMethod.GET, "/tls-h2");
            request.addHeader(HttpHeaderNames.HOST, "localhost");

            FullHttpResponse response = pair.client.sendRequest(request, 5000L);
            try {
                assertEquals(HttpStatus.OK, response.status());
                assertEquals(1, response.streamId());
                assertEquals("h2:/tls-h2", utf8(response.content()));
            } finally {
                response.release();
            }
        } finally {
            pair.close();
        }
    }

    @Test
    public void testTlsRouteH1WhenClientLacksH2() throws Throwable {
        SslConfig serverConf = serverSslConfig("h2", "http/1.1");
        SslConfig clientConf = clientSslConfig("http/1.1");
        RoutePair pair = openPair(serverConf, clientH1Proto(clientConf));
        try {
            assertEquals("http/1.1", pair.serverSsl.getApplicationProtocol());
            assertEquals("http/1.1", pair.clientSsl.getApplicationProtocol());

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/client-no-h2");
            request.addHeader(HttpHeaderNames.HOST, "localhost");

            FullHttpResponse response = pair.client.sendRequest(request, 5000L);
            try {
                assertEquals(HttpStatus.OK, response.status());
                assertEquals("h1:/client-no-h2", utf8(response.content()));
            } finally {
                response.release();
            }
        } finally {
            pair.close();
        }
    }

    @Test
    public void testTlsRouteH1WhenServerLacksH2() throws Throwable {
        SslConfig serverConf = serverSslConfig("http/1.1");
        SslConfig clientConf = clientSslConfig("h2", "http/1.1");
        RoutePair pair = openPair(serverConf, clientH1Proto(clientConf));
        try {
            assertEquals("http/1.1", pair.serverSsl.getApplicationProtocol());
            assertEquals("http/1.1", pair.clientSsl.getApplicationProtocol());

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/server-no-h2");
            request.addHeader(HttpHeaderNames.HOST, "localhost");

            FullHttpResponse response = pair.client.sendRequest(request, 5000L);
            try {
                assertEquals(HttpStatus.OK, response.status());
                assertEquals("h1:/server-no-h2", utf8(response.content()));
            } finally {
                response.release();
            }
        } finally {
            pair.close();
        }
    }

    @Test
    public void testTlsRouteH1WhenBothOnlySupportH1() throws Throwable {
        SslConfig serverConf = serverSslConfig("http/1.1");
        SslConfig clientConf = clientSslConfig("http/1.1");
        RoutePair pair = openPair(serverConf, clientH1Proto(clientConf));
        try {
            assertEquals("http/1.1", pair.serverSsl.getApplicationProtocol());
            assertEquals("http/1.1", pair.clientSsl.getApplicationProtocol());

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/tls-h1");
            request.addHeader(HttpHeaderNames.HOST, "localhost");

            FullHttpResponse response = pair.client.sendRequest(request, 5000L);
            try {
                assertEquals(HttpStatus.OK, response.status());
                assertEquals("h1:/tls-h1", utf8(response.content()));
            } finally {
                response.release();
            }
        } finally {
            pair.close();
        }
    }

    @Test
    public void testTlsRouteH1UpgradeAttemptStillStayOnH1() throws Throwable {
        SslConfig serverConf = serverSslConfig("h2", "http/1.1");
        SslConfig clientConf = clientSslConfig("http/1.1");
        RoutePair pair = openPair(serverConf, clientH1Proto(clientConf));
        try {
            assertEquals("http/1.1", pair.serverSsl.getApplicationProtocol());
            assertEquals("http/1.1", pair.clientSsl.getApplicationProtocol());

            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/upgrade-attempt");
            request.addHeader(HttpHeaderNames.HOST, "localhost");
            request.addHeader(HttpHeaderNames.CONNECTION, "Upgrade, HTTP2-Settings");
            request.addHeader(HttpHeaderNames.UPGRADE, "h2c");
            request.addHeader(HttpHeaderNames.HTTP2_SETTINGS, ENCODED_SETTINGS);

            FullHttpResponse response = pair.client.sendRequest(request, 5000L);
            try {
                assertEquals(HttpStatus.OK, response.status());
                assertEquals("h1:/upgrade-attempt", utf8(response.content()));
            } finally {
                response.release();
            }

            assertEquals("http/1.1", pair.serverSsl.getApplicationProtocol());
            assertEquals("http/1.1", pair.clientSsl.getApplicationProtocol());
        } finally {
            pair.close();
        }
    }

    private static ProtoInitializer serverProto(SslConfig sslConfig) {
        return ctx -> ProtoHelper.standard().nextDuplex("ssl", new SslDuplex(sslConfig))//
                .nextRouteAsStatic("alpn", new Http2OverTlsRoute(), routing -> {
                    ProtoRoutingControl routingControl = routing.control();

                    routing.branch(HttpRouteKey.BRANCH_H1, b -> b//
                            .nextDuplex("http-codec", new HttpServerDuplex())//
                            .nextDecoder("http-aggregator", new HttpRequestAggregator(MAX_CONTENT_LENGTH))//
                            .nextDecoder("http-handler", new InlineTlsHandler("h1")))//
                            .branch(HttpRouteKey.BRANCH_H2, b -> b//
                                    .nextDuplex("h2-frame", new Http2FrameDuplex(true))//
                                    .nextDuplex("h2-message", new Http2ObjectDuplex(true, routingControl))//
                                    .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), p1 -> {
                                        ProtoPartitionControl partitionControl = p1.control();
                                        Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                                        p1.policy(policy).byDefault(p2 -> {
                                            p2.addLast("h2-control-lifecycle", new Http2ObjectStreamManager(partitionControl, policy));
                                        }).byInitializer(partitionCtx -> {
                                            partitionCtx.addLast("h2-aggregator", new HttpServerDuplexAggregator(MAX_CONTENT_LENGTH));
                                            partitionCtx.addLastDecoder("h2-handler", new InlineTlsHandler("h2"));
                                        });
                                    }));
                }).config(ctx);
    }

    private static ProtoInitializer clientH1Proto(SslConfig sslConfig) {
        return ctx -> ProtoHelper.standard()//
                .nextDuplex("ssl", new SslDuplex(sslConfig))//
                .nextDuplex("http-codec", new HttpClientDuplex())//
                .nextDecoder("http-aggregator", new HttpResponseAggregator(MAX_CONTENT_LENGTH))//
                .config(ctx);
    }

    private static ProtoInitializer clientH2Proto(SslConfig sslConfig) {
        return ctx -> ProtoHelper.standard()//
                .nextDuplex("ssl", new SslDuplex(sslConfig))//
                .nextDuplex("h2-frame", new Http2FrameDuplex(false))//
                .nextDuplex("h2-message", new Http2ObjectDuplex(false))//
                .nextDuplex("h2-aggregator", new HttpClientDuplexAggregator(MAX_CONTENT_LENGTH))//
                .config(ctx);
    }

    private RoutePair openPair(SslConfig serverConf, ProtoInitializer clientProto) throws Throwable {
        NetManager neta = new NetManager();
        VrtSoConfig config = VrtSoConfig.asDefault();
        config.setAsynchronous(false);

        VrtSocketAddress address = new VrtSocketAddress(0, true);
        NetListen listen = neta.bind(address, serverProto(serverConf), config);
        NetChannel clientChannel = neta.connectSync(address, clientProto, config);
        VrtChannel serverChannel = (VrtChannel) neta.findChannel(3);
        listen.waitAnyAccept();

        SslContext clientSsl = waitSslReady((VrtChannel) clientChannel);
        SslContext serverSsl = waitSslReady(serverChannel);
        return new RoutePair(neta, clientChannel, serverChannel, clientSsl, serverSsl);
    }

    private SslContext waitSslReady(VrtChannel channel) throws InterruptedException {
        assertTrue(waitUntil(() -> {
            SslContext sslContext = channel.findProtoContext(SslContext.class);
            return sslContext != null && sslContext.isReady();
        }, 5000L));

        SslContext sslContext = channel.findProtoContext(SslContext.class);
        assertNotNull(sslContext);
        assertTrue(sslContext.isReady());
        return sslContext;
    }

    private static SslConfig serverSslConfig(String... appProtocols) throws Exception {
        X509Certificate[] certChain;
        PrivateKey privateKey;
        try (FileInputStream certInput = new FileInputStream(CERT_PATH)) {
            certChain = SslUtils.toX509Certificates(certInput);
        }
        try (FileInputStream keyInput = new FileInputStream(KEY_PATH)) {
            privateKey = SslUtils.toPrivateKey(keyInput, null);
        }

        SslConfig sslConfig = new SslConfig();
        sslConfig.setCertChainDirect(certChain);
        sslConfig.setPrivateKeyDirect(privateKey);
        sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_2 });
        sslConfig.setAppProtocol(appProtocols);
        return sslConfig;
    }

    private static SslConfig clientSslConfig(String... appProtocols) throws Exception {
        X509TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { trustAll }, new SecureRandom());

        SslConfig sslConfig = new SslConfig();
        sslConfig.setSslContext(sslContext);
        sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_2 });
        sslConfig.setAppProtocol(appProtocols);
        return sslConfig;
    }

    private static final class RoutePair implements Closeable {
        private final NetManager neta;
        private final HttpClient client;
        private final SslContext clientSsl;
        private final SslContext serverSsl;

        private RoutePair(NetManager neta, NetChannel clientChannel, VrtChannel serverChannel, SslContext clientSsl, SslContext serverSsl) {
            this.neta = neta;
            this.client = new HttpClient(clientChannel);
            this.clientSsl = clientSsl;
            this.serverSsl = serverSsl;
        }

        @Override
        public void close() throws IOException {
            this.client.close();
            this.neta.shutdown();
        }
    }

    private static final class HttpClient implements Closeable {
        private final NetChannel    channel;
        private final Queue<Object> inbound;

        private HttpClient(NetChannel channel) {
            this.channel = channel;
            this.inbound = new ConcurrentLinkedQueue<Object>();
            this.channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, data -> {
                if (data.getData() != null) {
                    this.inbound.offer(data.getData());
                }
            });
        }

        private FullHttpResponse sendRequest(FullHttpRequest request, long timeoutMs) throws Exception {
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

    private static final class InlineTlsHandler implements ProtoHandler<HttpObject, Object> {
        private final String prefix;

        private InlineTlsHandler(String prefix) {
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
                    byte[] bodyBytes = (this.prefix + ":" + request.uri()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
