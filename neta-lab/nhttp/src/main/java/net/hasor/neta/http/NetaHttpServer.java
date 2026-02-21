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
package net.hasor.neta.http;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.udp.UdpSoConfig;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.constant.HttpHeaderNames;
import net.hasor.neta.codec.http.constant.HttpHeaderValues;
import net.hasor.neta.codec.http.cors.CorsConfig;
import net.hasor.neta.codec.http.cors.CorsUtil;
import net.hasor.neta.codec.http.websocket.WebSocketFrame;
import net.hasor.neta.codec.http.websocket.WebSocketServerHandshaker;
import net.hasor.neta.codec.http2.Http2ServerDuplexe;
import net.hasor.neta.codec.http3.Http3ServerDuplexe;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.neta.codec.ssl.SslContext;
import net.hasor.neta.codec.ssl.SslDuplexer;
import net.hasor.neta.http.internal.*;

/**
 * Main HTTP server class built on the Neta AIO framework.
 * Supports HTTP/1.1, HTTP/2 (h2), HTTP/3, QUIC, HTTPS (TLS/SSL), WebSocket, and CORS.
 * <p>
 * Multi-protocol support is achieved via TLS ALPN (Application-Layer Protocol Negotiation).
 * When HTTPS is enabled, the server automatically negotiates the best protocol with the client:
 * <ul>
 *   <li>{@code h2} → HTTP/2 (RFC 9113)</li>
 *   <li>{@code http/1.1} → HTTP/1.1 (default fallback)</li>
 * </ul>
 * HTTP/3 over QUIC (UDP) can be started separately on the same port, advertised via {@code Alt-Svc} header.
 * </p>
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * NetaHttpServer server = new NetaHttpServer();
 * server.addServlet("/hello", new HttpServlet() {
 *     protected void doGet(ServletRequest req, ServletResponse resp) throws IOException {
 *         resp.setContentType("text/plain");
 *         resp.write("Hello, World!");
 *     }
 * });
 * server.start(8080);              // HTTP/1.1 + h2c (HTTP/2 Prior Knowledge)
 * server.startSSL(8443);           // HTTPS with ALPN (h2, http/1.1)
 * server.startHttp3(8443);         // HTTP/3 over QUIC (UDP)
 * }</pre>
 * @author 赵永春 (zyc@hasor.net)
 */
public class NetaHttpServer {
    private static final Logger                logger               = Logger.getLogger(NetaHttpServer.class);
    private final        NetManager            netManager           = new NetManager();
    private final        ServletDispatcher     dispatcher           = new ServletDispatcher();
    private final        DefaultSessionManager sessionManager       = new DefaultSessionManager();
    private              DefaultServletContext servletContext;
    // Configuration
    private              String                serverName           = "Neta-HTTP";
    private              String                contextPath          = "";
    private              int                   maxContentLength     = 1048576; // 1MB
    private              int                   maxInitialLineLength = 4096;
    private              int                   maxHeaderSize        = 8192;
    private              int                   maxChunkSize         = 8192;
    private              SslConfig             sslConfig;
    private              CorsConfig            corsConfig;
    private              boolean               http2Enabled         = true;
    private              boolean               http3Enabled         = true;
    private              int                   http3Port            = -1;       // for Alt-Svc header
    // State
    private final        CountDownLatch        stopLatch            = new CountDownLatch(1);
    private              NetListen             httpListen;
    private              NetListen             httpsListen;
    private              NetListen             http3Listen;
    private volatile     boolean               running              = false;

    // ================================
    //  Configuration Methods
    // ================================

    /** Sets the server name displayed in the Server header */
    public NetaHttpServer serverName(String serverName) {
        this.serverName = serverName;
        return this;
    }

    /** Sets the context path prefix for all servlets */
    public NetaHttpServer contextPath(String contextPath) {
        this.contextPath = contextPath != null ? contextPath : "";
        return this;
    }

    /** Sets the maximum HTTP request content length (default 1MB) */
    public NetaHttpServer maxContentLength(int maxContentLength) {
        this.maxContentLength = maxContentLength;
        return this;
    }

    /** Configures SSL/TLS for HTTPS support */
    public NetaHttpServer ssl(SslConfig sslConfig) {
        this.sslConfig = sslConfig;
        return this;
    }

    /** Configures CORS (Cross-Origin Resource Sharing) */
    public NetaHttpServer cors(CorsConfig corsConfig) {
        this.corsConfig = corsConfig;
        return this;
    }

    /** Sets the session timeout in seconds */
    public NetaHttpServer sessionTimeout(int seconds) {
        this.sessionManager.setDefaultMaxInactiveInterval(seconds);
        return this;
    }

    /** Sets the maximum initial line length for HTTP request parsing */
    public NetaHttpServer maxInitialLineLength(int maxInitialLineLength) {
        this.maxInitialLineLength = maxInitialLineLength;
        return this;
    }

    /** Sets the maximum header size for HTTP request parsing */
    public NetaHttpServer maxHeaderSize(int maxHeaderSize) {
        this.maxHeaderSize = maxHeaderSize;
        return this;
    }

    /** Sets the maximum chunk size for HTTP content delivery */
    public NetaHttpServer maxChunkSize(int maxChunkSize) {
        this.maxChunkSize = maxChunkSize;
        return this;
    }

    /** Enables or disables HTTP/2 support over HTTPS (default: enabled) */
    public NetaHttpServer http2(boolean enabled) {
        this.http2Enabled = enabled;
        return this;
    }

    /** Enables or disables HTTP/3 over QUIC support (default: enabled) */
    public NetaHttpServer http3(boolean enabled) {
        this.http3Enabled = enabled;
        return this;
    }

    // ================================
    //  Registration Methods
    // ================================

    /** Registers a servlet at the given URL pattern */
    public NetaHttpServer addServlet(String urlPattern, HttpServlet servlet) {
        this.dispatcher.addServlet(urlPattern, servlet);
        return this;
    }

    /** Registers a filter at the given URL pattern */
    public NetaHttpServer addFilter(String urlPattern, Filter filter) {
        this.dispatcher.addFilter(urlPattern, filter);
        return this;
    }

    /** Registers a WebSocket handler at the given path */
    public NetaHttpServer addWebSocket(String path, WebSocketHandler handler) {
        this.dispatcher.addWebSocket(path, handler);
        return this;
    }

    /** Sets the default servlet for unmatched paths */
    public NetaHttpServer setDefaultServlet(HttpServlet defaultServlet) {
        this.dispatcher.setDefaultServlet(defaultServlet);
        return this;
    }

    // ================================
    //  Lifecycle Methods
    // ================================

    /** Starts the HTTP server on the given port */
    public NetaHttpServer start(int port) throws Exception {
        return start(new InetSocketAddress(port));
    }

    /** Starts the HTTP server on the given address */
    public NetaHttpServer start(InetSocketAddress address) throws Exception {
        if (this.running) {
            throw new IllegalStateException("Server is already running");
        }

        initServletContext();

        // create HTTP pipeline initializer
        ProtoInitializer httpInitializer = createHttpInitializer(false);
        this.httpListen = this.netManager.bind(address, httpInitializer, SoConfig.TCP());
        this.running = true;

        String httpProtos = this.http2Enabled ? "http/1.1, h2c" : "http/1.1";
        logger.info("Neta HTTP server started on " + address + " (protocols: " + httpProtos + ")");
        return this;
    }

    /** Starts the HTTPS server on the given port (requires SSL configuration) */
    public NetaHttpServer startSSL(int port) throws Exception {
        return startSSL(new InetSocketAddress(port));
    }

    /** Starts the HTTPS server on the given address with ALPN multi-protocol support */
    public NetaHttpServer startSSL(InetSocketAddress address) throws Exception {
        Objects.requireNonNull(this.sslConfig, "SSL configuration is required for HTTPS. Call ssl() first.");

        initServletContext();

        // Configure ALPN protocols and selector on SslConfig
        configureAlpn();

        ProtoInitializer httpsInitializer = createHttpsAlpnInitializer();
        this.httpsListen = this.netManager.bind(address, httpsInitializer, SoConfig.TCP());
        this.running = true;

        logger.info("Neta HTTPS server started on " + address + " (protocols: " + getEnabledProtocols() + ")");
        return this;
    }

    /** Starts the HTTP/3 server over QUIC (UDP) on the given port */
    public NetaHttpServer startHttp3(int port) throws Exception {
        return startHttp3(new InetSocketAddress(port));
    }

    /** Starts the HTTP/3 server over QUIC (UDP) on the given address */
    public NetaHttpServer startHttp3(InetSocketAddress address) throws Exception {
        initServletContext();

        this.http3Port = address.getPort();

        ProtoInitializer http3Initializer = createHttp3Initializer();
        UdpSoConfig udpConfig = SoConfig.UDP();
        udpConfig.setRcvPacketSize(65535); // max UDP packet size for QUIC
        this.http3Listen = this.netManager.bind(address, http3Initializer, udpConfig);
        this.running = true;

        logger.info("Neta HTTP/3 (QUIC) server started on UDP " + address);
        return this;
    }

    /** Stops the server and releases all resources */
    public void stop() {
        if (!this.running) {
            return;
        }
        this.running = false;
        this.stopLatch.countDown();

        try {
            if (this.httpListen != null) {
                this.httpListen.close();
            }
            if (this.httpsListen != null) {
                this.httpsListen.close();
            }
            if (this.http3Listen != null) {
                this.http3Listen.close();
            }
            this.netManager.shutdown();
        } catch (Exception e) {
            logger.warn("Error closing server", e);
        }

        this.dispatcher.destroy();
        logger.info("Neta HTTP server stopped");
    }

    /** Blocks the current thread until the server is stopped */
    public void await() throws InterruptedException {
        this.stopLatch.await();
    }

    /** Returns true if the server is running */
    public boolean isRunning() {
        return this.running;
    }

    /** Returns the SessionManager */
    public SessionManager getSessionManager() {
        return this.sessionManager;
    }

    /** Returns the NetManager */
    public NetManager getNetManager() {
        return this.netManager;
    }

    // ================================
    //  Internal Pipeline Setup
    // ================================

    /** Initialize servlet context without starting any listeners. Package-private for testing. */
    void initServletContext() throws Exception {
        if (this.servletContext == null) {
            this.servletContext = new DefaultServletContext(this.serverName, this.contextPath, this.corsConfig, this.sessionManager);
            this.dispatcher.init(this.servletContext);
        }
    }

    /** Configures ALPN protocols and selector on the SslConfig */
    void configureAlpn() {
        java.util.List<String> protocols = new java.util.ArrayList<>();
        if (this.http2Enabled) {
            protocols.add("h2");
        }
        protocols.add("http/1.1"); // always available as fallback

        this.sslConfig.setAppProtocol(protocols.toArray(new String[0]));
        this.sslConfig.setAppProtocolSelector((channel, sslEngine, clientProtocols) -> {
            // Prefer h2 > http/1.1
            if (this.http2Enabled && clientProtocols.contains("h2")) {
                return "h2";
            }
            return "http/1.1";
        });
    }

    /** Returns a human-readable list of enabled protocols */
    private String getEnabledProtocols() {
        java.util.List<String> list = new java.util.ArrayList<>();
        if (this.http2Enabled)
            list.add("h2");
        if (this.http3Enabled) {
            list.add("h3(UDP)");
        }
        list.add("http/1.1");
        return String.join(", ", list);
    }

    /** Creates pipeline initializer for plain HTTP/1.1 (no TLS), with h2c (HTTP/2 cleartext) support */
    ProtoInitializer createHttpInitializer(boolean secure) {
        return ctx -> {
            // SSL layer (for HTTPS only)
            if (secure && this.sslConfig != null) {
                ctx.addLast("ssl", new SslDuplexer(this.sslConfig));
            }

            // Protocol detection: route h2c (HTTP/2 Prior Knowledge) vs HTTP/1.1 vs invalid data
            // h2c preface starts with "PRI " (0x50 0x52 0x49 0x20), per RFC 9113 §3.4
            ProtoRoutingDuplexer.Builder<ByteBuf> httpDetect = ProtoRoutingDuplexer.newBuilder((context, rcvUp, rcvDown) -> {
                ByteBuf first = rcvUp.peekMessage();
                if (first != null && first.readableBytes() > 0) {
                    int firstByte = first.getByte(0) & 0xFF;

                    // Not an uppercase ASCII letter (A-Z) — reject
                    if (firstByte < 0x41 || firstByte > 0x5A) {
                        return "reject";
                    }

                    // Check for h2c prior knowledge: "PRI " (0x50 0x52 0x49 0x20)
                    if (this.http2Enabled && firstByte == 0x50 && first.readableBytes() >= 4) {
                        if ((first.getByte(1) & 0xFF) == 0x52           // R
                                && (first.getByte(2) & 0xFF) == 0x49    // I
                                && (first.getByte(3) & 0xFF) == 0x20) { // <space>
                            return "h2c";
                        }
                    }

                    // Regular HTTP method (GET, POST, PUT, DELETE, HEAD, OPTIONS, PATCH, CONNECT, TRACE)
                    return "http";
                }
                return null; // no data yet, wait
            });

            // h2c branch: HTTP/2 over cleartext (Prior Knowledge, RFC 9113 §3.4)
            if (this.http2Enabled) {
                httpDetect.branch("h2c", h2cBranch -> {
                    h2cBranch.addLast("h2-codec", new Http2ServerDuplexe(4096, this.maxHeaderSize));
                    h2cBranch.addLastDecoder("h2-aggregator", new HttpObjectAggregator(this.maxContentLength));
                    h2cBranch.addLastDecoder("h2-handler", new HttpDispatchHandler(secure));
                });
            }

            // HTTP/1.1 branch
            httpDetect.branch("http", httpBranch -> {
                httpBranch.addLast("http-codec", new HttpServerDuplexe(this.maxInitialLineLength, this.maxHeaderSize, this.maxChunkSize));
                httpBranch.addLastDecoder("http-aggregator", new HttpObjectAggregator(this.maxContentLength));
                httpBranch.addLastDecoder("http-handler", new HttpDispatchHandler(secure));
            });

            // Invalid data: log hex dump and close
            httpDetect.branch("reject", rejectBranch -> {
                rejectBranch.addLastDecoder("close", new ProtoHandler<ByteBuf, Object>() {
                    @Override
                    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<Object> dst) {
                        if (src.hasMore()) {
                            ByteBuf buf = src.peekMessage();
                            int len = Math.min(buf.readableBytes(), 32);
                            StringBuilder hex = new StringBuilder();
                            for (int i = 0; i < len; i++) {
                                if (i > 0)
                                    hex.append(' ');
                                hex.append(String.format("%02x", buf.getByte(i) & 0xFF));
                            }
                            long chId = context.getChannel().getChannelId();
                            logger.warn("reject(" + chId + ") unknown protocol, first " + len + " bytes: [" + hex + "]");
                        }
                        context.getChannel().close();
                        return ProtoStatus.Stop;
                    }
                });
            });

            ctx.addLast("http-detect", httpDetect.build(ctx));
        };
    }

    /**
     * Creates HTTPS pipeline initializer with TLS detection and ALPN-based protocol routing.
     * <p>
     * First detects whether the incoming connection is TLS or plaintext HTTP.
     * Plaintext requests receive a 301 redirect to HTTPS. TLS connections proceed
     * ALPN-based protocol negotiation (h2, http/1.1).
     * </p>
     * <pre>
     * [TLS Detect] ─── "tls"       ── [SslDuplexer] → [ALPN Router] ─── "h2"        ── [Http2] → [Agg] → [Handler]
     *              │                                                 └── "http/1.1" ── [Http]  → [Agg] → [Handler]
     *              └── "plaintext" ── [HttpCodec] → [Agg] → [RedirectHandler]
     * </pre>
     */
    ProtoInitializer createHttpsAlpnInitializer() {
        return ctx -> {
            // Outer routing: detect TLS vs plaintext by inspecting the first byte
            ProtoRoutingDuplexer.Builder<ByteBuf> tlsDetect = ProtoRoutingDuplexer.newBuilder((context, rcvUp, rcvDown) -> {
                ByteBuf first = rcvUp.peekMessage();
                if (first != null && first.readableBytes() > 0) {
                    int firstByte = first.getByte(0) & 0xFF;
                    // TLS record types: 0x14=ChangeCipherSpec, 0x15=Alert, 0x16=Handshake, 0x17=AppData
                    if (firstByte >= 0x14 && firstByte <= 0x17) {
                        return "tls";
                    }
                    // HTTP methods start with uppercase ASCII letters (A-Z: 0x41-0x5A)
                    if (firstByte >= 0x41 && firstByte <= 0x5A) {
                        return "plaintext";
                    }
                    // Unknown protocol (port scanner, garbage data, etc.) — close connection
                    return "unknown";
                }
                return null; // no data yet, wait
            });

            // TLS branch: SSL + ALPN routing
            tlsDetect.branch("tls", tlsBranch -> {
                tlsBranch.addLast("ssl", new SslDuplexer(this.sslConfig));

                // ALPN routing — select protocol based on negotiation result
                ProtoRoutingDuplexer.Builder<ByteBuf> alpnBuilder = ProtoRoutingDuplexer.newBuilder((context, rcvUp2, rcvDown2) -> {
                    SslContext sslCtx = context.context(SslContext.class);
                    if (sslCtx != null && sslCtx.isReady()) {
                        String proto = sslCtx.getApplicationProtocol();
                        if ("h2".equals(proto) && this.http2Enabled) {
                            return "h2";
                        }
                        return "http/1.1";
                    }
                    return null; // SSL handshake not complete, wait
                });

                // HTTP/2 branch
                if (this.http2Enabled) {
                    alpnBuilder.branch("h2", branch -> {
                        branch.addLast("h2-codec", new Http2ServerDuplexe(4096, this.maxHeaderSize));
                        branch.addLastDecoder("h2-aggregator", new HttpObjectAggregator(this.maxContentLength));
                        branch.addLastDecoder("h2-handler", new HttpDispatchHandler(true));
                    });
                }

                // HTTP/1.1 fallback branch (always present)
                alpnBuilder.branch("http/1.1", branch -> {
                    branch.addLast("http-codec", new HttpServerDuplexe(this.maxInitialLineLength, this.maxHeaderSize, this.maxChunkSize));
                    branch.addLastDecoder("http-aggregator", new HttpObjectAggregator(this.maxContentLength));
                    branch.addLastDecoder("http-handler", new HttpDispatchHandler(true));
                });

                tlsBranch.addLast("alpn-router", alpnBuilder.build(tlsBranch));
            });

            // Plaintext branch: decode HTTP and redirect to HTTPS
            tlsDetect.branch("plaintext", plainBranch -> {
                plainBranch.addLast("http-codec", new HttpServerDuplexe(this.maxInitialLineLength, this.maxHeaderSize, this.maxChunkSize));
                plainBranch.addLastDecoder("http-aggregator", new HttpObjectAggregator(this.maxContentLength));
                plainBranch.addLastDecoder("redirect-handler", new HttpsRedirectHandler());
            });

            // Unknown protocol branch: log hex dump and close connection
            tlsDetect.branch("unknown", unknownBranch -> {
                unknownBranch.addLastDecoder("close", new ProtoHandler<ByteBuf, Object>() {
                    @Override
                    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<Object> dst) {
                        if (src.hasMore()) {
                            ByteBuf buf = src.peekMessage();
                            int len = Math.min(buf.readableBytes(), 32);
                            StringBuilder hex = new StringBuilder();
                            for (int i = 0; i < len; i++) {
                                if (i > 0)
                                    hex.append(' ');
                                hex.append(String.format("%02x", buf.getByte(i) & 0xFF));
                            }
                            long chId = context.getChannel().getChannelId();
                            logger.warn("reject(" + chId + ") unknown protocol on HTTPS port, first " + len + " bytes: [" + hex + "]");
                        }
                        context.getChannel().close();
                        return ProtoStatus.Stop;
                    }
                });
            });

            ctx.addLast("tls-detect", tlsDetect.build(ctx));
        };
    }

    /**
     * Creates HTTP/3 over QUIC (UDP) pipeline initializer.
     * <pre>
     * [Http3ServerDuplexe (QUIC+HTTP/3)] → [Aggregator] → [Handler]
     * </pre>
     */
    ProtoInitializer createHttp3Initializer() {
        return ctx -> {
            // HTTP/3 codec (includes QUIC framing internally)
            ctx.addLast("h3-codec", new Http3ServerDuplexe(4096, this.maxHeaderSize));

            // HTTP aggregator
            ctx.addLastDecoder("h3-aggregator", new HttpObjectAggregator(this.maxContentLength));

            // Application handler
            ctx.addLastDecoder("h3-handler", new HttpDispatchHandler(true));
        };
    }

    // ================================
    //  HTTP Dispatch Handler
    // ================================

    /**
     * Internal ProtoHandler that receives FullHttpRequest objects from the pipeline
     * and dispatches them to servlets or handles WebSocket upgrade.
     */
    private class HttpDispatchHandler implements ProtoHandler<HttpObject, Object> {
        private final boolean secure;

        HttpDispatchHandler(boolean secure) {
            this.secure = secure;
        }

        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Object> dst) {
            while (src.hasMore()) {
                HttpObject msg = src.takeMessage();
                if (msg instanceof FullHttpRequest) {
                    handleRequest(context, (FullHttpRequest) msg);
                } else if (msg instanceof WebSocketFrame) {
                    handleWebSocketFrame(context, (WebSocketFrame) msg);
                }
            }
            return ProtoStatus.Next;
        }

        private void handleRequest(ProtoContext context, FullHttpRequest request) {
            NetChannel channel = (NetChannel) context.getChannel();

            try {
                // create servlet request/response
                DefaultServletRequest servletRequest = new DefaultServletRequest(request, channel, this.secure, sessionManager);
                DefaultServletResponse servletResponse = new DefaultServletResponse(channel);

                String path = servletRequest.getRequestPath();

                // check for WebSocket upgrade
                if (WebSocketServerHandshaker.isWebSocketUpgrade(request)) {
                    handleWebSocketUpgrade(context, request, servletRequest, channel, path);
                    return;
                }

                // CORS preflight handling
                if (corsConfig != null && corsConfig.isEnabled()) {
                    if (CorsUtil.isPreflightRequest(request)) {
                        FullHttpResponse corsResponse = new DefaultFullHttpResponse(request.protocolVersion(), net.hasor.neta.codec.http.constant.HttpStatus.NO_CONTENT);
                        CorsUtil.applyPreflightCorsHeaders(request, corsResponse, corsConfig);
                        corsResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO);
                        channel.sendData(corsResponse);
                        return;
                    }
                }

                // dispatch to servlet
                dispatcher.dispatch(servletRequest, servletResponse);

                // apply CORS headers to response if config exists
                // (done in commit since headers are added to the response object)

                // commit response if not yet committed
                if (!servletResponse.isCommitted()) {
                    // apply CORS simple headers
                    if (corsConfig != null && corsConfig.isEnabled()) {
                        String origin = request.headers().get(HttpHeaderNames.ORIGIN);
                        if (origin != null) {
                            applyCorsToServletResponse(request, servletResponse);
                        }
                    }
                    // advertise HTTP/3 via Alt-Svc header
                    if (http3Enabled && http3Port > 0) {
                        servletResponse.addHeader("Alt-Svc", "h3=\":" + http3Port + "\"; ma=86400");
                    }
                    servletResponse.commit();
                }

                // handle keep-alive
                handleKeepAlive(request, channel);

            } catch (Exception e) {
                logger.warn("Error handling HTTP request: " + request.uri(), e);
                sendErrorResponse(channel, 500, "Internal Server Error");
            }
        }

        private void applyCorsToServletResponse(FullHttpRequest request, DefaultServletResponse response) {
            String origin = request.headers().get(HttpHeaderNames.ORIGIN);
            if (origin == null || corsConfig == null) {
                return;
            }
            if (corsConfig.isAnyOrigin()) {
                response.setHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            } else if (corsConfig.isOriginAllowed(origin)) {
                response.setHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
                response.addHeader(HttpHeaderNames.VARY, HttpHeaderNames.ORIGIN);
            }
            if (corsConfig.isAllowCredentials()) {
                response.setHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            }
            if (!corsConfig.exposedHeaders().isEmpty()) {
                response.setHeader(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS, String.join(", ", corsConfig.exposedHeaders()));
            }
        }

        private void handleWebSocketUpgrade(ProtoContext context, FullHttpRequest request, DefaultServletRequest servletRequest, NetChannel channel, String path) {
            WebSocketHandler wsHandler = dispatcher.findWebSocketHandler(path);
            if (wsHandler == null) {
                sendErrorResponse(channel, 404, "No WebSocket handler for path: " + path);
                return;
            }

            try {
                // send handshake response
                FullHttpResponse handshakeResponse = WebSocketServerHandshaker.handshakeResponse(request);
                channel.sendData(handshakeResponse);

                // create WebSocket session
                DefaultWebSocketSession wsSession = new DefaultWebSocketSession(channel, path, servletRequest);

                // store WebSocket state in channel context
                context.context(WebSocketHandler.class, wsHandler);
                context.context(DefaultWebSocketSession.class, wsSession);

                // notify handler
                wsHandler.onOpen(wsSession);

            } catch (Exception e) {
                logger.warn("WebSocket upgrade failed", e);
                sendErrorResponse(channel, 500, "WebSocket upgrade failed");
            }
        }

        private void handleWebSocketFrame(ProtoContext context, WebSocketFrame frame) {
            WebSocketHandler wsHandler = context.context(WebSocketHandler.class);
            DefaultWebSocketSession wsSession = context.context(DefaultWebSocketSession.class);

            if (wsHandler == null || wsSession == null) {
                return;
            }

            try {
                switch (frame.opcode()) {
                    case TEXT:
                        ByteBuf textContent = frame.content();
                        String text = textContent.getString(textContent.readerIndex(), textContent.readableBytes(), StandardCharsets.UTF_8);
                        wsHandler.onMessage(wsSession, text);
                        break;
                    case BINARY:
                        ByteBuf binContent = frame.content();
                        byte[] data = new byte[binContent.readableBytes()];
                        binContent.readBytes(data);
                        wsHandler.onMessage(wsSession, data);
                        break;
                    case PING:
                        wsSession.sendPong();
                        break;
                    case PONG:
                        // ignore
                        break;
                    case CLOSE:
                        int statusCode = 1000;
                        String reason = "";
                        ByteBuf closeContent = frame.content();
                        if (closeContent != null && closeContent.readableBytes() >= 2) {
                            statusCode = closeContent.readUInt16();
                            if (closeContent.readableBytes() > 0) {
                                reason = closeContent.getString(closeContent.readerIndex(), closeContent.readableBytes(), StandardCharsets.UTF_8);
                            }
                        }
                        wsSession.markClosed();
                        wsHandler.onClose(wsSession, statusCode, reason);
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                logger.warn("Error handling WebSocket frame", e);
                wsHandler.onError(wsSession, e);
            }
        }

        private void handleKeepAlive(FullHttpRequest request, NetChannel channel) {
            String connection = request.headers().get(HttpHeaderNames.CONNECTION);
            boolean keepAlive;
            if (connection != null) {
                keepAlive = HttpHeaderValues.KEEP_ALIVE.equalsIgnoreCase(connection);
            } else {
                // HTTP/1.1 defaults to keep-alive
                keepAlive = request.protocolVersion().isKeepAliveDefault();
            }
            if (!keepAlive) {
                channel.close();
            }
        }

        private void sendErrorResponse(NetChannel channel, int code, String message) {
            try {
                String body = "<html><body><h1>" + code + " " + message + "</h1></body></html>";
                ByteBuf content = ByteBuf.wrap(body.getBytes(StandardCharsets.UTF_8));
                DefaultFullHttpResponse response = new DefaultFullHttpResponse(net.hasor.neta.codec.http.constant.HttpVersion.HTTP_1_1, net.hasor.neta.codec.http.constant.HttpStatus.valueOf(code), content);
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_HTML + "; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(content.readableBytes()));
                response.headers().set(HttpHeaderNames.SERVER, serverName);
                channel.sendData(response);
            } catch (Exception e) {
                logger.warn("Failed to send error response", e);
            }
        }

        @Override
        public void onClose(ProtoContext context) {
            // handle WebSocket close event
            WebSocketHandler wsHandler = context.context(WebSocketHandler.class);
            DefaultWebSocketSession wsSession = context.context(DefaultWebSocketSession.class);
            if (wsHandler != null && wsSession != null && wsSession.isOpen()) {
                wsSession.markClosed();
                wsHandler.onClose(wsSession, 1001, "Connection closed");
            }
        }

        @Override
        public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
            // Handle HTTP protocol exceptions with appropriate error responses
            if (e instanceof HttpProtocolException) {
                NetChannel channel = (NetChannel) context.getChannel();
                int statusCode = mapProtocolExceptionToStatusCode(e);
                String statusMessage = mapProtocolExceptionToMessage(e);
                logger.warn("HTTP protocol error: " + e.getMessage());
                sendErrorResponse(channel, statusCode, statusMessage);
                channel.close(); // close connection on protocol error
                eh.clear();
                return ProtoStatus.Stop;
            }

            // Handle WebSocket errors
            WebSocketHandler wsHandler = context.context(WebSocketHandler.class);
            DefaultWebSocketSession wsSession = context.context(DefaultWebSocketSession.class);
            if (wsHandler != null && wsSession != null) {
                wsHandler.onError(wsSession, e);
            }
            return ProtoStatus.Next;
        }

        /**
         * Maps HTTP protocol exceptions to appropriate HTTP status codes.
         * <ul>
         *   <li>{@code HttpInitialLineTooLongException} → 414 URI Too Long</li>
         *   <li>{@code HttpHeaderTooLargeException} → 431 Request Header Fields Too Large</li>
         *   <li>{@code HttpContentTooLargeException} → 413 Content Too Large</li>
         *   <li>{@code HttpMalformedRequestException} → 400 Bad Request</li>
         *   <li>Other {@code HttpProtocolException} → 400 Bad Request</li>
         * </ul>
         */
        private int mapProtocolExceptionToStatusCode(Throwable e) {
            if (e instanceof HttpInitialLineTooLongException) {
                return 414; // URI Too Long
            } else if (e instanceof HttpHeaderTooLargeException) {
                return 431; // Request Header Fields Too Large
            } else if (e instanceof HttpContentTooLargeException) {
                return 413; // Content Too Large
            } else {
                return 400; // Bad Request
            }
        }

        private String mapProtocolExceptionToMessage(Throwable e) {
            if (e instanceof HttpInitialLineTooLongException) {
                return "URI Too Long";
            } else if (e instanceof HttpHeaderTooLargeException) {
                return "Request Header Fields Too Large";
            } else if (e instanceof HttpContentTooLargeException) {
                return "Content Too Large";
            } else {
                return "Bad Request";
            }
        }
    }

    // ================================
    //  HTTPS Redirect Handler
    // ================================

    /**
     * Handler that responds to plaintext HTTP requests on the HTTPS port with a 301
     * redirect to the same URL using the HTTPS scheme. This handles the case where a
     * browser sends a plaintext HTTP request to an HTTPS port (e.g., navigating to
     * {@code http://localhost:8443/} instead of {@code https://localhost:8443/}).
     */
    private class HttpsRedirectHandler implements ProtoHandler<HttpObject, Object> {
        @Override
        public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> src, ProtoSndQueue<Object> dst) {
            while (src.hasMore()) {
                HttpObject msg = src.takeMessage();
                if (msg instanceof FullHttpRequest) {
                    FullHttpRequest request = (FullHttpRequest) msg;
                    NetChannel channel = (NetChannel) context.getChannel();

                    // Build the HTTPS redirect URL
                    String host = request.headers().get("host");
                    if (host == null) {
                        host = "localhost";
                    }
                    String redirectUrl = "https://" + host + request.uri();

                    // Send 301 Moved Permanently
                    String body = "<html><body><h1>301 Moved Permanently</h1><p>Redirecting to <a href=\"" + redirectUrl + "\">" + redirectUrl + "</a></p></body></html>";
                    ByteBuf content = ByteBuf.wrap(body.getBytes(StandardCharsets.UTF_8));
                    DefaultFullHttpResponse response = new DefaultFullHttpResponse(net.hasor.neta.codec.http.constant.HttpVersion.HTTP_1_1, net.hasor.neta.codec.http.constant.HttpStatus.MOVED_PERMANENTLY, content);
                    response.headers().set("Location", redirectUrl);
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_HTML + "; charset=UTF-8");
                    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(content.readableBytes()));
                    response.headers().set(HttpHeaderNames.CONNECTION, "close");
                    response.headers().set(HttpHeaderNames.SERVER, serverName);

                    channel.sendData(response);
                    channel.close();
                }
            }
            return ProtoStatus.Stop;
        }
    }
}
