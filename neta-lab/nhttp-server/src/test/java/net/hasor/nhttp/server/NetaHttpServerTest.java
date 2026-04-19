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

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Queue;

import org.junit.Ignore;
import org.junit.Test;

import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.PlayLoad;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.transport.virtual.*;
import net.hasor.neta.codec.ssl.*;

/**
 * Integration tests for {@link NetaHttpServer} pipeline configurations.
 * <p>
 * Tests protocol routing (ALPN-based), HTTP/1.1 request/response, and servlet dispatch
 * using VrtChannel (virtual in-memory channels) for reliable, port-free testing.
 * </p>
 * <p>
 * SSL/TLS tests use VrtListen pattern for proper handshake support.
 * </p>
 * @author Test Suite for NetaHttpServer
 */
public class NetaHttpServerTest {

    // ========================= Helper: SSL Config =========================

    private static SslConfig sslConfig() {
        SslConfig sslConfig = new SslConfig();
        sslConfig.setAuthType(SslAuthKeyType.PEM);
        sslConfig.setPemCertChain("ssl/ca/server.crt");
        sslConfig.setPemPrivate("ssl/ca/server.pem");
        sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_2 });
        return sslConfig;
    }

    // ========================= Helper: ByteBuf Utilities =========================

    private static ByteBuf toByteBuf(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.US_ASCII);
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(bytes.length);
        buf.writeBytes(bytes, 0, bytes.length);
        buf.markWriter();
        return buf;
    }

    private static String collectStrings(Queue<String> queue) {
        StringBuilder sb = new StringBuilder();
        String msg;
        while ((msg = queue.poll()) != null) {
            sb.append(msg);
        }
        return sb.toString();
    }

    /** Subscribe helper: reads ByteBuf data immediately before it is released */
    private static Queue<String> subscribeAsString(VrtChannel channel) {
        Queue<String> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        channel.subscribe(d -> {
            Object data = d.getData();
            if (data instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) data;
                if (buf.readableBytes() > 0) {
                    queue.offer(buf.readString(buf.readableBytes(), StandardCharsets.US_ASCII));
                }
            }
        });
        return queue;
    }

    /** Subscribe helper for SSL tests: reads inbound ByteBuf immediately */
    private static Queue<String> subscribeInboundAsString(VrtChannel channel) {
        Queue<String> queue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        channel.subscribe(PlayLoad::isInbound, d -> {
            Object data = d.getData();
            if (data instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) data;
                if (buf.readableBytes() > 0) {
                    queue.offer(buf.readString(buf.readableBytes(), StandardCharsets.US_ASCII));
                }
            }
        });
        return queue;
    }

    private static SslContext findServerSslContext(VrtChannel server) {
        SslContext sslContext = SslUtils.getSslContextFromPath(server, "tls-detect", "tls");
        return sslContext != null ? sslContext : SslUtils.getSslContextFromRoot(server);
    }

    // ========================= Helper: Test Servlets =========================

    private static HttpServlet echoServlet(String responseBody) {
        return new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                resp.write(responseBody);
            }

            @Override
            protected void doPost(ServletRequest req, ServletResponse resp) throws IOException {
                resp.setContentType("text/plain");
                String reqBody = req.getBodyAsString();
                resp.write("echo:" + reqBody);
            }
        };
    }

    // =================================================================
    //  Test 1: Plain HTTP/1.1 GET request — full pipeline test
    // =================================================================

    @Test
    public void test_plainHttp_getRequest() throws Throwable {
        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.addServlet("/test", echoServlet("Hello World"));
        httpServer.initServletContext();

        ProtoInitializer serverInit = httpServer.createHttpInitializer(false);

        NetManager neta = new NetManager();
        try {
            VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), serverInit, VrtSoConfig.asServer());
            VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            }, VrtSoConfig.asClient());

            VrtTransfer transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            transfer.linkTo(server, client, VrtTransfer.duplicate());

            Queue<String> clientRcv = subscribeAsString(client);

            String request = "GET /test HTTP/1.1\r\nHost: localhost\r\n\r\n";
            client.sendData(toByteBuf(request)).get();
            Thread.sleep(500);

            String response = collectStrings(clientRcv);
            assertTrue("Response should contain 200 OK: " + response, response.contains("200 OK"));
            assertTrue("Response should contain body: " + response, response.contains("Hello World"));
        } finally {
            neta.shutdown();
        }
    }

    // =================================================================
    //  Test 2: Plain HTTP/1.1 POST with body
    // =================================================================

    @Test
    public void test_plainHttp_postWithBody() throws Throwable {
        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.addServlet("/echo", echoServlet(""));
        httpServer.initServletContext();

        ProtoInitializer serverInit = httpServer.createHttpInitializer(false);

        NetManager neta = new NetManager();
        try {
            VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), serverInit, VrtSoConfig.asServer());
            VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            }, VrtSoConfig.asClient());

            VrtTransfer transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            transfer.linkTo(server, client, VrtTransfer.duplicate());

            Queue<String> clientRcv = subscribeAsString(client);

            String body = "test-data-123";
            String request = "POST /echo HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: text/plain\r\n" + "Content-Length: " + body.length() + "\r\n" + "\r\n" + body;
            client.sendData(toByteBuf(request)).get();
            Thread.sleep(500);

            String response = collectStrings(clientRcv);
            assertTrue("Response should contain 200 OK: " + response, response.contains("200 OK"));
            assertTrue("Response should contain echoed body: " + response, response.contains("echo:test-data-123"));
        } finally {
            neta.shutdown();
        }
    }

    // =================================================================
    //  Test 3: HTTPS + ALPN → http/1.1 — full request/response via SSL
    // =================================================================

    @Test
    public void test_httpsAlpn_http11_fullRequestResponse() throws Throwable {
        SslConfig serverConf = sslConfig();

        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.ssl(serverConf);
        httpServer.http2(false);   // disable h2, only http/1.1 available
        httpServer.addServlet("/secure", echoServlet("Secure Hello"));
        httpServer.initServletContext();
        httpServer.configureAlpn();

        ProtoInitializer serverInit = httpServer.createHttpsAlpnInitializer();

        // Client SSL config
        SslConfig clientConf = sslConfig();
        clientConf.setAppProtocol(new String[] { "http/1.1" });

        ProtoInitializer clientInit = ctx -> {
            ctx.addLast("SSL", new SslDuplexer(clientConf));
        };

        NetManager neta = new NetManager();
        try {
            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtAddr = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtAddr, serverInit, config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtAddr, clientInit, config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();

            Thread.sleep(1000); // wait for SSL handshake

            // Verify ALPN negotiation result
            SslContext serverSSL = findServerSslContext(server);
            assertNotNull("Server SSL context should exist", serverSSL);
            assertTrue("Server SSL should be ready", serverSSL.isReady());
            assertEquals("ALPN should be http/1.1", "http/1.1", serverSSL.getApplicationProtocol());

            // Send HTTP/1.1 request through SSL
            Queue<String> clientRcv = subscribeInboundAsString(client);

            String request = "GET /secure HTTP/1.1\r\nHost: localhost\r\n\r\n";
            client.sendData(toByteBuf(request));
            Thread.sleep(1000);

            // Collect response (decrypted by client SSL layer → raw HTTP text)
            String response = collectStrings(clientRcv);
            assertTrue("Response should contain 200 OK: " + response, response.contains("200 OK"));
            assertTrue("Response should contain body: " + response, response.contains("Secure Hello"));
        } finally {
            neta.shutdown();
        }
    }

    // =================================================================
    //  Test 4: HTTPS + ALPN → h2 route selection
    //  Verifies that when client offers h2 + http/1.1, server prefers h2.
    // =================================================================

    @Test
    public void test_httpsAlpn_h2_routeSelection() throws Throwable {
        SslConfig serverConf = sslConfig();

        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.ssl(serverConf);
        httpServer.http2(true);
        httpServer.addServlet("/test", echoServlet("H2 test"));
        httpServer.initServletContext();
        httpServer.configureAlpn();

        ProtoInitializer serverInit = httpServer.createHttpsAlpnInitializer();

        // Client offers h2 + http/1.1
        SslConfig clientConf = sslConfig();
        clientConf.setAppProtocol(new String[] { "h2", "http/1.1" });

        ProtoInitializer clientInit = ctx -> {
            ctx.addLast("SSL", new SslDuplexer(clientConf));
        };

        NetManager neta = new NetManager();
        try {
            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtAddr = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtAddr, serverInit, config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtAddr, clientInit, config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();
            Thread.sleep(1000);

            // Verify ALPN selected h2
            SslContext serverSSL = findServerSslContext(server);
            assertNotNull("Server SSL context should exist", serverSSL);
            assertEquals("ALPN should select h2", "h2", serverSSL.getApplicationProtocol());

            SslContext clientSSL = client.findProtoContext(SslContext.class);
            assertNotNull("Client SSL context should exist", clientSSL);
            assertEquals("Client ALPN should be h2", "h2", clientSSL.getApplicationProtocol());
        } finally {
            neta.shutdown();
        }
    }

    // =================================================================
    //  Test 5: HTTPS + ALPN → spdy/3.1 route selection
    //  With h2 disabled, server should prefer spdy/3.1 over http/1.1.
    //  NOTE: SPDY protocol is not implemented in NetaHttpServer, test ignored.
    // =================================================================

    @Ignore("SPDY protocol is not implemented in NetaHttpServer")
    @Test
    public void test_httpsAlpn_spdy_routeSelection() throws Throwable {
        SslConfig serverConf = sslConfig();

        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.ssl(serverConf);
        httpServer.http2(false);   // disable h2
        // httpServer.spdy(true); // SPDY not implemented, test is @Ignore'd
        httpServer.addServlet("/test", echoServlet("SPDY test"));
        httpServer.initServletContext();
        httpServer.configureAlpn();

        ProtoInitializer serverInit = httpServer.createHttpsAlpnInitializer();

        // Client offers spdy/3.1 + http/1.1
        SslConfig clientConf = sslConfig();
        clientConf.setAppProtocol(new String[] { "spdy/3.1", "http/1.1" });

        ProtoInitializer clientInit = ctx -> {
            ctx.addLast("SSL", new SslDuplexer(clientConf));
        };

        NetManager neta = new NetManager();
        try {
            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtAddr = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtAddr, serverInit, config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtAddr, clientInit, config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();
            Thread.sleep(1000);

            // Verify ALPN selected spdy/3.1
            SslContext serverSSL = findServerSslContext(server);
            assertNotNull("Server SSL context should exist", serverSSL);
            assertEquals("ALPN should select spdy/3.1", "spdy/3.1", serverSSL.getApplicationProtocol());
        } finally {
            neta.shutdown();
        }
    }

    // =================================================================
    //  Test 6: HTTPS + ALPN → http/1.1 fallback
    //  All protocols enabled, but client only offers http/1.1.
    // =================================================================

    @Test
    public void test_httpsAlpn_fallbackToHttp11() throws Throwable {
        SslConfig serverConf = sslConfig();

        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.ssl(serverConf);
        httpServer.http2(true);
        httpServer.addServlet("/test", echoServlet("Fallback"));
        httpServer.initServletContext();
        httpServer.configureAlpn();

        ProtoInitializer serverInit = httpServer.createHttpsAlpnInitializer();

        // Client only offers http/1.1
        SslConfig clientConf = sslConfig();
        clientConf.setAppProtocol(new String[] { "http/1.1" });

        ProtoInitializer clientInit = ctx -> {
            ctx.addLast("SSL", new SslDuplexer(clientConf));
        };

        NetManager neta = new NetManager();
        try {
            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtAddr = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtAddr, serverInit, config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtAddr, clientInit, config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();
            Thread.sleep(1000);

            // Verify ALPN falls back to http/1.1
            SslContext serverSSL = findServerSslContext(server);
            assertNotNull("Server SSL context should exist", serverSSL);
            assertEquals("ALPN should fallback to http/1.1", "http/1.1", serverSSL.getApplicationProtocol());

            // Also verify full request/response works through fallback
            Queue<String> clientRcv = subscribeInboundAsString(client);

            String request = "GET /test HTTP/1.1\r\nHost: localhost\r\n\r\n";
            client.sendData(toByteBuf(request));
            Thread.sleep(1000);

            String response = collectStrings(clientRcv);
            assertTrue("Response should contain 200 OK: " + response, response.contains("200 OK"));
            assertTrue("Response should contain body: " + response, response.contains("Fallback"));
        } finally {
            neta.shutdown();
        }
    }

    // =================================================================
    //  Test 7: Multiple servlets routing — verify correct dispatch
    // =================================================================

    @Test
    public void test_multipleServlets_routing() throws Throwable {
        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.addServlet("/api/users", echoServlet("users-data"));
        httpServer.addServlet("/api/items", echoServlet("items-data"));
        httpServer.addServlet("/home", echoServlet("home-page"));
        httpServer.initServletContext();

        ProtoInitializer serverInit = httpServer.createHttpInitializer(false);

        NetManager neta = new NetManager();
        try {
            VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), serverInit, VrtSoConfig.asServer());
            VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            }, VrtSoConfig.asClient());

            VrtTransfer transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            transfer.linkTo(server, client, VrtTransfer.duplicate());

            Queue<String> clientRcv = subscribeAsString(client);

            // Request 1: /api/users
            client.sendData(toByteBuf("GET /api/users HTTP/1.1\r\nHost: localhost\r\n\r\n")).get();
            Thread.sleep(300);
            String resp1 = collectStrings(clientRcv);
            assertTrue("users response: " + resp1, resp1.contains("users-data"));

            // Request 2: /home
            client.sendData(toByteBuf("GET /home HTTP/1.1\r\nHost: localhost\r\n\r\n")).get();
            Thread.sleep(300);
            String resp2 = collectStrings(clientRcv);
            assertTrue("home response: " + resp2, resp2.contains("home-page"));

            // Request 3: /api/items
            client.sendData(toByteBuf("GET /api/items HTTP/1.1\r\nHost: localhost\r\n\r\n")).get();
            Thread.sleep(300);
            String resp3 = collectStrings(clientRcv);
            assertTrue("items response: " + resp3, resp3.contains("items-data"));
        } finally {
            neta.shutdown();
        }
    }

    // =================================================================
    //  Test 8: 404 for unmapped path
    // =================================================================

    @Test
    public void test_404_noServlet() throws Throwable {
        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.addServlet("/exists", echoServlet("found"));
        httpServer.initServletContext();

        ProtoInitializer serverInit = httpServer.createHttpInitializer(false);

        NetManager neta = new NetManager();
        try {
            VrtChannel server = (VrtChannel) neta.connectSync(new VrtSocketAddress(1), serverInit, VrtSoConfig.asServer());
            VrtChannel client = (VrtChannel) neta.connectSync(new VrtSocketAddress(2), ctx -> {
            }, VrtSoConfig.asClient());

            VrtTransfer transfer = new VrtTransfer(neta);
            transfer.linkTo(client, server, VrtTransfer.duplicate());
            transfer.linkTo(server, client, VrtTransfer.duplicate());

            Queue<String> clientRcv = subscribeAsString(client);

            client.sendData(toByteBuf("GET /notfound HTTP/1.1\r\nHost: localhost\r\n\r\n")).get();
            Thread.sleep(500);

            String response = collectStrings(clientRcv);
            assertTrue("Should contain 404: " + response, response.contains("404"));
        } finally {
            neta.shutdown();
        }
    }

    // =================================================================
    //  Test 9: ALPN protocol preference order — h2 > http/1.1
    // =================================================================

    @Test
    public void test_httpsAlpn_preferenceOrder() throws Throwable {
        SslConfig serverConf = sslConfig();

        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.ssl(serverConf);
        httpServer.http2(true);
        httpServer.addServlet("/test", echoServlet("test"));
        httpServer.initServletContext();
        httpServer.configureAlpn();

        ProtoInitializer serverInit = httpServer.createHttpsAlpnInitializer();

        // Client offers all protocols — server should select h2 (highest priority)
        SslConfig clientConf = sslConfig();
        clientConf.setAppProtocol(new String[] { "http/1.1", "h2" });

        ProtoInitializer clientInit = ctx -> {
            ctx.addLast("SSL", new SslDuplexer(clientConf));
        };

        NetManager neta = new NetManager();
        try {
            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtAddr = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtAddr, serverInit, config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtAddr, clientInit, config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();
            Thread.sleep(1000);

            // h2 should be selected (highest priority in server preferences)
            SslContext serverSSL = findServerSslContext(server);
            assertNotNull("Server SSL context should exist", serverSSL);
            assertEquals("Server should prefer h2 over http/1.1", "h2", serverSSL.getApplicationProtocol());
        } finally {
            neta.shutdown();
        }
    }

    // =================================================================
    //  Test 10: Verify HTTP/1.1 over HTTPS produces correct response headers
    // =================================================================

    @Test
    public void test_httpsAlpn_http11_responseHeaders() throws Throwable {
        SslConfig serverConf = sslConfig();

        NetaHttpServer httpServer = new NetaHttpServer();
        httpServer.ssl(serverConf);
        httpServer.http2(false);
        httpServer.serverName("Test-Server");
        httpServer.addServlet("/headers", new HttpServlet() {
            @Override
            protected void doGet(ServletRequest req, ServletResponse resp) throws IOException {
                resp.setContentType("application/json");
                resp.write("{\"status\":\"ok\"}");
            }
        });
        httpServer.initServletContext();
        httpServer.configureAlpn();

        ProtoInitializer serverInit = httpServer.createHttpsAlpnInitializer();

        SslConfig clientConf = sslConfig();
        clientConf.setAppProtocol(new String[] { "http/1.1" });

        ProtoInitializer clientInit = ctx -> {
            ctx.addLast("SSL", new SslDuplexer(clientConf));
        };

        NetManager neta = new NetManager();
        try {
            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtAddr = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtAddr, serverInit, config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtAddr, clientInit, config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();
            Thread.sleep(1000);

            Queue<String> clientRcv = subscribeInboundAsString(client);

            client.sendData(toByteBuf("GET /headers HTTP/1.1\r\nHost: localhost\r\n\r\n"));
            Thread.sleep(1000);

            String response = collectStrings(clientRcv);
            assertTrue("Should contain 200 OK: " + response, response.contains("200 OK"));
            assertTrue("Should contain content-type: " + response, response.toLowerCase().contains("content-type: application/json"));
            assertTrue("Should contain JSON body: " + response, response.contains("{\"status\":\"ok\"}"));
        } finally {
            neta.shutdown();
        }
    }
}
