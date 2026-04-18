package net.hasor.neta.codec.http.h2;
import java.io.*;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.ProtoPartitionControl;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.channel.transport.virtual.VrtSoConfig;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.routing.H2CUpgradeServerDuplexer;
import net.hasor.neta.codec.http.routing.HttpAggregatorOverTlsRoute;
import net.hasor.neta.codec.http.routing.HttpRouteKey;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.neta.codec.ssl.SslDuplexer;
import net.hasor.neta.codec.ssl.SslProtocol;
import net.hasor.neta.codec.ssl.SslUtils;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RealTlsAggregatorTest extends AbstractHttp2Test {
    private static final int    MAX_CONTENT_LENGTH = 1048576;
    private static final String CERT_PATH          = "../neta-core/src/test/resources/ssl/ca/server.crt";
    private static final String KEY_PATH           = "../neta-core/src/test/resources/ssl/ca/server.pem";
    private static final String ENCODED_SETTINGS   = "AAMAAABk";

    private static OkHttpClient httpsHttp1Client() throws Exception {
        return httpsClient(Collections.singletonList(Protocol.HTTP_1_1));
    }

    private static OkHttpClient httpsH2Client() throws Exception {
        return httpsClient(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1));
    }

    private static OkHttpClient httpsClient(List<Protocol> protocols) throws Exception {
        X509TrustManager trustAll = trustAllManager();
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { trustAll }, new SecureRandom());

        return new OkHttpClient.Builder()//
                .sslSocketFactory(sslContext.getSocketFactory(), trustAll)//
                .hostnameVerifier((hostname, session) -> true)//
                .protocols(protocols)//
                .connectTimeout(5, TimeUnit.SECONDS)//
                .readTimeout(5, TimeUnit.SECONDS)//
                .writeTimeout(5, TimeUnit.SECONDS)//
                .build();
    }

    private static ProtoInitializer buildTlsRouteServerProto(SslConfig sslConfig) {
        return ctx -> ProtoHelper.standard()//
                .nextDuplex("ssl", new SslDuplexer(sslConfig))//
                .nextRouteAsStatic("alpn", new HttpAggregatorOverTlsRoute(), routing -> {
                    ProtoRoutingControl routingControl = routing.control();

                    routing.branch(HttpRouteKey.BRANCH_H1, b -> b//
                                    .nextDuplex("http-codec", new HttpServerDuplexe())//
                                    .nextDuplex("h1-h2c-upgrade-bridge", new H2CUpgradeServerDuplexer(routingControl))//
                                    .nextDecoder("http-aggregator", new HttpRequestAggregator(MAX_CONTENT_LENGTH))//
                                    .nextDecoder("http-handler", new InlineDispatchHandler("h1")))//
                            .branch(HttpRouteKey.BRANCH_H2, b -> b//
                                    .nextDuplex("h2-frame", new Http2FrameDuplexe(true))//
                                    .nextDuplex("h2-message", new Http2ObjectDuplexe(true, routingControl))//
                                    .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), partition -> {
                                        Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                                        ProtoPartitionControl control = partition.control();
                                        partition.policy(policy).byDefault(partitionCtx -> {
                                            partitionCtx.addLast("h2-control-lifecycle", new Http2ObjectStreamManager(control, policy));
                                        }).byInitializer(partitionCtx -> {
                                            partitionCtx.addLast("h2-aggregator", new HttpServerDuplexeAggregator(MAX_CONTENT_LENGTH));
                                            partitionCtx.addLastDecoder("h2-handler", new InlineDispatchHandler("h2"));
                                        });
                                    }))//
                            .branch(HttpRouteKey.BRANCH_H2C, b -> b//
                                    .nextDuplex("http-codec", new HttpServerDuplexe())//
                                    .nextDuplex("h2c-upgrade", new H2CUpgradeServerDuplexer(routingControl))//
                                    .nextDecoder("h2c-handler", new InlineDispatchHandler("h2c")));
                }).config(ctx);
    }

    @Test
    public void testTlsAggHttp1Only() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        OkHttpClient client = httpsHttp1Client();
        try {
            neta.bind(new InetSocketAddress("127.0.0.1", port), buildTlsRouteServerProto(serverSslConfig("h2", "http/1.1")), SoConfig.TCP());

            Request request = new Request.Builder().url("https://127.0.0.1:" + port + "/plain").get().build();
            try (Response response = client.newCall(request).execute()) {
                assertEquals(Protocol.HTTP_1_1, response.protocol());
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
    public void testTlsAggDirectH2() throws Exception {
        int port = findFreePort();
        NetManager neta = new NetManager();
        OkHttpClient client = httpsH2Client();
        try {
            neta.bind(new InetSocketAddress("127.0.0.1", port), buildTlsRouteServerProto(serverSslConfig("h2", "http/1.1")), SoConfig.TCP());

            Request request = new Request.Builder().url("https://127.0.0.1:" + port + "/direct").get().build();
            try (Response response = client.newCall(request).execute()) {
                assertEquals(Protocol.HTTP_2, response.protocol());
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
    public void testTlsAggPriFallbackToH2Branch() throws Throwable {
        int port = findFreePort();
        NetManager neta = new NetManager();
        try {
            neta.bind(new InetSocketAddress("127.0.0.1", port), buildTlsRouteServerProto(serverSslConfig("h2", "http/1.1")), SoConfig.TCP());

            try (RealTlsH2cClient client = new RealTlsH2cClient(port, "http/1.1")) {
                assertEquals("http/1.1", client.applicationProtocol());

                FullHttpResponse response = client.sendH2PriorKnowledgeGet(1, "/pri-fallback");
                try {
                    assertEquals("http/1.1", client.applicationProtocol());
                    assertEquals(1, response.streamId());
                    assertEquals(HttpStatus.OK, response.status());
                    assertEquals("h2:/pri-fallback", utf8(response.content()));
                } finally {
                    response.release();
                }
            }
        } finally {
            neta.shutdown();
        }
    }

    @Test
    public void testTlsAggDirectH2cUpgradeBranch() throws Throwable {
        int port = findFreePort();
        NetManager neta = new NetManager();
        try {
            neta.bind(new InetSocketAddress("127.0.0.1", port), buildTlsRouteServerProto(serverSslConfig("h2", "http/1.1")), SoConfig.TCP());

            try (RealTlsH2cClient client = new RealTlsH2cClient(port, "http/1.1")) {
                assertEquals("http/1.1", client.applicationProtocol());

                FullHttpResponse upgraded = client.upgrade("/upgrade");
                try {
                    assertEquals("http/1.1", client.applicationProtocol());
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
    public void testTlsAggHttp1ThenViaHttp1xH2cBridgeToH2Branch() throws Throwable {
        int port = findFreePort();
        NetManager neta = new NetManager();
        try {
            neta.bind(new InetSocketAddress("127.0.0.1", port), buildTlsRouteServerProto(serverSslConfig("h2", "http/1.1")), SoConfig.TCP());

            try (RealTlsH2cClient client = new RealTlsH2cClient(port, "http/1.1")) {
                assertEquals("http/1.1", client.applicationProtocol());

                Http1Response http1 = client.http1Get("/before-upgrade");
                assertEquals(200, http1.statusCode);
                assertEquals("h1:/before-upgrade", http1.bodyText);
                assertEquals("http/1.1", client.applicationProtocol());

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

    private final class RealTlsH2cClient implements Closeable {
        private final NetManager                   decoderNeta;
        private final VirtualPipe                  h2Decoder;
        private final ArrayDeque<FullHttpResponse> pendingResponses;
        private final SSLSocket                    socket;
        private final InputStream                  input;
        private final OutputStream                 output;

        private RealTlsH2cClient(int port, String... appProtocols) throws Throwable {
            this.decoderNeta = new NetManager();
            this.h2Decoder = openVirtualPipe(this.decoderNeta, ctx -> ProtoHelper.standard()//
                    .nextDuplex("h2-frame", new Http2FrameDuplexe(false))//
                    .nextDuplex("h2-message", new Http2ObjectDuplexe(false))//
                    .nextDuplex("h2-client-aggregator", new HttpClientDuplexeAggregator(MAX_CONTENT_LENGTH))//
                    .config(ctx), VrtSoConfig.asClient());
            this.pendingResponses = new ArrayDeque<FullHttpResponse>();

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] { trustAllManager() }, new SecureRandom());
            SSLSocketFactory socketFactory = sslContext.getSocketFactory();
            this.socket = (SSLSocket) socketFactory.createSocket();
            configureApplicationProtocols(this.socket, appProtocols);
            this.socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            this.socket.setSoTimeout(1000);
            this.socket.startHandshake();
            this.input = this.socket.getInputStream();
            this.output = this.socket.getOutputStream();
        }

        private String applicationProtocol() throws Exception {
            return getApplicationProtocol(this.socket);
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
            byte[] headerBlock = encodeHeaders(headers(HttpHeaderNames.PSEUDO_METHOD, HttpMethod.GET.name(), HttpHeaderNames.PSEUDO_PATH, path, HttpHeaderNames.PSEUDO_SCHEME, "https", HttpHeaderNames.PSEUDO_AUTHORITY, "example.com"));
            writeBytes(frame(headerBlock.length, Http2FrameType.HEADERS, Http2Flags.END_HEADERS | Http2Flags.END_STREAM, streamId, headerBlock));
            return awaitNextH2Response(5000L);
        }

        private FullHttpResponse sendH2PriorKnowledgeGet(int streamId, String path) throws Throwable {
            byte[] headerBlock = encodeHeaders(headers(HttpHeaderNames.PSEUDO_METHOD, HttpMethod.GET.name(), HttpHeaderNames.PSEUDO_PATH, path, HttpHeaderNames.PSEUDO_SCHEME, "https", HttpHeaderNames.PSEUDO_AUTHORITY, "example.com"));
            writeBytes(concat(CLIENT_PREFACE, frame(0, Http2FrameType.SETTINGS, Http2Flags.NONE, 0), frame(headerBlock.length, Http2FrameType.HEADERS, Http2Flags.END_HEADERS | Http2Flags.END_STREAM, streamId, headerBlock)));
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
            String headerText = new String(all, 0, headerEnd, java.nio.charset.StandardCharsets.US_ASCII);
            int bodyEnd = headerEnd + Math.max(bodyLength, 0);
            byte[] bodyBytes = Arrays.copyOfRange(all, headerEnd, bodyEnd);
            byte[] extraBytes = Arrays.copyOfRange(all, bodyEnd, all.length);
            return new Http1Response(parseStatusCode(headerText), headerText, new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8), extraBytes);
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
            writeBytes(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
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
                    byte[] body = (this.routeTag + ":" + request.uri()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
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

    private static X509TrustManager trustAllManager() {
        return new X509TrustManager() {
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
    }

    private static void configureApplicationProtocols(SSLSocket socket, String... appProtocols) throws Exception {
        SSLParameters parameters = socket.getSSLParameters();
        Method setProtocols = SSLParameters.class.getMethod("setApplicationProtocols", String[].class);
        setProtocols.invoke(parameters, new Object[] { appProtocols });
        socket.setSSLParameters(parameters);
    }

    private static String getApplicationProtocol(SSLSocket socket) throws Exception {
        Method getProtocol = SSLSocket.class.getMethod("getApplicationProtocol");
        Object selected = getProtocol.invoke(socket);
        return selected == null ? null : String.valueOf(selected);
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
        String headerText = new String(raw, 0, headerEnd, java.nio.charset.StandardCharsets.US_ASCII).toLowerCase();
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
}