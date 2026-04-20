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
package net.hasor.neta.example.httpserver;

import java.io.InputStream;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import net.hasor.neta.codec.http.cors.CorsConfig;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.neta.codec.ssl.SslProtocol;
import net.hasor.neta.codec.ssl.SslUtils;
import net.hasor.nhttp.server.NetaHttpServer;
import net.hasor.nhttp.server.connector.BackpressureStrategy;

/**
 * HTTP server example based on the new {@link NetaHttpServer} API.
 * <ul>
 *   <li>HTTP/1.1 on port 8080 (plain text)</li>
 *   <li>HTTPS on port 8443 with ALPN negotiation (HTTP/2, HTTP/1.1)</li>
 * </ul>
 * <p>
 * Access with Google Chrome:
 * <ul>
 *   <li>{@code http://localhost:8080/} — HTTP/1.1</li>
 *   <li>{@code https://localhost:8443/} — HTTPS (Chrome will auto-negotiate h2 or http/1.1)</li>
 * </ul>
 * <p>
 * Note: A local Root CA is generated in {@code src/main/resources/ssl/} and used to sign the server cert.
 * For Chrome to accept it, trust the CA once (macOS):
 * <pre>
 *   security add-trusted-cert -r trustRoot -k ~/Library/Keychains/login.keychain-db &lt;path-to&gt;/ssl/neta-ca.crt
 * </pre>
 * Or type "thisisunsafe" on Chrome's certificate warning page.
 * @author 赵永春 (zyc@hasor.net)
 */
public class HttpServerMain {
    private static final int    HTTP_PORT               = 8080;
    private static final int    HTTPS_PORT              = 8443;
    private static final int    MAX_CONTENT_LENGTH      = 128 * 1024 * 1024;
    private static final int    BODY_QUEUE_CAPACITY     = 64;
    private static final int    SESSION_TIMEOUT_SECONDS = 1800;
    private static final String SERVER_NAME             = "Neta-Example-HTTP-Server/2.0";

    public static void main(String[] args) throws Exception {
        System.out.println("==========================================================");
        System.out.println("  Neta HTTP Server Example");
        System.out.println("==========================================================");

        // Step 1: Load or generate TLS certificate (signed by persistent local CA)
        //   To force renewal, replace generate() with SelfSignedCertGenerator.renew("localhost")
        System.out.println("[1/4] Loading TLS certificate...");
        SelfSignedCertGenerator.CertFiles certs = SelfSignedCertGenerator.generate("localhost");
        System.out.println("  Server Cert: " + certs.certFile.getAbsolutePath());
        System.out.println("  Private Key: " + certs.keyFile.getAbsolutePath());
        System.out.println("  CA Cert:     " + certs.caFile.getAbsolutePath());

        if (certs.caCreated) {
            System.out.println();
            System.out.println("  *** Chrome HTTPS Setup (one-time) ***");
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac")) {
                System.out.println("  Trust the CA certificate in macOS Keychain:");
                System.out.println("    security add-trusted-cert -r trustRoot \\");
                System.out.println("      -k ~/Library/Keychains/login.keychain-db \\");
                System.out.println("      " + certs.caFile.getAbsolutePath());
                System.out.println("  Then restart Chrome.");
            } else {
                System.out.println("  Import as trusted CA: " + certs.caFile.getAbsolutePath());
            }
            System.out.println("  Quick bypass: type 'thisisunsafe' on Chrome's warning page.");
        }

        // Step 2: Configure SSL with ALPN support
        System.out.println("[2/4] Configuring SSL/TLS with ALPN...");

        // Load cert chain and private key directly from src/main/resources/ssl/
        X509Certificate[] certChain;
        PrivateKey privateKey;
        try (InputStream in = Files.newInputStream(certs.certFile.toPath())) {
            certChain = SslUtils.toX509Certificates(in);
        }
        try (InputStream in = Files.newInputStream(certs.keyFile.toPath())) {
            privateKey = SslUtils.toPrivateKey(in, null);
        }

        // Build KeyStore and KeyManagerFactory directly
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        SslUtils.loadKeyStore(keyStore, certChain, privateKey, new char[0]);

        SslConfig sslConfig = new SslConfig();
        sslConfig.setKeyStore(keyStore);
        sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_3, SslProtocol.TLS_v1_2 });

        // Step 3: Configure CORS for development
        CorsConfig corsConfig = CorsConfig.builder().allowMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")//
                .allowAnyOrigin()     //
                .allowHeaders("*")    //
                .maxAge(3600) //
                .build();

        // Step 4: Create and configure the servers
        System.out.println("[3/4] Creating HTTP and HTTPS servers...");
        NetaHttpServer httpServer = buildServer(corsConfig, null);
        NetaHttpServer httpsServer = buildServer(corsConfig, sslConfig);
        configureRoutes(httpServer);
        configureRoutes(httpsServer);

        registerShutdownHook(httpServer, httpsServer);

        // Step 5: Start listeners
        System.out.println("[4/4] Starting server...");

        httpServer.start(HTTP_PORT);
        httpsServer.startSSL(HTTPS_PORT);

        System.out.println();
        System.out.println("==========================================================");
        System.out.println("  Server is running! Open in Google Chrome:");
        System.out.println();
        System.out.println("  HTTP/1.1:  http://localhost:" + HTTP_PORT + "/");
        System.out.println("  HTTPS:     https://localhost:" + HTTPS_PORT + "/");
        System.out.println("             (Chrome auto-negotiates h2 via ALPN)");
        System.out.println();
        System.out.println("  Enabled Protocols:");
        System.out.println("    - HTTP/1.1  (TCP port " + HTTP_PORT + ")");
        System.out.println("    - HTTPS     (TCP port " + HTTPS_PORT + " with ALPN)");
        System.out.println("      - h2        (HTTP/2)");
        System.out.println("      - http/1.1  (fallback)");
        System.out.println("    - h3        (temporarily disabled)");
        System.out.println();
        System.out.println("  API endpoint:      /api/info");
        System.out.println("  Form submit:       /api/form");
        System.out.println("  File upload:       /api/upload");
        System.out.println("  WebSocket echo:    /ws/echo");
        System.out.println("  WebSocket chat UI: /pages/chat.html");
        System.out.println();
        System.out.println("  Tip: If Chrome shows a cert warning, type 'thisisunsafe'");
        System.out.println("==========================================================");

        httpsServer.await();
    }

    private static NetaHttpServer buildServer(CorsConfig corsConfig, SslConfig sslConfig) {
        NetaHttpServer server = new NetaHttpServer();
        server.serverName(SERVER_NAME).cors(corsConfig).http2(true).maxContentLength(MAX_CONTENT_LENGTH).bodyQueueCapacity(BODY_QUEUE_CAPACITY).backpressureStrategy(BackpressureStrategy.limitedWait(250L)).sessionTimeout(SESSION_TIMEOUT_SECONDS);

        if (sslConfig != null) {
            server.ssl(sslConfig);
        }
        return server;
    }

    private static void configureRoutes(NetaHttpServer server) {
        server.setDefaultServlet(new StaticFileServlet("static"));
        server.addServlet("/api/info", new ProtocolInfoServlet(HTTP_PORT, HTTPS_PORT, true, false));
        server.addServlet("/api/form", new FormSubmitServlet());
        server.addServlet("/api/upload", new FileUploadServlet());
        server.addWebSocket("/ws/echo", new EchoWebSocketHandler());
    }

    private static void registerShutdownHook(NetaHttpServer httpServer, NetaHttpServer httpsServer) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            httpsServer.stop();
            httpServer.stop();
        }, "neta-http-server-shutdown"));
    }
}
