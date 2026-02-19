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
import java.util.logging.Level;
import java.util.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.constant.HttpHeaderNames;
import net.hasor.neta.codec.http.constant.HttpHeaderValues;
import net.hasor.neta.codec.http.cors.CorsConfig;
import net.hasor.neta.codec.http.cors.CorsUtil;
import net.hasor.neta.codec.http.websocket.WebSocketFrame;
import net.hasor.neta.codec.http.websocket.WebSocketServerHandshaker;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.neta.codec.ssl.SslDuplexer;
import net.hasor.neta.http.internal.*;

/**
 * Main HTTP server class built on the Neta AIO framework.
 * Supports HTTP, HTTPS (TLS/SSL), WebSocket, and CORS.
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * NetaHttpServer server = new NetaHttpServer();
 * server.addServlet("/hello", new HttpServlet() {
 *     protected void doGet(ServletRequest req, ServletResponse resp) throws IOException {
 *         resp.setContentType("text/plain");
 *         resp.write("Hello, World!");
 *     }
 * });
 * server.start(8080);
 * }</pre>
 * @author 赵永春 (zyc@hasor.net)
 */
public class NetaHttpServer {
    private static final Logger logger = Logger.getLogger(NetaHttpServer.class.getName());

    private final NetManager            netManager     = new NetManager();
    private final ServletDispatcher     dispatcher     = new ServletDispatcher();
    private final DefaultSessionManager sessionManager = new DefaultSessionManager();
    private       DefaultServletContext servletContext;

    // Configuration
    private String     serverName           = "Neta-HTTP";
    private String     contextPath          = "";
    private int        maxContentLength     = 1048576; // 1MB
    private int        maxInitialLineLength = 4096;
    private int        maxHeaderSize        = 8192;
    private int        maxChunkSize         = 8192;
    private SslConfig  sslConfig;
    private CorsConfig corsConfig;

    // State
    private          NetListen httpListen;
    private          NetListen httpsListen;
    private volatile boolean   running = false;

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

        // initialize context
        this.servletContext = new DefaultServletContext(this.serverName, this.contextPath, this.corsConfig, this.sessionManager);
        this.dispatcher.init(this.servletContext);

        // create HTTP pipeline initializer
        ProtoInitializer httpInitializer = createHttpInitializer(false);
        this.httpListen = this.netManager.bind(address, httpInitializer, SoConfig.TCP());
        this.running = true;

        logger.info("Neta HTTP server started on " + address);
        return this;
    }

    /** Starts the HTTPS server on the given port (requires SSL configuration) */
    public NetaHttpServer startSSL(int port) throws Exception {
        return startSSL(new InetSocketAddress(port));
    }

    /** Starts the HTTPS server on the given address */
    public NetaHttpServer startSSL(InetSocketAddress address) throws Exception {
        Objects.requireNonNull(this.sslConfig, "SSL configuration is required for HTTPS. Call ssl() first.");

        if (this.servletContext == null) {
            this.servletContext = new DefaultServletContext(this.serverName, this.contextPath, this.corsConfig, this.sessionManager);
            this.dispatcher.init(this.servletContext);
        }

        ProtoInitializer httpsInitializer = createHttpInitializer(true);
        this.httpsListen = this.netManager.bind(address, httpsInitializer, SoConfig.TCP());
        this.running = true;

        logger.info("Neta HTTPS server started on " + address);
        return this;
    }

    /** Stops the server and releases all resources */
    public void stop() {
        if (!this.running) {
            return;
        }
        this.running = false;

        try {
            if (this.httpListen != null) {
                this.httpListen.close();
            }
            if (this.httpsListen != null) {
                this.httpsListen.close();
            }
            this.netManager.shutdown();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error closing server", e);
        }

        this.dispatcher.destroy();
        logger.info("Neta HTTP server stopped");
    }

    /** Blocks the current thread until the server is stopped */
    public void await() throws InterruptedException {
        if (this.httpListen != null) {
            this.httpListen.waitIdle();
        }
        if (this.httpsListen != null) {
            this.httpsListen.waitIdle();
        }
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

    private ProtoInitializer createHttpInitializer(boolean secure) {
        return ctx -> {
            // SSL layer (for HTTPS only)
            if (secure && this.sslConfig != null) {
                ctx.addLast("ssl", new SslDuplexer(this.sslConfig));
            }

            // HTTP codec layer
            ctx.addLast("http-codec", new HttpServerDuplexe(this.maxInitialLineLength, this.maxHeaderSize, this.maxChunkSize));

            // HTTP aggregator (combines chunked messages into full requests)
            ctx.addLastDecoder("http-aggregator", new HttpObjectAggregator(this.maxContentLength));

            // Application handler (dispatches to servlets/websockets)
            ctx.addLastDecoder("http-handler", new HttpDispatchHandler(secure));
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
                    servletResponse.commit();
                }

                // handle keep-alive
                handleKeepAlive(request, channel);

            } catch (Exception e) {
                logger.log(Level.WARNING, "Error handling HTTP request: " + request.uri(), e);
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
                logger.log(Level.WARNING, "WebSocket upgrade failed", e);
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
                logger.log(Level.WARNING, "Error handling WebSocket frame", e);
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
                logger.log(Level.WARNING, "Failed to send error response", e);
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
            if (e instanceof net.hasor.neta.codec.http.exception.HttpProtocolException) {
                NetChannel channel = (NetChannel) context.getChannel();
                int statusCode = mapProtocolExceptionToStatusCode(e);
                String statusMessage = mapProtocolExceptionToMessage(e);
                logger.log(Level.WARNING, "HTTP protocol error: " + e.getMessage());
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
            if (e instanceof net.hasor.neta.codec.http.exception.HttpInitialLineTooLongException) {
                return 414; // URI Too Long
            } else if (e instanceof net.hasor.neta.codec.http.exception.HttpHeaderTooLargeException) {
                return 431; // Request Header Fields Too Large
            } else if (e instanceof net.hasor.neta.codec.http.exception.HttpContentTooLargeException) {
                return 413; // Content Too Large
            } else {
                return 400; // Bad Request
            }
        }

        private String mapProtocolExceptionToMessage(Throwable e) {
            if (e instanceof net.hasor.neta.codec.http.exception.HttpInitialLineTooLongException) {
                return "URI Too Long";
            } else if (e instanceof net.hasor.neta.codec.http.exception.HttpHeaderTooLargeException) {
                return "Request Header Fields Too Large";
            } else if (e instanceof net.hasor.neta.codec.http.exception.HttpContentTooLargeException) {
                return "Content Too Large";
            } else {
                return "Bad Request";
            }
        }
    }
}
