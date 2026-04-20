/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.nhttp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.hasor.neta.channel.NetListen;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SoConfig;
import net.hasor.neta.codec.ssl.SslAuthKeyType;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.neta.codec.ssl.SslProtocol;
import okhttp3.*;

/**
 * Tests HTTP/2 over TLS (ALPN-negotiated h2) with large request bodies
 * that exceed the initial flow control window (65535 bytes).
 * <p>
 * Verifies that WINDOW_UPDATE frames are correctly generated and delivered
 * through the nested branch pipeline: h2 branch → ALPN router → SSL → TLS branch → network.
 * </p>
 */
public class Http2OverTlsUploadTest {

    private NetManager   neta;
    private NetListen    listen;
    private int          port;
    private OkHttpClient h2TlsClient;

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static SslConfig serverSslConfig() {
        SslConfig sslConfig = new SslConfig();
        sslConfig.setAuthType(SslAuthKeyType.PEM);
        sslConfig.setPemCertChain("ssl/ca/server.crt");
        sslConfig.setPemPrivate("ssl/ca/server.pem");
        sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_2 });
        return sslConfig;
    }

    private static OkHttpClient createH2TlsClient() throws Exception {
        // Trust-all TrustManager for self-signed test certificate
        X509TrustManager trustAll = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            public void checkClientTrusted(X509Certificate[] c, String a) {
            }

            public void checkServerTrusted(X509Certificate[] c, String a) {
            }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { trustAll }, new SecureRandom());

        return new OkHttpClient.Builder()//
                .sslSocketFactory(sslContext.getSocketFactory(), trustAll)//
                .hostnameVerifier((hostname, session) -> true)//
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))//
                .connectTimeout(5, TimeUnit.SECONDS)//
                .readTimeout(60, TimeUnit.SECONDS)//
                .writeTimeout(60, TimeUnit.SECONDS)//
                .callTimeout(120, TimeUnit.SECONDS)//
                .build();
    }

    @Before
    public void setUp() throws Exception {
        Thread.sleep(50);
        neta = new NetManager();
        h2TlsClient = createH2TlsClient();
    }

    @After
    public void tearDown() {
        if (h2TlsClient != null) {
            h2TlsClient.dispatcher().executorService().shutdown();
            h2TlsClient.connectionPool().evictAll();
        }
        if (neta != null) {
            try {
                neta.shutdown();
            } catch (IOException ignore) {
            }
        }
    }

    /**
     * Starts an HTTPS server with full ALPN-based h2 pipeline via NetaHttpServer.
    * Pipeline: tls-detect → [tls branch: ssl → alpn-router → [h2 branch: h2-codec → h2-aggregator → h2-handler]]
     */
    private void startHttpsServer(HttpServlet... servlets) throws Exception {
        port = findFreePort();

        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.ssl(serverSslConfig());
        httpServer.http2(true);

        for (HttpServlet servlet : servlets) {
            httpServer.addServlet("/upload", servlet);
        }

        httpServer.initServletContext();
        httpServer.configureAlpn();

        ProtoInitializer init = httpServer.createHttpsAlpnInitializer();
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", port);
        listen = neta.bind(addr, init, SoConfig.TCP());
    }

    private HttpServlet echoLengthServlet() {
        return new HttpServlet() {
            @Override
            protected void doPost(ServletRequest req, ServletResponse resp) throws IOException {
                int bodyLen = (int) req.getContentLength();
                resp.setContentType("text/plain");
                resp.write("received:" + bodyLen);
            }
        };
    }

    // ========================= Test: Small POST (fits in initial window) =========================

    @Test
    public void testH2Tls_PostSmallBody_1KB() throws Exception {
        startHttpsServer(echoLengthServlet());

        byte[] body = new byte[1024];
        Arrays.fill(body, (byte) 'A');

        Request request = new Request.Builder()//
                .url("https://127.0.0.1:" + port + "/upload")//
                .post(RequestBody.create(body, MediaType.parse("application/octet-stream")))//
                .build();

        Response response = h2TlsClient.newCall(request).execute();
        assertEquals(200, response.code());
        String responseBody = response.body().string();
        assertEquals("received:1024", responseBody);
        assertEquals(Protocol.HTTP_2, response.protocol());
    }

    // ========================= Test: POST that exceeds initial window (65535 bytes) =========================

    @Test
    public void testH2Tls_PostExceedsInitialWindow_100KB() throws Exception {
        startHttpsServer(echoLengthServlet());

        // 100KB body — exceeds the default 65535-byte window
        byte[] body = new byte[102400];
        Arrays.fill(body, (byte) 'B');

        Request request = new Request.Builder()//
                .url("https://127.0.0.1:" + port + "/upload")//
                .post(RequestBody.create(body, MediaType.parse("application/octet-stream")))//
                .build();

        Response response = h2TlsClient.newCall(request).execute();
        assertEquals(200, response.code());
        String responseBody = response.body().string();
        assertEquals("received:102400", responseBody);
        assertEquals(Protocol.HTTP_2, response.protocol());
    }

    // ========================= Test: Large POST (200KB) =========================

    @Test
    public void testH2Tls_PostExceedsInitialWindow_200KB() throws Exception {
        startHttpsServer(echoLengthServlet());

        // 200KB body — well over the initial 65535-byte window
        byte[] body = new byte[204800];
        Arrays.fill(body, (byte) 'C');

        Request request = new Request.Builder()//
                .url("https://127.0.0.1:" + port + "/upload")//
                .post(RequestBody.create(body, MediaType.parse("application/octet-stream")))//
                .build();

        Response response = h2TlsClient.newCall(request).execute();
        assertEquals(200, response.code());
        String responseBody = response.body().string();
        assertEquals("received:204800", responseBody);
        assertEquals(Protocol.HTTP_2, response.protocol());
    }

    // ========================= Test: GET over h2-TLS (sanity check) =========================

    @Test
    public void testH2Tls_GetRequest() throws Exception {
        port = findFreePort();

        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.ssl(serverSslConfig());
        httpServer.http2(true);

        httpServer.addServlet("/hello", new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                resp.write("Hello from h2-TLS");
            }
        });

        httpServer.initServletContext();
        httpServer.configureAlpn();

        ProtoInitializer init = httpServer.createHttpsAlpnInitializer();
        listen = neta.bind(new InetSocketAddress("127.0.0.1", port), init, SoConfig.TCP());

        Request request = new Request.Builder()//
                .url("https://127.0.0.1:" + port + "/hello")//
                .get()//
                .build();

        Response response = h2TlsClient.newCall(request).execute();
        assertEquals(200, response.code());
        assertNotNull(response.body());
        assertEquals("Hello from h2-TLS", response.body().string());
        assertEquals(Protocol.HTTP_2, response.protocol());
    }
}
