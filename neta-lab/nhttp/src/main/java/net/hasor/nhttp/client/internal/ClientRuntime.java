/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.nhttp.client.internal;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.routing.ProtoPartitionBuilder;
import net.hasor.neta.channel.routing.ProtoRoutingBuilder;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.codec.http.*;
import net.hasor.neta.codec.http.h2.*;
import net.hasor.neta.codec.http.routing.Http2OverTlsRoute;
import net.hasor.neta.codec.http.routing.HttpRouteKey;
import net.hasor.neta.codec.http.websocket.*;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.neta.codec.ssl.SslContext;
import net.hasor.neta.codec.ssl.SslDuplex;
import net.hasor.nhttp.client.*;
import net.hasor.nhttp.request.HttpObjectWriter;
import net.hasor.nhttp.request.HttpWriter;
import net.hasor.nhttp.request.Request;

/**
 * Internal client runtime that owns transport resources and request execution.
 * @author 赵永春 (zyc@hasor.net)
 */
public class ClientRuntime implements Closeable {
    private final HttpClientConfig         config;
    private final ClientDispatcher         dispatcher;
    private final NetManager               netManager;
    private final HttpWriter               requestWriter;
    private final ScheduledExecutorService timeoutScheduler;
    private final AtomicBoolean            closed = new AtomicBoolean(false);

    public ClientRuntime(HttpClientConfig config) {
        this.config = config;
        this.dispatcher = new ClientDispatcher(config.getMaxConcurrentCalls(), config.getMaxConcurrentCallsPerHost());
        this.netManager = new NetManager();
        this.requestWriter = new HttpObjectWriter();
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "nhttp-client-timeout");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public Future<Response> executeAsync(Request request) {
        final BasicFuture<Response> result = new BasicFuture<Response>();
        if (request == null) {
            result.failed(new IllegalArgumentException("request is null"));
            return result;
        }
        if (this.closed.get()) {
            result.failed(new IOException("client is closed"));
            return result;
        }

        this.dispatcher.enqueue(routeKey(request.getUri()), result, () -> this.startHttp(request, result));
        return result;
    }

    public Future<WebSocket> openWebSocket(Request request, WebSocketListener listener) {
        final BasicFuture<WebSocket> result = new BasicFuture<WebSocket>();
        if (request == null) {
            result.failed(new IllegalArgumentException("request is null"));
            return result;
        }
        if (this.closed.get()) {
            result.failed(new IOException("client is closed"));
            return result;
        }

        WebSocketListener actualListener = listener == null ? new WebSocketListener() {
        } : listener;
        this.dispatcher.enqueue(routeKey(request.getUri()), result, () -> this.startWebSocket(request, actualListener, result));
        return result;
    }

    @Override
    public void close() throws IOException {
        if (this.closed.compareAndSet(false, true)) {
            this.timeoutScheduler.shutdownNow();
            this.netManager.shutdown();
        }
    }

    static IOException asIOException(Throwable throwable) {
        if (throwable instanceof ExecutionException) {
            return asIOException(throwable.getCause());
        }
        if (throwable instanceof TimeoutException) {
            return new IOException(throwable);
        }
        if (throwable instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new IOException(throwable);
        }
        if (throwable instanceof IOException) {
            return (IOException) throwable;
        }
        return new IOException(throwable);
    }

    private void startHttp(Request request, BasicFuture<Response> result) {
        if (result.isDone()) {
            return;
        }

        ProtocolPlan plan;
        try {
            plan = ProtocolPlan.http(request.getUri(), this.config);
        } catch (Throwable e) {
            result.failed(asIOException(e));
            return;
        }

        final AtomicReference<NetChannel> channelRef = new AtomicReference<NetChannel>();
        final AtomicReference<SubscribeHolder> subscriptionRef = new AtomicReference<SubscribeHolder>();
        final AtomicReference<ScheduledFuture<?>> connectTimerRef = new AtomicReference<ScheduledFuture<?>>();
        final AtomicReference<ScheduledFuture<?>> writeTimerRef = new AtomicReference<ScheduledFuture<?>>();
        final AtomicReference<ScheduledFuture<?>> readTimerRef = new AtomicReference<ScheduledFuture<?>>();
        final AtomicReference<ScheduledFuture<?>> callTimerRef = new AtomicReference<ScheduledFuture<?>>();

        connectTimerRef.set(this.scheduleFailure(result, this.config.getConnectTimeoutMillis(), new IOException("connect timed out")));
        callTimerRef.set(this.scheduleFailure(result, this.config.getCallTimeoutMillis(), new IOException("call timed out")));
        result.onFinal(done -> {
            this.cancelTimer(connectTimerRef.get());
            this.cancelTimer(writeTimerRef.get());
            this.cancelTimer(readTimerRef.get());
            this.cancelTimer(callTimerRef.get());
            SubscribeHolder subscribeHolder = subscriptionRef.get();
            if (subscribeHolder != null) {
                subscribeHolder.unSubscribe();
            }
            this.safeClose(channelRef.get());
        });

        this.netManager.connectAsync(plan.remoteAddress, this.httpInitializer(plan), SoConfig.TCP()).onCompleted(connectFuture -> {
            NetChannel channel = connectFuture.getResult();
            channelRef.set(channel);
            if (result.isDone()) {
                this.safeClose(channel);
                return;
            }

            this.cancelTimer(connectTimerRef.get());
            try {
                this.awaitTlsReady(channel, plan, this.config.getConnectTimeoutMillis());
                if (result.isDone()) {
                    return;
                }

                channel.onClose(ch -> {
                    if (!result.isDone()) {
                        result.failed(new IOException("channel closed before response completed"));
                    }
                });

                subscriptionRef.set(channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, payload -> {
                    if (payload.getError() != null) {
                        result.failed(asIOException(payload.getError()));
                        return;
                    }

                    Object message = payload.getData();
                    if (message instanceof FullHttpResponse) {
                        this.cancelTimer(readTimerRef.get());
                        result.completed(Response.of(request.getUri(), (FullHttpResponse) message));
                    }
                }));

                HttpVersion requestVersion = this.resolveRequestVersion(channel, plan);
                HttpObject[] httpObjects = this.requestWriter.write(request, requestVersion);
                writeTimerRef.set(this.scheduleFailure(result, this.config.getWriteTimeoutMillis(), new IOException("request write timed out")));
                channel.sendData(httpObjects).onCompleted(sendFuture -> {
                    this.cancelTimer(writeTimerRef.get());
                    if (!result.isDone()) {
                        readTimerRef.set(this.scheduleFailure(result, this.config.getReadTimeoutMillis(), new IOException("response read timed out")));
                    }
                }).onFailed(sendFuture -> result.failed(asIOException(sendFuture.getCause())));
            } catch (Throwable e) {
                result.failed(asIOException(e));
            }
        }).onFailed(connectFuture -> result.failed(asIOException(connectFuture.getCause())));
    }

    private void startWebSocket(Request request, WebSocketListener listener, BasicFuture<WebSocket> result) {
        if (result.isDone()) {
            return;
        }

        final AtomicReference<java.net.http.WebSocket> rawWebSocketRef = new AtomicReference<java.net.http.WebSocket>();
        final AtomicReference<JdkWebSocket> webSocketRef = new AtomicReference<JdkWebSocket>();
        final AtomicReference<ScheduledFuture<?>> connectTimerRef = new AtomicReference<ScheduledFuture<?>>();
        final AtomicReference<ScheduledFuture<?>> openTimerRef = new AtomicReference<ScheduledFuture<?>>();

        connectTimerRef.set(this.scheduleFailure(result, this.config.getConnectTimeoutMillis(), new IOException("connect timed out")));
        openTimerRef.set(this.scheduleFailure(result, this.config.getWebSocketOpenTimeoutMillis(), new IOException("websocket open timed out")));
        result.onFinal(done -> {
            this.cancelTimer(connectTimerRef.get());
            this.cancelTimer(openTimerRef.get());
        });
        result.onFailed(done -> {
            JdkWebSocket webSocket = webSocketRef.get();
            if (webSocket != null) {
                webSocket.fireFailure(done.getCause());
            } else {
                this.safeFailure(listener, null, done.getCause());
            }
            java.net.http.WebSocket rawWebSocket = rawWebSocketRef.get();
            if (rawWebSocket != null) {
                rawWebSocket.abort();
            }
        }).onCancel(done -> {
            java.net.http.WebSocket rawWebSocket = rawWebSocketRef.get();
            if (rawWebSocket != null) {
                rawWebSocket.abort();
            }
        });

        try {
            java.net.http.HttpClient.Builder clientBuilder = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofMillis(this.config.getConnectTimeoutMillis()));
            java.net.http.WebSocket.Builder builder = clientBuilder.build().newWebSocketBuilder().connectTimeout(Duration.ofMillis(this.config.getWebSocketOpenTimeoutMillis()));

            List<String> subProtocols = new ArrayList<String>();
            for (Map.Entry<String, List<String>> entry : request.allHeaders().entrySet()) {
                String name = entry.getKey();
                if (StringUtils.equalsIgnoreCase(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, name)) {
                    for (String value : entry.getValue()) {
                        if (StringUtils.isBlank(value)) {
                            continue;
                        }
                        for (String item : value.split(",")) {
                            String protocol = StringUtils.trimToEmpty(item);
                            if (StringUtils.isNotBlank(protocol)) {
                                subProtocols.add(protocol);
                            }
                        }
                    }
                    continue;
                }
                for (String value : entry.getValue()) {
                    builder.header(name, value);
                }
            }
            if (!subProtocols.isEmpty()) {
                builder.subprotocols(subProtocols.get(0), subProtocols.subList(1, subProtocols.size()).toArray(new String[0]));
            }

            builder.buildAsync(request.getUri(), new java.net.http.WebSocket.Listener() {
                private final StringBuilder         textBuffer   = new StringBuilder();
                private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

                @Override
                public void onOpen(java.net.http.WebSocket webSocket) {
                    rawWebSocketRef.set(webSocket);
                    JdkWebSocket wrapped = new JdkWebSocket(request, webSocket, -1L, webSocket.getSubprotocol(), listener, ClientRuntime.this.config.getWriteTimeoutMillis());
                    webSocketRef.set(wrapped);
                    result.completed(wrapped);
                    wrapped.fireOpen();
                    webSocket.request(1);
                }

                @Override
                public CompletableFuture<?> onText(java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
                    this.textBuffer.append(data);
                    if (last) {
                        JdkWebSocket wrapped = webSocketRef.get();
                        if (wrapped != null) {
                            wrapped.fireText(this.textBuffer.toString());
                        }
                        this.textBuffer.setLength(0);
                    }
                    webSocket.request(1);
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletableFuture<?> onBinary(java.net.http.WebSocket webSocket, ByteBuffer data, boolean last) {
                    byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    this.binaryBuffer.write(bytes, 0, bytes.length);
                    if (last) {
                        JdkWebSocket wrapped = webSocketRef.get();
                        if (wrapped != null) {
                            wrapped.fireBinary(this.binaryBuffer.toByteArray());
                        }
                        this.binaryBuffer.reset();
                    }
                    webSocket.request(1);
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletableFuture<?> onPing(java.net.http.WebSocket webSocket, ByteBuffer message) {
                    byte[] bytes = new byte[message.remaining()];
                    message.get(bytes);
                    JdkWebSocket wrapped = webSocketRef.get();
                    if (wrapped != null) {
                        wrapped.firePing(bytes);
                    }
                    webSocket.request(1);
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletableFuture<?> onPong(java.net.http.WebSocket webSocket, ByteBuffer message) {
                    byte[] bytes = new byte[message.remaining()];
                    message.get(bytes);
                    JdkWebSocket wrapped = webSocketRef.get();
                    if (wrapped != null) {
                        wrapped.firePong(bytes);
                    }
                    webSocket.request(1);
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletableFuture<?> onClose(java.net.http.WebSocket webSocket, int statusCode, String reason) {
                    JdkWebSocket wrapped = webSocketRef.get();
                    if (wrapped != null) {
                        wrapped.markClosed();
                        wrapped.fireClosing(statusCode, reason);
                        wrapped.fireClosed(statusCode, reason);
                    } else if (!result.isDone()) {
                        result.failed(new IOException("websocket closed before open completed"));
                    }
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public void onError(java.net.http.WebSocket webSocket, Throwable error) {
                    if (!result.isDone()) {
                        result.failed(asIOException(error));
                        return;
                    }
                    JdkWebSocket wrapped = webSocketRef.get();
                    if (wrapped != null) {
                        wrapped.fireFailure(error);
                    }
                }
            }).whenComplete((ws, error) -> {
                if (error != null && !result.isDone()) {
                    result.failed(asIOException(error));
                }
            });
        } catch (Throwable e) {
            result.failed(asIOException(e));
        }
    }

    private void dispatchWebSocketPayload(Request request, NetChannel channel, WebSocketListener listener, AtomicReference<RealWebSocket> webSocketRef, AtomicReference<SubscribeHolder> subscriptionRef, BasicFuture<WebSocket> result, PlayLoad payload) {
        if (payload.getError() != null) {
            if (!result.isDone()) {
                result.failed(asIOException(payload.getError()));
            } else {
                RealWebSocket webSocket = webSocketRef.get();
                if (webSocket != null) {
                    webSocket.fireFailure(payload.getError());
                } else {
                    this.safeFailure(listener, null, payload.getError());
                }
            }
            return;
        }

        Object message = payload.getData();
        if (message instanceof WebSocketHandshakeEvent) {
            WebSocketHandshakeEvent handshakeEvent = (WebSocketHandshakeEvent) message;
            try {
                RealWebSocket webSocket = new RealWebSocket(request, channel, handshakeEvent.streamId(), handshakeEvent.subProtocol(), listener, this.config.getWriteTimeoutMillis());
                webSocketRef.set(webSocket);
                result.completed(webSocket);
                webSocket.fireOpen();
            } finally {
                handshakeEvent.release();
            }
            return;
        }

        if (message instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) message;
            IOException error = new IOException("websocket handshake rejected: " + response.status().code() + " " + response.status().reasonPhrase());
            response.release();
            if (!result.isDone()) {
                result.failed(error);
            } else {
                RealWebSocket webSocket = webSocketRef.get();
                if (webSocket != null) {
                    webSocket.fireFailure(error);
                }
            }
            return;
        }

        RealWebSocket webSocket = webSocketRef.get();
        if (message instanceof TextWebSocketMessage) {
            TextWebSocketMessage textMessage = (TextWebSocketMessage) message;
            try {
                if (webSocket != null) {
                    webSocket.fireText(new String(textMessage.content().asByteArray(), StandardCharsets.UTF_8));
                }
            } finally {
                textMessage.release();
            }
            return;
        }

        if (message instanceof BinaryWebSocketMessage) {
            BinaryWebSocketMessage binaryMessage = (BinaryWebSocketMessage) message;
            try {
                if (webSocket != null) {
                    webSocket.fireBinary(binaryMessage.content().asByteArray());
                }
            } finally {
                binaryMessage.release();
            }
            return;
        }

        if (message instanceof PingWebSocketEvent) {
            PingWebSocketEvent pingEvent = (PingWebSocketEvent) message;
            try {
                if (webSocket != null) {
                    webSocket.firePing(pingEvent.content().asByteArray());
                }
            } finally {
                pingEvent.release();
            }
            return;
        }

        if (message instanceof PongWebSocketEvent) {
            PongWebSocketEvent pongEvent = (PongWebSocketEvent) message;
            try {
                if (webSocket != null) {
                    webSocket.firePong(pongEvent.content().asByteArray());
                }
            } finally {
                pongEvent.release();
            }
            return;
        }

        if (message instanceof WebSocketCloseEvent) {
            WebSocketCloseEvent closeEvent = (WebSocketCloseEvent) message;
            try {
                if (webSocket != null) {
                    webSocket.markClosed();
                    webSocket.fireClosing(closeEvent.statusCode(), closeEvent.reason());
                    webSocket.fireClosed(closeEvent.statusCode(), closeEvent.reason());
                }
            } finally {
                closeEvent.release();
                SubscribeHolder subscribeHolder = subscriptionRef.get();
                if (subscribeHolder != null) {
                    subscribeHolder.unSubscribe();
                }
                this.safeClose(channel);
            }
        }
    }

    private ScheduledFuture<?> scheduleFailure(BasicFuture<?> result, long timeoutMillis, IOException exception) {
        return this.timeoutScheduler.schedule(() -> result.failed(exception), timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelTimer(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private void sendWebSocketHandshake(NetChannel channel, ProtocolPlan plan, FullHttpRequest handshakeRequest, BasicFuture<WebSocket> result) {
        if (this.useDirectHttp1WebSocket(plan)) {
            channel.sendData(handshakeRequest, "ws-client").onFailed(sendFuture -> result.failed(asIOException(sendFuture.getCause())));
            return;
        }

        if (handshakeRequest.protocolVersion().majorVersion() == 2) {
            channel.sendData(handshakeRequest).onFailed(sendFuture -> result.failed(asIOException(sendFuture.getCause())));
            return;
        }

        DefaultHttpRequest requestLine = new DefaultHttpRequest(handshakeRequest.protocolVersion(), handshakeRequest.method(), handshakeRequest.uri());
        DefaultLastHttpHeaders headers = new DefaultLastHttpHeaders(handshakeRequest);
        DefaultLastHttpContent lastContent = new DefaultLastHttpContent(ByteBuf.EMPTY);
        handshakeRequest.release();

        channel.sendData(requestLine).onCompleted(step1 -> {
            channel.sendData(headers).onCompleted(step2 -> {
                channel.sendData(lastContent).onFailed(sendFuture -> result.failed(asIOException(sendFuture.getCause())));
            }).onFailed(sendFuture -> result.failed(asIOException(sendFuture.getCause())));
        }).onFailed(sendFuture -> result.failed(asIOException(sendFuture.getCause())));
    }

    private boolean useDirectHttp1WebSocket(ProtocolPlan plan) {
        return plan.versionPolicy == HttpVersionPolicy.HTTP_1_1 || !plan.secure;
    }

    private FullHttpRequest websocketHandshake(Request request, NetChannel channel, ProtocolPlan plan) throws IOException {
        HttpVersion requestVersion = this.resolveRequestVersion(channel, plan);
        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        for (Map.Entry<String, List<String>> entry : request.allHeaders().entrySet()) {
            for (String value : entry.getValue()) {
                headers.addHeader(entry.getKey(), value);
            }
        }
        if (requestVersion.majorVersion() == 2) {
            return WebSocketUtils.createHttp2Handshake(this.config.getWebSocketVersion(), request.getUri().toString(), headers);
        }
        return WebSocketUtils.createHandshake(this.config.getWebSocketVersion(), request.getUri().toString(), headers);
    }

    private void awaitTlsReady(NetChannel channel, ProtocolPlan plan, long timeoutMillis) throws IOException {
        if (!plan.secure) {
            return;
        }

        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            SslContext sslContext = channel.findProtoContext(SslContext.class);
            if (sslContext != null && sslContext.isReady()) {
                return;
            }
            if (channel.isClose()) {
                throw new IOException("channel closed before TLS handshake completed");
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
        throw new IOException("TLS handshake timed out");
    }

    private HttpVersion resolveRequestVersion(NetChannel channel, ProtocolPlan plan) throws IOException {
        if (!plan.secure) {
            return plan.preferredVersion;
        }
        if (plan.versionPolicy == HttpVersionPolicy.HTTP_1_1) {
            return HttpVersion.HTTP_1_1;
        }
        if (plan.versionPolicy == HttpVersionPolicy.HTTP_2) {
            return HttpVersion.HTTP_2_0;
        }

        SslContext sslContext = channel.findProtoContext(SslContext.class);
        String protocol = sslContext != null ? sslContext.getApplicationProtocol() : null;
        if (StringUtils.equalsIgnoreCase("h2", protocol)) {
            return HttpVersion.HTTP_2_0;
        }
        return HttpVersion.HTTP_1_1;
    }

    private ProtoInitializer httpInitializer(ProtocolPlan plan) {
        return ctx -> {
            if (plan.secure) {
                if (plan.versionPolicy == HttpVersionPolicy.AUTO) {
                    ProtoHelper.standard().nextDuplex("ssl", new SslDuplex(plan.sslConfig)).nextRouteAsStatic("alpn", new Http2OverTlsRoute(), routing -> {
                        routing.branch(HttpRouteKey.BRANCH_H1, branch -> branch.nextDuplex("http-codec", new HttpClientDuplex()).nextDecoder("http-aggregator", new HttpResponseAggregator(this.config.getMaxContentLength())));
                        routing.branch(HttpRouteKey.BRANCH_H2, branch -> branch.nextDuplex("h2-frame", new Http2FrameDuplex(false)).nextDuplex("h2-message", new Http2ObjectDuplex(false)).nextDuplex("h2-aggregator", new HttpClientDuplexAggregator(this.config.getMaxContentLength())));
                    }).config(ctx);
                } else if (plan.versionPolicy == HttpVersionPolicy.HTTP_2) {
                    ProtoHelper.standard().nextDuplex("ssl", new SslDuplex(plan.sslConfig)).nextDuplex("h2-frame", new Http2FrameDuplex(false)).nextDuplex("h2-message", new Http2ObjectDuplex(false)).nextDuplex("h2-aggregator", new HttpClientDuplexAggregator(this.config.getMaxContentLength())).config(ctx);
                } else {
                    ProtoHelper.standard().nextDuplex("ssl", new SslDuplex(plan.sslConfig)).nextDuplex("http-codec", new HttpClientDuplex()).nextDecoder("http-aggregator", new HttpResponseAggregator(this.config.getMaxContentLength())).config(ctx);
                }
                return;
            }

            if (plan.versionPolicy == HttpVersionPolicy.HTTP_2) {
                ProtoHelper.standard().nextDuplex("h2-frame", new Http2FrameDuplex(false)).nextDuplex("h2-message", new Http2ObjectDuplex(false)).nextDuplex("h2-aggregator", new HttpClientDuplexAggregator(this.config.getMaxContentLength())).config(ctx);
            } else {
                ProtoHelper.standard().nextDuplex("http-codec", new HttpClientDuplex()).nextDecoder("http-aggregator", new HttpResponseAggregator(this.config.getMaxContentLength())).config(ctx);
            }
        };
    }

    private ProtoInitializer websocketInitializer(ProtocolPlan plan) {
        return ctx -> {
            if (this.useDirectHttp1WebSocket(plan)) {
                if (plan.secure) {
                    ProtoHelper.standard().nextDuplex("ssl", new SslDuplex(plan.sslConfig)).nextDuplex("http-client", new HttpClientDuplex()).nextDuplex("ws-client", new WebSocketClientHandshakeDuplex(this.config.getWebSocketVersion())).nextDuplex("ws-frame", new WebSocketFrameDuplex(this.config.getWebSocketVersion())).nextDuplex("ws-message", new WebSocketMessageDuplex()).config(ctx);
                } else {
                    ProtoHelper.standard().nextDuplex("http-client", new HttpClientDuplex()).nextDuplex("ws-client", new WebSocketClientHandshakeDuplex(this.config.getWebSocketVersion())).nextDuplex("ws-frame", new WebSocketFrameDuplex(this.config.getWebSocketVersion())).nextDuplex("ws-message", new WebSocketMessageDuplex()).config(ctx);
                }
                return;
            }

            if (plan.secure && plan.versionPolicy == HttpVersionPolicy.AUTO) {
                ProtoHelper.standard().nextDuplex("ssl", new SslDuplex(plan.sslConfig)).nextRouteAsStatic("alpn", new Http2OverTlsRoute(), routing -> {
                    routing.branch(HttpRouteKey.BRANCH_H1, branch -> branch.nextDuplex("http-client", new HttpClientDuplex()).nextDuplex("client-route", this.newHttp1WebSocketRoute()));
                    routing.branch(HttpRouteKey.BRANCH_H2, branch -> branch.nextDuplex("h2-frame", new Http2FrameDuplex(false)).nextDuplex("h2-message", new Http2ObjectDuplex(false)).nextPartition("h2-stream", new Http2ObjectPartitionSelector(), this::configureHttp2WebSocketPartitions));
                }).config(ctx);
                return;
            }

            if (plan.secure) {
                if (plan.versionPolicy == HttpVersionPolicy.HTTP_2) {
                    ProtoHelper.standard().nextDuplex("ssl", new SslDuplex(plan.sslConfig)).nextDuplex("h2-frame", new Http2FrameDuplex(false)).nextDuplex("h2-message", new Http2ObjectDuplex(false)).nextPartition("h2-stream", new Http2ObjectPartitionSelector(), this::configureHttp2WebSocketPartitions).config(ctx);
                } else {
                    ProtoHelper.standard().nextDuplex("ssl", new SslDuplex(plan.sslConfig)).nextDuplex("http-client", new HttpClientDuplex()).nextDuplex("client-route", this.newHttp1WebSocketRoute()).config(ctx);
                }
                return;
            }

            if (plan.versionPolicy == HttpVersionPolicy.HTTP_2) {
                ProtoHelper.standard().nextDuplex("h2-frame", new Http2FrameDuplex(false)).nextDuplex("h2-message", new Http2ObjectDuplex(false)).nextPartition("h2-stream", new Http2ObjectPartitionSelector(), this::configureHttp2WebSocketPartitions).config(ctx);
            } else {
                ProtoHelper.standard().nextDuplex("http-client", new HttpClientDuplex()).nextDuplex("client-route", this.newHttp1WebSocketRoute()).config(ctx);
            }
        };
    }

    private void configureHttp2WebSocketPartitions(ProtoPartitionBuilder<HttpObject, HttpObject> partition) {
        Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
        partition.policy(policy).byDefault(partitionCtx -> partitionCtx.addLast("h2-control-events", new Http2ObjectStreamManager(partition.control(), policy))).byInitializer(partitionCtx -> {
            final ProtoRoutingControl[] routingControl = new ProtoRoutingControl[1];
            ProtoRoutingBuilder<HttpObject, HttpObject> routing = ProtoHelper.<HttpObject, HttpObject>typedRoutingAsDefault(HttpRouteKey.BRANCH_H1, branchCtx -> {
                branchCtx.addLast("ws-over-http", new WebSocketClientUpgradeRouteDuplex(routingControl[0], this.config.getWebSocketVersion(), HttpRouteKey.BRANCH_SOCKET));
                branchCtx.addLast("http-agg", new HttpClientDuplexAggregator(this.config.getMaxContentLength()));
            }).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
                branchCtx.addLast("ws-frame", new WebSocketFrameDuplex(this.config.getWebSocketVersion()));
                branchCtx.addLast("ws-message", new WebSocketMessageDuplex());
            });
            routingControl[0] = routing.control();
            partitionCtx.addLast("client-route", routing.build());
        });
    }

    private ProtoDuplex<HttpObject, ?, ?, HttpObject> newHttp1WebSocketRoute() {
        final ProtoRoutingControl[] routingControl = new ProtoRoutingControl[1];
        ProtoRoutingBuilder<HttpObject, HttpObject> routing = ProtoHelper.<HttpObject, HttpObject>typedRoutingAsDefault(HttpRouteKey.BRANCH_H1, branchCtx -> {
            branchCtx.addLast("ws-over-http", new WebSocketClientUpgradeRouteDuplex(routingControl[0], this.config.getWebSocketVersion(), HttpRouteKey.BRANCH_SOCKET));
            branchCtx.addLastDecoder("http-aggregator", new HttpResponseAggregator(this.config.getMaxContentLength()));
        }).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, branchCtx -> {
            branchCtx.addLast("ws-frame", new WebSocketFrameDuplex(this.config.getWebSocketVersion()));
            branchCtx.addLast("ws-message", new WebSocketMessageDuplex());
        });
        routingControl[0] = routing.control();
        return routing.build();
    }

    private void safeClose(NetChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (Throwable ignored) {
        }
    }

    private void safeFailure(WebSocketListener listener, RealWebSocket webSocket, Throwable error) {
        try {
            listener.onFailure(webSocket, error);
        } catch (Throwable ignored) {
        }
    }

    private static String routeKey(URI uri) {
        if (uri == null) {
            return "unknown";
        }
        String host = StringUtils.trimToEmpty(uri.getHost());
        String scheme = StringUtils.trimToEmpty(uri.getScheme());
        int port = uri.getPort();
        if (port < 0) {
            if (StringUtils.equalsIgnoreCase("https", scheme) || StringUtils.equalsIgnoreCase("wss", scheme)) {
                port = 443;
            } else {
                port = 80;
            }
        }
        return scheme + "://" + host + ":" + port;
    }

    private static SslConfig copySslConfig(HttpClientConfig clientConfig, URI uri, HttpVersionPolicy versionPolicy) {
        SslConfig source = clientConfig.getSslConfig();
        SslConfig target = new SslConfig();

        if (source != null) {
            target.setAuthType(source.getAuthType());
            target.setJksResource(source.getJksResource());
            target.setPemCertChain(source.getPemCertChain());
            target.setPemPrivate(source.getPemPrivate());
            target.setKeyPassword(source.getKeyPassword());
            target.setCertChainDirect(source.getCertChainDirect());
            target.setPrivateKeyDirect(source.getPrivateKeyDirect());
            target.setSslContext(source.getSslContext());
            target.setKeyStore(source.getKeyStore());
            target.setKeyManagerFactory(source.getKeyManagerFactory());
            target.setTrustManagers(source.getTrustManagers());
            target.setTrustManagerFactory(source.getTrustManagerFactory());
            target.setSniHostName(source.getSniHostName());
            target.setProvider(source.getProvider());
            target.setClientAuth(source.getClientAuth());
            target.setCiphers(source.getCiphers());
            target.setProtocols(source.getProtocols());
        }

        if (target.getSslContext() == null && target.getKeyStore() == null && target.getKeyManagerFactory() == null && target.getAuthType() == null && target.getCertChainDirect() == null && target.getPrivateKeyDirect() == null && StringUtils.isBlank(target.getJksResource()) && StringUtils.isBlank(target.getPemCertChain()) && StringUtils.isBlank(target.getPemPrivate())) {
            try {
                KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                keyStore.load(null, null);
                target.setKeyStore(keyStore);
            } catch (Throwable e) {
                throw new IllegalStateException("init empty keyStore failed", e);
            }
        }

        if (StringUtils.isBlank(target.getSniHostName())) {
            target.setSniHostName(uri.getHost());
        }
        if (versionPolicy == HttpVersionPolicy.HTTP_2) {
            target.setAppProtocol(new String[] { "h2" });
            target.setDefaultAppProtocol("h2");
        } else if (versionPolicy == HttpVersionPolicy.HTTP_1_1) {
            target.setAppProtocol(new String[] { "http/1.1" });
            target.setDefaultAppProtocol("http/1.1");
        } else {
            target.setAppProtocol(new String[] { "h2", "http/1.1" });
            target.setDefaultAppProtocol("http/1.1");
        }
        return target;
    }

    private static class ProtocolPlan {
        private final boolean           secure;
        private final HttpVersionPolicy versionPolicy;
        private final HttpVersion       preferredVersion;
        private final InetSocketAddress remoteAddress;
        private final SslConfig         sslConfig;

        private ProtocolPlan(boolean secure, HttpVersionPolicy versionPolicy, HttpVersion preferredVersion, InetSocketAddress remoteAddress, SslConfig sslConfig) {
            this.secure = secure;
            this.versionPolicy = versionPolicy;
            this.preferredVersion = preferredVersion;
            this.remoteAddress = remoteAddress;
            this.sslConfig = sslConfig;
        }

        private static ProtocolPlan http(URI uri, HttpClientConfig clientConfig) {
            String scheme = uri.getScheme();
            if (!StringUtils.equalsIgnoreCase("http", scheme) && !StringUtils.equalsIgnoreCase("https", scheme)) {
                throw new IllegalArgumentException("unsupported HTTP scheme: " + scheme);
            }
            return resolve(uri, clientConfig, StringUtils.equalsIgnoreCase("https", scheme));
        }

        private static ProtocolPlan websocket(URI uri, HttpClientConfig clientConfig) {
            String scheme = uri.getScheme();
            if (!StringUtils.equalsIgnoreCase("ws", scheme) && !StringUtils.equalsIgnoreCase("wss", scheme)) {
                throw new IllegalArgumentException("unsupported WebSocket scheme: " + scheme);
            }
            return resolve(uri, clientConfig, StringUtils.equalsIgnoreCase("wss", scheme));
        }

        private static ProtocolPlan resolve(URI uri, HttpClientConfig clientConfig, boolean secure) {
            if (uri == null || StringUtils.isBlank(uri.getHost())) {
                throw new IllegalArgumentException("uri host must not be blank: " + uri);
            }
            HttpVersionPolicy versionPolicy = clientConfig.getVersionPolicy();
            HttpVersion preferredVersion = versionPolicy == HttpVersionPolicy.HTTP_2 ? HttpVersion.HTTP_2_0 : HttpVersion.HTTP_1_1;
            if (!secure && versionPolicy == HttpVersionPolicy.AUTO) {
                versionPolicy = HttpVersionPolicy.HTTP_1_1;
            }

            int port = uri.getPort();
            if (port < 0) {
                port = secure ? 443 : 80;
            }

            SslConfig sslConfig = secure ? copySslConfig(clientConfig, uri, versionPolicy) : null;
            return new ProtocolPlan(secure, versionPolicy, preferredVersion, new InetSocketAddress(uri.getHost(), port), sslConfig);
        }
    }
}
