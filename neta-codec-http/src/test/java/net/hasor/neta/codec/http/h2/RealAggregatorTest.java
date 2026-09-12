/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec.http.h2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.routing.H2CUpgradeServerDuplex;
import net.hasor.neta.codec.http.routing.HttpAggregatorRoute;
import net.hasor.neta.codec.http.routing.HttpRouteKey;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

public class RealAggregatorTest extends AbstractHttp2Test {
    private static final int    MAX_CONTENT_LENGTH = 1048576;
    private static final String ENCODED_SETTINGS   = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] { 0x00, 0x03, 0x00, 0x00, 0x00, 0x64 });

    private static OkHttpClient http1Client() {
        return new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS).build();
    }

    private static OkHttpClient h2PriorKnowledgeClient() {
        return new OkHttpClient.Builder().protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE)).connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS).build();
    }

    private static ProtoInitializer buildRouteServerProto() {
        return ctx -> {
            ProtoHelper.standard().nextRouteAsStatic("protocol-detect", new HttpAggregatorRoute(), routing -> {
                ProtoRoutingControl routingControl = routing.control();

                routing.branch(HttpRouteKey.BRANCH_H1, b -> b//
                        .nextDuplex("http-codec", new HttpServerDuplex())//
                        .nextDuplex("h1-upgrade", new H2CUpgradeServerDuplex(routingControl))//
                        .nextDecoder("http-aggregator", new HttpRequestAggregator(MAX_CONTENT_LENGTH))//
                        .nextDecoder("http-handler", new InlineDispatchHandler("h1")))//
                        .branch(HttpRouteKey.BRANCH_H2, b -> b//
                                .nextDuplex("h2-frame", new Http2FrameDuplex(true))//
                                .nextDuplex("h2-message", new Http2ObjectDuplex(true, routingControl))//
                                .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), p -> {
                                    Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                                    p.policy(policy).byDefault(partitionCtx -> {
                                        partitionCtx.addLast("h2-control-lifecycle", new Http2ObjectStreamManager(p.control(), policy));
                                    }).byInitializer(partitionCtx -> {
                                        partitionCtx.addLast("h2-aggregator", new HttpServerDuplexAggregator(MAX_CONTENT_LENGTH));
                                        partitionCtx.addLastDecoder("h2-handler", new InlineDispatchHandler("h2"));
                                    });
                                }))//
                        .branch(HttpRouteKey.BRANCH_H2C, b -> b//
                                .nextDuplex("http-codec", new HttpServerDuplex())//
                                .nextDuplex("h2c-upgrade", new H2CUpgradeServerDuplex(routingControl))//
                                .nextDecoder("h2c-handler", new InlineDispatchHandler("h2c")));
            }).config(ctx);
        };
    }

    @Test
    public void testAggHttp1Only() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        OkHttpClient client = http1Client();
        try {
            neta.bind(new InetSocketAddress("127.0.0.1", port), buildRouteServerProto(), SoConfig.TCP());

            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/plain").get().build();
            try (Response response = client.newCall(request).execute()) {
                assertEquals(200, response.code());
                assertEquals("h1:/plain", response.body().string());
            }
        } finally {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
            neta.shutdown();
        }
    }

    @Test
    public void testAggDirectH2() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        OkHttpClient client = h2PriorKnowledgeClient();
        try {
            neta.bind(new InetSocketAddress("127.0.0.1", port), buildRouteServerProto(), SoConfig.TCP());

            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/direct").get().build();
            try (Response response = client.newCall(request).execute()) {
                assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
                assertEquals(200, response.code());
                assertEquals("h2:/direct", response.body().string());
            }
        } finally {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
            neta.shutdown();
        }
    }

    @Test
    public void testAggUpgradeToH2() throws Throwable {
        int port = findFreePort();
        NetManager neta = new NetManager();
        try {
            neta.bind(new InetSocketAddress("127.0.0.1", port), buildRouteServerProto(), SoConfig.TCP());

            try (RealH2cClient client = new RealH2cClient(port)) {
                FullHttpResponse upgraded = client.upgrade("/upgrade");
                try {
                    assertEquals(1, upgraded.streamId());
                    assertEquals(HttpStatus.OK, upgraded.status());
                    assertEquals("h2:/upgrade", utf8(upgraded.content()));
                } finally {
                    upgraded.release();
                }
            }
        } finally {
            neta.shutdown();
        }
    }

    @Test
    public void testAggHttp1ThenUpgradeToH2() throws Throwable {
        int port = findFreePort();
        NetManager neta = new NetManager();
        try {
            neta.bind(new InetSocketAddress("127.0.0.1", port), buildRouteServerProto(), SoConfig.TCP());

            try (RealH2cClient client = new RealH2cClient(port)) {
                Http1Response http1 = client.http1Get("/before-upgrade");
                assertEquals(200, http1.statusCode);
                assertEquals("h1:/before-upgrade", http1.bodyText);

                FullHttpResponse upgraded = client.upgrade("/upgrade-after-h1");
                try {
                    assertEquals(1, upgraded.streamId());
                    assertEquals(HttpStatus.OK, upgraded.status());
                    assertEquals("h2:/upgrade-after-h1", utf8(upgraded.content()));
                } finally {
                    upgraded.release();
                }

                FullHttpResponse afterUpgrade = client.sendH2Get(3, "/after-upgrade");
                try {
                    assertEquals(3, afterUpgrade.streamId());
                    assertEquals(HttpStatus.OK, afterUpgrade.status());
                    assertEquals("h2:/after-upgrade", utf8(afterUpgrade.content()));
                } finally {
                    afterUpgrade.release();
                }
            }
        } finally {
            neta.shutdown();
        }
    }

    private final class RealH2cClient implements Closeable {
        private final NetManager                   decoderNeta;
        private final VirtualPipe                  h2Decoder;
        private final ArrayDeque<FullHttpResponse> pendingResponses;
        private final Socket                       socket;
        private final InputStream                  input;
        private final OutputStream                 output;

        private RealH2cClient(int port) throws Throwable {
            this.decoderNeta = new NetManager();
            this.h2Decoder = openVirtualPipe(this.decoderNeta, ctx -> ProtoHelper.standard().nextDuplex("h2-frame", new Http2FrameDuplex(false)).nextDuplex("h2-message", new Http2ObjectDuplex(false)).nextDuplex("h2-client-aggregator", new HttpClientDuplexAggregator(MAX_CONTENT_LENGTH)).config(ctx), VrtSoConfig.asClient());
            this.pendingResponses = new ArrayDeque<FullHttpResponse>();
            this.socket = new Socket();
            this.socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            this.socket.setSoTimeout(1000);
            this.input = this.socket.getInputStream();
            this.output = this.socket.getOutputStream();
        }

        private Http1Response http1Get(String path) throws IOException {
            writeAscii("GET " + path + " HTTP/1.1\r\nHost: example.com\r\nConnection: keep-alive\r\n\r\n");
            return readHttp1Response();
        }

        private FullHttpResponse upgrade(String path) throws Throwable {
            writeAscii("GET " + path + " HTTP/1.1\r\nHost: example.com\r\nConnection: Upgrade, HTTP2-Settings\r\nUpgrade: h2c\r\nHTTP2-Settings: " + ENCODED_SETTINGS + "\r\n\r\n");
            Http1Response response = readHttp1Response();
            assertTrue(response.headerText.startsWith("HTTP/1.1 101"));
            assertTrue(response.headerText.toLowerCase().contains("upgrade: h2c"));
            if (response.extraBytes.length > 0) {
                decodeH2Bytes(response.extraBytes);
            }

            writeBytes(concat(CLIENT_PREFACE, frame(0, Http2FrameType.SETTINGS, Http2Flags.NONE, 0), frame(0, Http2FrameType.SETTINGS, Http2Flags.ACK, 0)));
            return awaitNextH2Response(5000L);
        }

        private FullHttpResponse sendH2Get(int streamId, String path) throws Throwable {
            byte[] headerBlock = encodeHeaders(headers(HttpHeaderNames.PSEUDO_METHOD, HttpMethod.GET.name(), HttpHeaderNames.PSEUDO_PATH, path, HttpHeaderNames.PSEUDO_SCHEME, "http", HttpHeaderNames.PSEUDO_AUTHORITY, "example.com"));
            writeBytes(frame(headerBlock.length, Http2FrameType.HEADERS, Http2Flags.END_HEADERS | Http2Flags.END_STREAM, streamId, headerBlock));
            return awaitNextH2Response(5000L);
        }

        private FullHttpResponse awaitNextH2Response(long timeoutMs) throws Throwable {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (!this.pendingResponses.isEmpty()) {
                    return this.pendingResponses.poll();
                }

                byte[] chunk = readAvailableChunk();
                if (chunk.length > 0) {
                    decodeH2Bytes(chunk);
                    continue;
                }
                Thread.sleep(10L);
            }
            throw new AssertionError("Timed out waiting for upgraded HTTP/2 response");
        }

        private void decodeH2Bytes(byte[] bytes) {
            List<HttpObject> decoded = receiveAndIntBound(this.h2Decoder, ByteBuf.wrap(bytes));
            for (HttpObject item : decoded) {
                if (item instanceof FullHttpResponse) {
                    this.pendingResponses.offer((FullHttpResponse) item);
                } else {
                    free(Collections.singletonList(item));
                }
            }
            drainQueue(this.h2Decoder.channelOutbound());
        }

        private Http1Response readHttp1Response() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int headerEnd = -1;
            int bodyLength = -1;
            byte[] temp = new byte[4096];
            while (true) {
                if (headerEnd >= 0 && buffer.size() >= headerEnd + Math.max(bodyLength, 0)) {
                    break;
                }
                int len = this.input.read(temp);
                if (len < 0) {
                    throw new EOFException("socket closed while reading HTTP/1 response");
                }
                buffer.write(temp, 0, len);
                byte[] all = buffer.toByteArray();
                if (headerEnd < 0) {
                    headerEnd = findHeaderEnd(all);
                    if (headerEnd >= 0) {
                        bodyLength = contentLength(all, headerEnd);
                    }
                }
            }

            byte[] all = buffer.toByteArray();
            String headerText = new String(all, 0, headerEnd, StandardCharsets.US_ASCII);
            int bodyEnd = headerEnd + Math.max(bodyLength, 0);
            byte[] bodyBytes = Arrays.copyOfRange(all, headerEnd, bodyEnd);
            byte[] extraBytes = Arrays.copyOfRange(all, bodyEnd, all.length);
            return new Http1Response(parseStatusCode(headerText), headerText, new String(bodyBytes, StandardCharsets.UTF_8), extraBytes);
        }

        private byte[] readAvailableChunk() throws IOException {
            byte[] buffer = new byte[8192];
            int len = this.input.read(buffer);
            if (len < 0) {
                throw new EOFException("socket closed while reading HTTP/2 frames");
            }
            return Arrays.copyOf(buffer, len);
        }

        private void writeAscii(String text) throws IOException {
            writeBytes(text.getBytes(StandardCharsets.US_ASCII));
        }

        private void writeBytes(byte[] bytes) throws IOException {
            this.output.write(bytes);
            this.output.flush();
        }

        @Override
        public void close() throws IOException {
            free(this.pendingResponses);
            free(castHttpObjects(drainQueue(this.h2Decoder.channelInbound())));
            this.socket.close();
            this.decoderNeta.shutdown();
        }
    }

    private static final class Http1Response {
        private final int    statusCode;
        private final String headerText;
        private final String bodyText;
        private final byte[] extraBytes;

        private Http1Response(int statusCode, String headerText, String bodyText, byte[] extraBytes) {
            this.statusCode = statusCode;
            this.headerText = headerText;
            this.bodyText = bodyText;
            this.extraBytes = extraBytes;
        }
    }

    private static int findHeaderEnd(byte[] raw) {
        for (int i = 0; i <= raw.length - 4; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    private static int contentLength(byte[] raw, int headerEnd) {
        String headerText = new String(raw, 0, headerEnd, StandardCharsets.US_ASCII).toLowerCase();
        for (String line : headerText.split("\\r\\n")) {
            if (line.startsWith("content-length:")) {
                return Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        return 0;
    }

    private static int parseStatusCode(String headerText) {
        String[] parts = headerText.split("\\s+");
        return Integer.parseInt(parts[1]);
    }

    private static final class InlineDispatchHandler implements ProtoHandler<HttpObject, Object> {
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
                if (!(item instanceof FullHttpRequest)) {
                    continue;
                }
                FullHttpRequest request = (FullHttpRequest) item;
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
