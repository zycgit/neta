/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.cors.CorsConfig;
import net.hasor.neta.codec.http.cors.CorsUtil;
import net.hasor.neta.codec.http.websocket.WebSocketHandshakeAuthorizer;
import net.hasor.neta.codec.http.websocket.WebSocketHandshakeEvent;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.nhttp.server.connector.*;
import net.hasor.nhttp.server.container.ConnectionManager;
import net.hasor.nhttp.server.container.DefaultErrorHandler;
import net.hasor.nhttp.server.container.ServerEventBus;
import net.hasor.nhttp.server.container.ServletDispatcher;
import net.hasor.nhttp.server.internal.*;

/**
 * Main HTTP server built on the Neta AIO framework.
 *
 * <h3>Architecture overview</h3>
 * <pre>
 * IO thread (neta pipeline)
 *   ├─ ConnectionLifecycleHandler  ← fires onConnectionOpen / onConnectionClose
 *   ├─ Protocol routing (H1 / H2 / H2C)
 *   ├─ HttpRequestHandler          ← accumulates headers, fires onHttpRequest callback
 *   └─ WebSocketLifecycleHandler   ← fires onWebSocketOpen callback
 *
 * Worker thread (RequestManager executor)
 *   └─ handleRequest()
 *        ├─ StreamingServletRequest  ← adapts codec objects to ServletRequest
 *        ├─ InternalServletResponse  ← writes through ResponseSink (H1 / H2)
 *        └─ ServletDispatcher.dispatch() → servlet / filter chain
 * </pre>
 *
 * <h3>Protocols</h3>
 * <ul>
 *   <li>{@link #start} — plain HTTP/1.1 + h2c (HTTP/2 cleartext)</li>
 *   <li>{@link #startSSL} — HTTPS with ALPN (h2 / http/1.1)</li>
 *   <li>{@link #startHttp3} — HTTP/3 over QUIC (UDP)</li>
 * </ul>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public class NetaHttpServer {
    private static final Logger logger = Logger.getLogger(NetaHttpServer.class);

    // =========================================================================
    // Configuration fields — mutable until start()
    // =========================================================================

    private String                     serverName                  = "Neta-HTTP";
    private String                     contextPath                 = "";
    private int                        maxContentLength            = 0;         // 0 = unlimited
    private int                        maxInitialLineLength        = 4096;
    private int                        maxHeaderSize               = 8192;
    private int                        maxChunkSize                = 8192;
    private int                        bodyQueueCapacity           = 16;
    private final int                  maxConcurrentRequests       = 200;
    private final long                 requestTimeoutMillis        = 30_000L;
    private final int                  maxConnections              = 10_000;
    private final long                 connectionIdleTimeoutMillis = 60_000L;
    private final long                 gracefulShutdownMillis      = 30_000L;
    private ExecutorService            executor;
    private BackpressureStrategy       backpressureStrategy        = BackpressureStrategy.limitedWait(200L);
    private boolean                    http2Enabled                = true;
    private SslConfig                  sslConfig;
    private CorsConfig                 corsConfig;
    private ErrorHandler               errorHandler;

    // =========================================================================
    // Always-present infrastructure
    // =========================================================================

    private final NetManager            netManager     = new NetManager();
    private final DefaultSessionManager sessionManager = new DefaultSessionManager();
    private final ServletDispatcher     dispatcher     = new ServletDispatcher();
    private final ServerEventBus        eventBus       = new ServerEventBus();

    // =========================================================================
    // Initialised at start() time
    // =========================================================================

    private ServerConfig          builtConfig;
    private RequestManager        requestManager;
    private ConnectionManager     connectionManager;
    private ErrorHandler          activeErrorHandler;
    private DefaultServletContext servletContext;

    /** Active async contexts; scanned every second by the timeout watchdog. */
    private final Set<InternalAsyncContext> asyncContexts = ConcurrentHashMap.newKeySet();
    /** Single-thread daemon that fires AsyncListener#onTimeout when requests exceed their deadline. */
    private ScheduledExecutorService        asyncWatchdog;

    // =========================================================================
    // Connectors
    // =========================================================================

    private HttpConnector  httpConnector;
    private HttpsConnector httpsConnector;
    private Http3Connector http3Connector;
    private int            http3Port = -1;

    // =========================================================================
    // Server state
    // =========================================================================

    private volatile boolean     running   = false;
    private final CountDownLatch stopLatch = new CountDownLatch(1);

    // =========================================================================
    // Configuration setters (fluent)
    // =========================================================================

    /** Sets the value for the {@code Server} response header. */
    public NetaHttpServer serverName(String serverName) {
        this.serverName = serverName;
        return this;
    }

    /** Sets the context path prefix (default: empty string). */
    public NetaHttpServer contextPath(String contextPath) {
        this.contextPath = contextPath != null ? contextPath : "";
        return this;
    }

    /** Sets the maximum request body size in bytes. Use {@code 0} or a negative value for no transport-level limit. */
    public NetaHttpServer maxContentLength(int bytes) {
        this.maxContentLength = bytes;
        return this;
    }

    /** Sets the maximum request-line length. */
    public NetaHttpServer maxInitialLineLength(int len) {
        this.maxInitialLineLength = len;
        return this;
    }

    /** Sets the maximum header section size. */
    public NetaHttpServer maxHeaderSize(int size) {
        this.maxHeaderSize = size;
        return this;
    }

    /** Sets the maximum HTTP content chunk size. */
    public NetaHttpServer maxChunkSize(int size) {
        this.maxChunkSize = size;
        return this;
    }

    /** Sets the per-request streaming body queue capacity. */
    public NetaHttpServer bodyQueueCapacity(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.bodyQueueCapacity = capacity;
        return this;
    }

    /** Sets the backpressure strategy used when the request body queue is full. */
    public NetaHttpServer backpressureStrategy(BackpressureStrategy strategy) {
        this.backpressureStrategy = java.util.Objects.requireNonNull(strategy, "strategy");
        return this;
    }

    /** Sets the session timeout in seconds. */
    public NetaHttpServer sessionTimeout(int seconds) {
        this.sessionManager.setDefaultMaxInactiveInterval(seconds);
        return this;
    }

    /** Enables or disables HTTP/2 support (default: enabled). */
    public NetaHttpServer http2(boolean enabled) {
        this.http2Enabled = enabled;
        return this;
    }

    /** Configures SSL/TLS for HTTPS support. */
    public NetaHttpServer ssl(SslConfig sslConfig) {
        this.sslConfig = sslConfig;
        return this;
    }

    /** Configures CORS. */
    public NetaHttpServer cors(CorsConfig corsConfig) {
        this.corsConfig = corsConfig;
        return this;
    }

    /** Sets a custom error handler (default: {@link DefaultErrorHandler}). */
    public NetaHttpServer errorHandler(ErrorHandler handler) {
        this.errorHandler = handler;
        return this;
    }

    /** Sets a custom worker {@link ExecutorService} (default: internal cached-thread-pool). */
    public NetaHttpServer executor(ExecutorService executor) {
        this.executor = executor;
        return this;
    }

    /** Adds a lifecycle listener. */
    public NetaHttpServer addListener(ServerListener listener) {
        this.eventBus.addListener(listener);
        return this;
    }

    // =========================================================================
    // Servlet / Filter / WebSocket registration
    // =========================================================================

    /** Registers a servlet at the given URL pattern. */
    public NetaHttpServer addServlet(String urlPattern, HttpServlet servlet) {
        this.dispatcher.addServlet(urlPattern, servlet);
        return this;
    }

    /** Registers a filter at the given URL pattern. */
    public NetaHttpServer addFilter(String urlPattern, Filter filter) {
        this.dispatcher.addFilter(urlPattern, filter);
        return this;
    }

    /** Registers a WebSocket handler at the given exact path. */
    public NetaHttpServer addWebSocket(String path, WebSocketHandler handler) {
        this.dispatcher.addWebSocket(path, handler);
        return this;
    }

    /** Sets the default servlet for unmatched paths. */
    public NetaHttpServer setDefaultServlet(HttpServlet defaultServlet) {
        this.dispatcher.setDefaultServlet(defaultServlet);
        return this;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /** Starts a plain-HTTP listener on the given port (HTTP/1.1 + h2c). */
    public NetaHttpServer start(int port) throws Exception {
        return start(new InetSocketAddress(port));
    }

    /** Starts a plain-HTTP listener on the given address (HTTP/1.1 + h2c). */
    public NetaHttpServer start(InetSocketAddress address) throws Exception {
        ensureNotRunning();
        initComponents();

        this.httpConnector = new HttpConnector(createWsAuthorizer());
        this.httpConnector.start(this.netManager, address, this.builtConfig, new DispatchCallbackImpl());
        this.running = true;

        String protocols = this.http2Enabled ? "http/1.1, h2c" : "http/1.1";
        logger.info("Neta HTTP server started on " + address + " (protocols: " + protocols + ")");
        this.eventBus.fireServerStarted(this);
        return this;
    }

    /** Starts an HTTPS listener on the given port with ALPN protocol negotiation. */
    public NetaHttpServer startSSL(int port) throws Exception {
        return startSSL(new InetSocketAddress(port));
    }

    /** Starts an HTTPS listener on the given address with ALPN protocol negotiation. */
    public NetaHttpServer startSSL(InetSocketAddress address) throws Exception {
        if (this.sslConfig == null) {
            throw new IllegalStateException("SSL configuration is required. Call ssl() first.");
        }
        ensureNotRunning();
        initComponents();

        this.httpsConnector = new HttpsConnector(createWsAuthorizer());
        this.httpsConnector.start(this.netManager, address, this.builtConfig, new DispatchCallbackImpl());
        this.running = true;

        String protocols = this.http2Enabled ? "h2, http/1.1" : "http/1.1";
        logger.info("Neta HTTPS server started on " + address + " (protocols: " + protocols + ")");
        this.eventBus.fireServerStarted(this);
        return this;
    }

    /** Starts an HTTP/3 over QUIC (UDP) listener on the given port. */
    public NetaHttpServer startHttp3(int port) throws Exception {
        return startHttp3(new InetSocketAddress(port));
    }

    /** Starts an HTTP/3 over QUIC (UDP) listener on the given address. */
    public NetaHttpServer startHttp3(InetSocketAddress address) throws Exception {
        ensureNotRunning();
        initComponents();

        this.http3Port = address.getPort();
        this.http3Connector = new Http3Connector();
        this.http3Connector.start(this.netManager, address, this.builtConfig, new DispatchCallbackImpl());
        this.running = true;

        logger.info("Neta HTTP/3 (QUIC) server started on UDP " + address);
        this.eventBus.fireServerStarted(this);
        return this;
    }

    /** Stops the server and releases all resources. */
    public void stop() {
        if (!this.running) {
            return;
        }
        this.eventBus.fireServerStopping(this);
        this.running = false;
        this.stopLatch.countDown();

        stopConnector(this.httpConnector);
        stopConnector(this.httpsConnector);
        stopConnector(this.http3Connector);

        // Stop the async timeout watchdog first so it does not race with shutdown
        if (this.asyncWatchdog != null) {
            this.asyncWatchdog.shutdownNow();
        }

        // Graceful worker shutdown
        if (this.requestManager != null) {
            this.requestManager.shutdown(this.gracefulShutdownMillis);
        }

        // Close remaining connections
        if (this.connectionManager != null) {
            this.connectionManager.closeAll();
        }

        // Destroy servlets and filters
        this.dispatcher.destroy();

        try {
            this.netManager.shutdown();
        } catch (Exception e) {
            logger.warn("Error shutting down NetManager", e);
        }

        this.eventBus.fireServerStopped(this);
        logger.info("Neta HTTP server stopped");
    }

    /** Blocks the calling thread until the server is stopped. */
    public void await() throws InterruptedException {
        this.stopLatch.await();
    }

    /** Returns {@code true} if the server is currently running. */
    public boolean isRunning() {
        return this.running;
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /** Returns the session manager. */
    public SessionManager getSessionManager() {
        return this.sessionManager;
    }

    /** Returns the underlying {@link NetManager}. */
    public NetManager getNetManager() {
        return this.netManager;
    }

    /** Returns the event bus; use to add {@link ServerListener}s after construction. */
    public ServerEventBus getEventBus() {
        return this.eventBus;
    }

    /**
     * Test helper: eagerly initialises the servlet context and worker-side infrastructure
     * without binding a real listener.
     */
    public void initServletContext() throws Exception {
        initComponents();
    }

    /**
     * Test helper: creates the plain HTTP pipeline without binding a socket.
     */
    public ProtoInitializer createHttpInitializer(boolean secure) throws Exception {
        initComponents();
        return PipelineFactory.createHttpPipeline(this.builtConfig, new DispatchCallbackImpl(), secure, createWsAuthorizer());
    }

    /**
     * Test helper: configures ALPN on the current SSL configuration.
     */
    public void configureAlpn() {
        if (this.sslConfig == null) {
            throw new IllegalStateException("SSL configuration is required. Call ssl() first.");
        }

        if (this.http2Enabled) {
            this.sslConfig.setAppProtocol(new String[] { "h2", "http/1.1" });
            this.sslConfig.setAppProtocolSelector((channel, clientProtocols) -> clientProtocols.contains("h2") ? "h2" : "http/1.1");
        } else {
            this.sslConfig.setAppProtocol(new String[] { "http/1.1" });
            this.sslConfig.setAppProtocolSelector((channel, clientProtocols) -> "http/1.1");
        }
    }

    /**
     * Test helper: creates the HTTPS + ALPN pipeline without binding a socket.
     */
    public ProtoInitializer createHttpsAlpnInitializer() throws Exception {
        initComponents();
        configureAlpn();
        return PipelineFactory.createHttpsAlpnPipeline(this.builtConfig, new DispatchCallbackImpl(), createWsAuthorizer());
    }

    // =========================================================================
    // Initialization helpers
    // =========================================================================

    private void ensureNotRunning() {
        if (this.running) {
            throw new IllegalStateException("Server is already running");
        }
    }

    /**
     * Initialises all components that cannot be created before configuration is finalised.
     * Idempotent: safe to call from multiple {@code startXxx} methods.
     */
    private void initComponents() throws Exception {
        if (this.builtConfig != null) {
            return; // already initialised
        }
        this.builtConfig = ServerConfig.builder().serverName(this.serverName).contextPath(this.contextPath).maxContentLength(this.maxContentLength).maxInitialLineLength(this.maxInitialLineLength).maxHeaderSize(this.maxHeaderSize).maxChunkSize(this.maxChunkSize).bodyQueueCapacity(this.bodyQueueCapacity).maxConcurrentRequests(this.maxConcurrentRequests).requestTimeout(this.requestTimeoutMillis).maxConnections(this.maxConnections).connectionIdleTimeout(this.connectionIdleTimeoutMillis).gracefulShutdown(this.gracefulShutdownMillis).executor(this.executor).backpressureStrategy(this.backpressureStrategy).http2(this.http2Enabled).ssl(this.sslConfig).cors(this.corsConfig).errorHandler(this.errorHandler).build();

        this.connectionManager = new ConnectionManager(this.maxConnections, this.connectionIdleTimeoutMillis);
        this.activeErrorHandler = (this.errorHandler != null) ? this.errorHandler : new DefaultErrorHandler();

        this.servletContext = new DefaultServletContext(this.serverName, this.contextPath, this.corsConfig, this.sessionManager);
        this.dispatcher.init(this.servletContext);

        this.requestManager = new RequestManager(this.builtConfig, this::handleRequest);

        // Async timeout watchdog — fires once per second, checks all live async contexts.
        this.asyncWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "neta-async-watchdog");
            t.setDaemon(true);
            return t;
        });
        this.asyncWatchdog.scheduleAtFixedRate(this::checkAsyncTimeouts, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Invoked by {@link #asyncWatchdog} once per second.
     * First purges any already-completed contexts, then calls
     * {@link InternalAsyncContext#checkTimeout()} on the survivors — which fires
     * {@link AsyncListener#onTimeout} and auto-completes contexts whose deadline has passed.
     */
    private void checkAsyncTimeouts() {
        try {
            // Remove completed entries first to keep the set small
            this.asyncContexts.removeIf(InternalAsyncContext::isCompleted);
            // Check timeout on the remaining live contexts
            for (InternalAsyncContext ctx : this.asyncContexts) {
                ctx.checkTimeout();
            }
        } catch (Throwable t) {
            logger.warn("Error in async timeout watchdog", t);
        }
    }

    private WebSocketHandshakeAuthorizer createWsAuthorizer() {
        return (event, callback) -> {
            WebSocketHandler wsHandler = this.dispatcher.findWebSocketHandler(event.requestPath());
            if (wsHandler == null) {
                byte[] body = ("No WebSocket handler for: " + event.requestPath()).getBytes(StandardCharsets.UTF_8);
                callback.reject(HttpStatus.NOT_FOUND, body);
            } else {
                callback.accept();
            }
        };
    }

    private static void stopConnector(Connector connector) {
        if (connector != null) {
            try {
                connector.stop();
            } catch (Throwable e) {
                logger.warn("Error stopping connector " + connector.getProtocol(), e);
            }
        }
    }

    // =========================================================================
    // Request handler (worker thread)
    // =========================================================================

    /**
     * Processes one HTTP request on a worker thread.
     * Called by {@link RequestManager} after the IO thread hands off the request.
     *
     * <h3>Async processing</h3>
     * <p>If the servlet calls {@link ServletRequest#startAsync()}, {@link #handleRequest} returns
     * without committing the response.  {@link RequestManager} detects {@code ctx.asyncStarted}
     * and skips the concurrency-slot release.  The {@link InternalAsyncContext#complete()} call
     * (on any thread) triggers the final commit and cleanup via the lambda below.</p>
     */
    private void handleRequest(RequestContext ctx) {
        StreamingServletRequest req = new StreamingServletRequest(ctx.httpRequest, ctx.httpHeaders, ctx.bodyChannel, ctx.channel, ctx.secure, this.sessionManager, this.requestTimeoutMillis);
        InternalServletResponse resp = new InternalServletResponse(ctx.responseSink, this.serverName);

        // ---- Async cleanup — idempotent; runs exactly once per request regardless of path ----
        final AtomicBoolean cleanupDone = new AtomicBoolean(false);
        final Runnable asyncCleanup = () -> {
            if (!cleanupDone.compareAndSet(false, true)) {
                return; // already ran — prevent double-decrement of concurrency slot
            }
            this.requestManager.releaseSlot(ctx);
            this.connectionManager.onRequestCompleted(ctx.channel);
            long elapsed = System.currentTimeMillis() - ctx.receivedTimeMillis;
            this.eventBus.fireRequestCompleted(req, resp, elapsed);
            req.release();
            handleKeepAlive(ctx);
        };

        // ---- Inject the async context supplier so startAsync() works ----
        req.setAsyncContextSupplier(() -> {
            // Re-dispatch action: submits the servlet chain at a new path on the worker pool.
            // After the re-dispatch, the async context is auto-completed unless the servlet
            // called startAsync() again (in which case it is responsible for calling complete()).
            Consumer<String> dispatchFn = (path) -> {
                req.setDispatcherType(DispatcherType.ASYNC);
                req.setAsyncDispatchPath(path);
                // Clear the asyncContext so isAsyncStarted() returns false at the start of
                // the re-dispatch; if the servlet calls startAsync() again a new context is created.
                req.resetAsyncState();
                this.requestManager.executeAsync(() -> {
                    try {
                        this.dispatcher.dispatch(req, resp);
                        // If the servlet did not re-enter async mode, auto-complete
                        if (!req.isAsyncStarted()) {
                            if (!resp.isCommitted()) {
                                resp.commit();
                            }
                            asyncCleanup.run();
                        } else {
                            // Servlet started async again — it owns the completion
                            ctx.asyncStarted = true;
                        }
                    } catch (Throwable t) {
                        logger.warn("Error during async dispatch to: " + path, t);
                        if (!resp.isCommitted()) {
                            try {
                                this.activeErrorHandler.handleError(500, "Internal Server Error", t, req, resp);
                                if (!resp.isCommitted()) {
                                    resp.commit();
                                }
                            } catch (Throwable ex) {
                                logger.warn("Error handler failed during async dispatch", ex);
                            }
                        }
                        asyncCleanup.run();
                    }
                });
            };
            InternalAsyncContext asyncCtx = new InternalAsyncContext(req, resp, this.requestTimeoutMillis, asyncCleanup, dispatchFn);
            // Register with the timeout watchdog; removed automatically when completed.
            this.asyncContexts.add(asyncCtx);
            return asyncCtx;
        });

        try {
            this.connectionManager.onRequestStarted(ctx.channel);
            this.eventBus.fireRequestReceived(req);

            // CORS preflight (fast-path: no servlet involvement)
            if (this.corsConfig != null && this.corsConfig.isEnabled() && CorsUtil.isPreflightRequest(ctx.httpRequest, ctx.httpHeaders)) {
                handleCORSPreflight(ctx, resp);
                return; // finally block handles cleanup
            }

            // Dispatch through servlet / filter chain
            this.dispatcher.dispatch(req, resp);

            // If async was started by the servlet, hand control to AsyncContext.complete()
            if (req.isAsyncStarted()) {
                ctx.asyncStarted = true;
                return; // RequestManager's finally will see asyncStarted=true and skip slot release
            }

            // If the servlet never consumed the body, drain and discard it before
            // completing the request so large uploads don't get abandoned mid-stream.
            req.discardUnreadBody();

            // Wire session cookie now that the servlet has (possibly) created one
            HttpSession resolvedSession = req.getResolvedSession();
            if (resolvedSession != null) {
                resp.setSession(resolvedSession);
            }

            // Apply CORS simple-request headers if not yet committed
            if (!resp.isCommitted()) {
                applyCorsHeaders(ctx, resp);

                // Advertise HTTP/3 via Alt-Svc
                if (this.http3Port > 0) {
                    resp.addHeader("Alt-Svc", "h3=\":" + this.http3Port + "\"; ma=86400");
                }
            }

            // Commit (no-op if already committed / streaming)
            resp.commit();

            long elapsed = System.currentTimeMillis() - ctx.receivedTimeMillis;
            this.eventBus.fireRequestCompleted(req, resp, elapsed);

        } catch (Throwable t) {
            logger.warn("Error processing request: " + ctx.httpRequest.uri(), t);
            if (!resp.isCommitted()) {
                try {
                    this.activeErrorHandler.handleError(500, "Internal Server Error", t, req, resp);
                    if (!resp.isCommitted()) {
                        resp.commit();
                    }
                } catch (Throwable ex) {
                    logger.warn("Error handler failed", ex);
                }
            }
            // If async was started before the exception, notify the context
            if (req.isAsyncStarted()) {
                ctx.asyncStarted = true;
                ((InternalAsyncContext) req.getAsyncContext()).fireOnError(t);
            }
        } finally {
            // Skip the synchronous cleanup when async is active — asyncCleanup owns it
            if (!ctx.asyncStarted) {
                req.release();
                this.connectionManager.onRequestCompleted(ctx.channel);
                handleKeepAlive(ctx);
            }
        }
    }

    /**
     * Sends a CORS preflight response (204 No Content) without invoking any servlet.
     * Skips body drain since preflight requests have no body.
     * Keep-alive is handled by the caller's {@code finally} block.
     */
    private void handleCORSPreflight(RequestContext ctx, InternalServletResponse resp) throws IOException {
        resp.setStatus(204);
        CorsUtil.applyPreflightCorsHeaders(ctx.httpRequest, ctx.httpHeaders, resp.getResponseHeaders(), this.corsConfig);
        resp.setHeader(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO);
        resp.commit();
        // Note: keep-alive is NOT called here — the finally block in handleRequest() does it.
    }

    /** Applies CORS headers for simple (non-preflight) requests. */
    private void applyCorsHeaders(RequestContext ctx, InternalServletResponse resp) {
        if (this.corsConfig == null || !this.corsConfig.isEnabled()) {
            return;
        }
        CorsUtil.applySimpleCorsHeaders(ctx.httpHeaders, resp.getResponseHeaders(), this.corsConfig);
    }

    /**
     * Handles HTTP/1.1 keep-alive: closes the channel when the connection is not
     * persistent. No-op for HTTP/2+ (multiplexed; connection managed by GOAWAY frames).
     */
    private void handleKeepAlive(RequestContext ctx) {
        if (ctx.httpRequest.protocolVersion().majorVersion() >= 2) {
            return;
        }
        String connection = ctx.httpHeaders.getString(HttpHeaderNames.CONNECTION);
        boolean keepAlive;
        if (connection != null) {
            keepAlive = HttpHeaderValues.KEEP_ALIVE.equalsIgnoreCase(connection);
        } else {
            keepAlive = ctx.httpRequest.protocolVersion().isKeepAliveDefault();
        }
        if (!keepAlive) {
            ctx.channel.close();
        }
    }

    // =========================================================================
    // RequestDispatchCallback (IO-thread bridge)
    // =========================================================================

    /**
     * Bridges the connector (IO-thread) layer to the container (worker-thread) layer.
     */
    private class DispatchCallbackImpl implements RequestDispatchCallback {

        @Override
        public void onHttpRequest(ProtoContext context, HttpRequest requestLine, HttpHeaders requestHeaders, BodyChannel bodyChannel, ResponseSink responseSink, NetChannel channel, boolean secure) {
            RequestContext requestCtx = new RequestContext(requestLine, requestHeaders, (InternalBodyChannel) bodyChannel, responseSink, channel, context, secure);

            boolean dispatched = requestManager.tryDispatch(requestCtx);
            if (!dispatched) {
                // Worker pool at capacity — send 503 immediately from IO thread
                bodyChannel.close();
                sendIoError(context, requestLine.protocolVersion(), 503, "Service Unavailable");
            }
        }

        @Override
        public void onWebSocketOpen(ProtoContext context, WebSocketHandshakeEvent event, NetChannel channel, boolean secure) {
            WebSocketHandler wsHandler = dispatcher.findWebSocketHandler(event.requestPath());
            if (wsHandler == null) {
                return; // authorizer already rejected unknown paths; guard anyway
            }

            HandshakeRequest upgradeReq = new HandshakeRequest(event, channel, secure, sessionManager);
            DefaultWebSocketSession wsSession = new DefaultWebSocketSession(context, channel, event.streamId(), event.requestPath(), upgradeReq);

            context.rootContext(WebSocketHandler.class, wsHandler);
            // Store under the public interface key so WebSocketFrameHandler and
            // WebSocketLifecycleHandler can find the session via rootContext(WebSocketSession.class).
            context.rootContext(WebSocketSession.class, wsSession);

            try {
                wsHandler.onOpen(wsSession);
            } catch (Throwable e) {
                logger.warn("WebSocketHandler.onOpen() threw an exception", e);
                try {
                    wsHandler.onError(wsSession, e);
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void onConnectionOpen(NetChannel channel) {
            boolean accepted = connectionManager.tryRegister(channel);
            if (!accepted) {
                logger.debug("Connection limit reached; closing channel=" + channel.getChannelId());
                channel.close();
                return;
            }
            eventBus.fireConnectionOpened(channel.getChannelId(), channel.getRemoteAddr());
        }

        @Override
        public void onConnectionClose(NetChannel channel) {
            connectionManager.unregister(channel);
            eventBus.fireConnectionClosed(channel.getChannelId(), channel.getRemoteAddr());
        }
    }

    // =========================================================================
    // IO-thread error helper
    // =========================================================================

    /**
     * Sends a minimal error response from the IO thread (e.g. when the worker pool is full).
     * Closes the connection after sending.
     */
    private static void sendIoError(ProtoContext ctx, HttpVersion version, int code, String message) {
        try {
            String body = "<html><body><h1>" + code + " " + htmlEscape(message) + "</h1></body></html>";
            ByteBuf content = ByteBuf.wrap(body.getBytes(StandardCharsets.UTF_8));
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(version != null ? version : HttpVersion.HTTP_1_1, HttpStatus.valueOf(code), content);
            response.setHeader(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_HTML + "; charset=UTF-8");
            response.setHeader(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(content.readableBytes()));
            response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.sendEncoded(response).onFinal(f -> ctx.getChannel().closeNow());
        } catch (Throwable t) {
            logger.warn("Failed to send IO-thread error response", t);
            ctx.getChannel().closeNow();
        }
    }

    private static String htmlEscape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
