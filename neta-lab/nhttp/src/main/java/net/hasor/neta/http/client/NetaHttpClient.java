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
package net.hasor.neta.http.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import net.hasor.nhttp.request.HttpObjectWriter;
import net.hasor.nhttp.request.HttpWriter;
import net.hasor.nhttp.request.Request;

/**
 * High-level HTTP/WebSocket client built on Neta.
 * @author 赵永春 (zyc@hasor.net)
 */
public class NetaHttpClient implements Closeable {
    private final NetManager       netManager;
    private final HttpClientConfig config;
    private final HttpWriter       requestWriter;

    public NetaHttpClient() {
        this(new HttpClientConfig());
    }

    public NetaHttpClient(HttpClientConfig config) {
        this.config = config == null ? new HttpClientConfig() : config;
        this.netManager = new NetManager();
        this.requestWriter = new HttpObjectWriter();
    }

    public HttpClientResponse execute(Request request) throws IOException {
        return this.execute(request, this.config.getTimeoutMillis());
    }

    public HttpClientResponse execute(Request request, long timeoutMillis) throws IOException {
        ProtocolPlan plan = ProtocolPlan.http(request.getUri(), this.config);
        NetChannel channel = null;
        SubscribeHolder subscribeHolder = null;
        BasicFuture<HttpClientResponse> responseFuture = new BasicFuture<>();
        try {
            channel = this.netManager.connectSync(plan.remoteAddress, this.httpInitializer(plan), SoConfig.TCP());
            this.awaitTlsReady(channel, plan, timeoutMillis);
            subscribeHolder = this.subscribeHttpResponse(channel, request.getUri(), responseFuture);
            channel.sendData(this.requestWriter.write(request, this.resolveRequestVersion(channel, plan))).get(timeoutMillis, TimeUnit.MILLISECONDS);
            return responseFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            throw this.asIOException(e);
        } finally {
            if (subscribeHolder != null) {
                subscribeHolder.unSubscribe();
            }
            this.safeClose(channel);
        }
    }

    public Future<HttpClientResponse> executeAsync(Request request) {
        return this.executeAsync(request, null);
    }

    public Future<HttpClientResponse> executeAsync(Request request, HttpClientCallback callback) {
        BasicFuture<HttpClientResponse> result = new BasicFuture<>();
        ProtocolPlan plan = ProtocolPlan.http(request.getUri(), this.config);

        result.onCompleted(future -> {
            if (callback != null) {
                callback.onSuccess(future.getResult());
            }
        }).onFailed(future -> {
            if (callback != null) {
                callback.onFailure(future.getCause());
            }
        });

        this.netManager.connectAsync(plan.remoteAddress, this.httpInitializer(plan), SoConfig.TCP()).onCompleted(connectFuture -> {
            NetChannel channel = connectFuture.getResult();
            final SubscribeHolder[] subscription = new SubscribeHolder[1];
            result.onFinal(done -> {
                if (subscription[0] != null) {
                    subscription[0].unSubscribe();
                }
                this.safeClose(channel);
            });

            try {
                this.awaitTlsReady(channel, plan, this.config.getTimeoutMillis());
                subscription[0] = this.subscribeHttpResponse(channel, request.getUri(), result);
                channel.sendData(this.requestWriter.write(request, this.resolveRequestVersion(channel, plan))).onFailed(sendFuture -> result.failed(sendFuture.getCause()));
            } catch (Throwable e) {
                result.failed(e);
            }
        }).onFailed(connectFuture -> result.failed(connectFuture.getCause()));

        return result;
    }

    public WebSocketClientSession openWebSocket(String uri, WebSocketClientHandler handler) throws IOException {
        return this.openWebSocket(URI.create(uri), handler, this.config.getTimeoutMillis());
    }

    public WebSocketClientSession openWebSocket(URI uri, WebSocketClientHandler handler, long timeoutMillis) throws IOException {
        try {
            return this.openWebSocketAsync(uri, handler, timeoutMillis).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            throw this.asIOException(e);
        }
    }

    public Future<WebSocketClientSession> openWebSocketAsync(String uri, WebSocketClientHandler handler) {
        return this.openWebSocketAsync(URI.create(uri), handler, this.config.getTimeoutMillis());
    }

    public Future<WebSocketClientSession> openWebSocketAsync(URI uri, WebSocketClientHandler handler, long timeoutMillis) {
        ProtocolPlan plan = ProtocolPlan.websocket(uri, this.config);
        BasicFuture<WebSocketClientSession> result = new BasicFuture<>();
        WebSocketClientHandler actualHandler = handler == null ? new WebSocketClientHandler() {
        } : handler;

        this.netManager.connectAsync(plan.remoteAddress, this.websocketInitializer(plan), SoConfig.TCP()).onCompleted(connectFuture -> {
            NetChannel channel = connectFuture.getResult();
            final SubscribeHolder[] subscription = new SubscribeHolder[1];
            final AtomicReference<ClientWebSocketSession> sessionRef = new AtomicReference<>();

            result.onFailed(done -> {
                if (subscription[0] != null) {
                    subscription[0].unSubscribe();
                }
                this.safeClose(channel);
                this.safeOnError(actualHandler, sessionRef.get(), done.getCause());
            });

            try {
                this.awaitTlsReady(channel, plan, timeoutMillis);
                subscription[0] = channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, payload -> this.dispatchWebSocketPayload(uri, channel, actualHandler, sessionRef, subscription, result, payload));
                FullHttpRequest handshakeRequest = this.websocketHandshake(uri, channel, plan);
                channel.sendData(handshakeRequest).onFailed(sendFuture -> result.failed(sendFuture.getCause()));
            } catch (Throwable e) {
                result.failed(e);
            }
        }).onFailed(connectFuture -> {
            Throwable error = connectFuture.getCause();
            result.failed(error);
            this.safeOnError(actualHandler, null, error);
        });

        return result;
    }

    @Override
    public void close() throws IOException {
        this.netManager.shutdown();
    }

    private SubscribeHolder subscribeHttpResponse(NetChannel channel, URI uri, BasicFuture<HttpClientResponse> responseFuture) {
        return channel.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, payload -> {
            if (payload.getError() != null) {
                responseFuture.failed(payload.getError());
                return;
            }

            Object message = payload.getData();
            if (message instanceof FullHttpResponse) {
                responseFuture.completed(HttpClientResponse.of(uri, (FullHttpResponse) message));
            }
        });
    }

    private void dispatchWebSocketPayload(URI uri, NetChannel channel, WebSocketClientHandler handler, AtomicReference<ClientWebSocketSession> sessionRef, SubscribeHolder[] subscription, BasicFuture<WebSocketClientSession> result, PlayLoad payload) {
        if (payload.getError() != null) {
            if (!result.isDone()) {
                result.failed(payload.getError());
            } else {
                this.safeOnError(handler, sessionRef.get(), payload.getError());
            }
            return;
        }

        Object message = payload.getData();
        if (message instanceof WebSocketHandshakeEvent) {
            WebSocketHandshakeEvent handshakeEvent = (WebSocketHandshakeEvent) message;
            ClientWebSocketSession session = new ClientWebSocketSession(uri, channel, handshakeEvent.streamId(), handshakeEvent.subProtocol());
            sessionRef.set(session);
            result.completed(session);
            this.safeOnOpen(handler, session);
            handshakeEvent.release();
            return;
        }

        if (message instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) message;
            IOException error = new IOException("websocket handshake rejected: " + response.status().code() + " " + response.status().reasonPhrase());
            response.release();
            if (!result.isDone()) {
                result.failed(error);
            } else {
                this.safeOnError(handler, sessionRef.get(), error);
            }
            return;
        }

        ClientWebSocketSession session = sessionRef.get();
        if (message instanceof TextWebSocketMessage) {
            TextWebSocketMessage textMessage = (TextWebSocketMessage) message;
            try {
                this.safeOnText(handler, session, new String(textMessage.content().asByteArray(), StandardCharsets.UTF_8));
            } finally {
                textMessage.release();
            }
            return;
        }

        if (message instanceof BinaryWebSocketMessage) {
            BinaryWebSocketMessage binaryMessage = (BinaryWebSocketMessage) message;
            try {
                this.safeOnBinary(handler, session, binaryMessage.content().asByteArray());
            } finally {
                binaryMessage.release();
            }
            return;
        }

        if (message instanceof PingWebSocketEvent) {
            PingWebSocketEvent pingEvent = (PingWebSocketEvent) message;
            try {
                this.safeOnPing(handler, session, pingEvent.content().asByteArray());
            } finally {
                pingEvent.release();
            }
            return;
        }

        if (message instanceof PongWebSocketEvent) {
            PongWebSocketEvent pongEvent = (PongWebSocketEvent) message;
            try {
                this.safeOnPong(handler, session, pongEvent.content().asByteArray());
            } finally {
                pongEvent.release();
            }
            return;
        }

        if (message instanceof WebSocketCloseEvent) {
            WebSocketCloseEvent closeEvent = (WebSocketCloseEvent) message;
            try {
                if (session != null) {
                    session.markClosed();
                }
                this.safeOnClose(handler, session, closeEvent.statusCode(), closeEvent.reason());
            } finally {
                closeEvent.release();
                if (subscription[0] != null) {
                    subscription[0].unSubscribe();
                }
                this.safeClose(channel);
            }
        }
    }

    private FullHttpRequest websocketHandshake(URI uri, NetChannel channel, ProtocolPlan plan) throws IOException {
        HttpVersion requestVersion = this.resolveRequestVersion(channel, plan);
        if (requestVersion.majorVersion() == 2) {
            return WebSocketUtils.createHttp2Handshake(this.config.getWebSocketVersion(), uri.toString());
        }
        return WebSocketUtils.createHandshake(this.config.getWebSocketVersion(), uri.toString());
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
                    ProtoHelper.standard()//
                            .nextDuplex("ssl", new SslDuplex(plan.sslConfig))//
                            .nextRouteAsStatic("alpn", new Http2OverTlsRoute(), routing -> {
                                routing.branch(HttpRouteKey.BRANCH_H1, branch -> branch//
                                        .nextDuplex("http-codec", new HttpClientDuplex())//
                                        .nextDecoder("http-aggregator", new HttpResponseAggregator(this.config.getMaxContentLength())));
                                routing.branch(HttpRouteKey.BRANCH_H2, branch -> branch//
                                        .nextDuplex("h2-frame", new Http2FrameDuplex(false))//
                                        .nextDuplex("h2-message", new Http2ObjectDuplex(false))//
                                        .nextDuplex("h2-aggregator", new HttpClientDuplexAggregator(this.config.getMaxContentLength())));
                            }).config(ctx);
                } else if (plan.versionPolicy == HttpVersionPolicy.HTTP_2) {
                    ProtoHelper.standard()//
                            .nextDuplex("ssl", new SslDuplex(plan.sslConfig))//
                            .nextDuplex("h2-frame", new Http2FrameDuplex(false))//
                            .nextDuplex("h2-message", new Http2ObjectDuplex(false))//
                            .nextDuplex("h2-aggregator", new HttpClientDuplexAggregator(this.config.getMaxContentLength()))//
                            .config(ctx);
                } else {
                    ProtoHelper.standard()//
                            .nextDuplex("ssl", new SslDuplex(plan.sslConfig))//
                            .nextDuplex("http-codec", new HttpClientDuplex())//
                            .nextDecoder("http-aggregator", new HttpResponseAggregator(this.config.getMaxContentLength()))//
                            .config(ctx);
                }
                return;
            }

            if (plan.versionPolicy == HttpVersionPolicy.HTTP_2) {
                ProtoHelper.standard()//
                        .nextDuplex("h2-frame", new Http2FrameDuplex(false))//
                        .nextDuplex("h2-message", new Http2ObjectDuplex(false))//
                        .nextDuplex("h2-aggregator", new HttpClientDuplexAggregator(this.config.getMaxContentLength()))//
                        .config(ctx);
            } else {
                ProtoHelper.standard()//
                        .nextDuplex("http-codec", new HttpClientDuplex())//
                        .nextDecoder("http-aggregator", new HttpResponseAggregator(this.config.getMaxContentLength()))//
                        .config(ctx);
            }
        };
    }

    private ProtoInitializer websocketInitializer(ProtocolPlan plan) {
        return ctx -> {
            if (plan.secure && plan.versionPolicy == HttpVersionPolicy.AUTO) {
                ProtoHelper.standard()//
                        .nextDuplex("ssl", new SslDuplex(plan.sslConfig))//
                        .nextRouteAsStatic("alpn", new Http2OverTlsRoute(), routing -> {
                            routing.branch(HttpRouteKey.BRANCH_H1, branch -> branch//
                                    .nextDuplex("http-client", new HttpClientDuplex())//
                                    .nextDuplex("client-route", this.newHttp1WebSocketRoute()));
                            routing.branch(HttpRouteKey.BRANCH_H2, branch -> branch//
                                    .nextDuplex("h2-frame", new Http2FrameDuplex(false))//
                                    .nextDuplex("h2-message", new Http2ObjectDuplex(false))//
                                    .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), this::configureHttp2WebSocketPartitions));
                        }).config(ctx);
                return;
            }

            if (plan.secure) {
                if (plan.versionPolicy == HttpVersionPolicy.HTTP_2) {
                    ProtoHelper.standard()//
                            .nextDuplex("ssl", new SslDuplex(plan.sslConfig))//
                            .nextDuplex("h2-frame", new Http2FrameDuplex(false))//
                            .nextDuplex("h2-message", new Http2ObjectDuplex(false))//
                            .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), partition -> this.configureHttp2WebSocketPartitions(partition))//
                            .config(ctx);
                } else {
                    ProtoHelper.standard()//
                            .nextDuplex("ssl", new SslDuplex(plan.sslConfig))//
                            .nextDuplex("http-client", new HttpClientDuplex())//
                            .nextDuplex("client-route", this.newHttp1WebSocketRoute())//
                            .config(ctx);
                }
                return;
            }

            if (plan.versionPolicy == HttpVersionPolicy.HTTP_2) {
                ProtoHelper.standard()//
                        .nextDuplex("h2-frame", new Http2FrameDuplex(false))//
                        .nextDuplex("h2-message", new Http2ObjectDuplex(false))//
                        .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), partition -> this.configureHttp2WebSocketPartitions(partition))//
                        .config(ctx);
            } else {
                ProtoHelper.standard()//
                        .nextDuplex("http-client", new HttpClientDuplex())//
                        .nextDuplex("client-route", this.newHttp1WebSocketRoute())//
                        .config(ctx);
            }
        };
    }

    private void configureHttp2WebSocketPartitions(ProtoPartitionBuilder<HttpObject, HttpObject> partition) {
        Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
        partition.policy(policy).byDefault(partitionCtx -> {
            partitionCtx.addLast("h2-control-events", new Http2ObjectStreamManager(partition.control(), policy));
        }).byInitializer(partitionCtx -> {
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

    private IOException asIOException(Throwable throwable) {
        if (throwable instanceof ExecutionException) {
            return this.asIOException(throwable.getCause());
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

    private void safeClose(NetChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close().await();
        } catch (Throwable ignored) {
        }
    }

    private void safeOnOpen(WebSocketClientHandler handler, WebSocketClientSession session) {
        try {
            handler.onOpen(session);
        } catch (Throwable ignored) {
        }
    }

    private void safeOnText(WebSocketClientHandler handler, WebSocketClientSession session, String message) {
        try {
            handler.onText(session, message);
        } catch (Throwable ignored) {
        }
    }

    private void safeOnBinary(WebSocketClientHandler handler, WebSocketClientSession session, byte[] data) {
        try {
            handler.onBinary(session, data);
        } catch (Throwable ignored) {
        }
    }

    private void safeOnPing(WebSocketClientHandler handler, WebSocketClientSession session, byte[] data) {
        try {
            handler.onPing(session, data);
        } catch (Throwable ignored) {
        }
    }

    private void safeOnPong(WebSocketClientHandler handler, WebSocketClientSession session, byte[] data) {
        try {
            handler.onPong(session, data);
        } catch (Throwable ignored) {
        }
    }

    private void safeOnClose(WebSocketClientHandler handler, WebSocketClientSession session, int statusCode, String reason) {
        try {
            handler.onClose(session, statusCode, reason);
        } catch (Throwable ignored) {
        }
    }

    private void safeOnError(WebSocketClientHandler handler, WebSocketClientSession session, Throwable error) {
        try {
            handler.onError(session, error);
        } catch (Throwable ignored) {
        }
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

    private static final class ProtocolPlan {
        private final URI               uri;
        private final boolean           secure;
        private final HttpVersionPolicy versionPolicy;
        private final HttpVersion       preferredVersion;
        private final InetSocketAddress remoteAddress;
        private final SslConfig         sslConfig;

        private ProtocolPlan(URI uri, boolean secure, HttpVersionPolicy versionPolicy, HttpVersion preferredVersion, InetSocketAddress remoteAddress, SslConfig sslConfig) {
            this.uri = uri;
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
            if (StringUtils.isBlank(uri.getHost())) {
                throw new IllegalArgumentException("uri host must not be blank: " + uri);
            }

            HttpVersionPolicy versionPolicy = clientConfig.getVersionPolicy();
            HttpVersion preferredVersion;
            if (secure) {
                preferredVersion = versionPolicy == HttpVersionPolicy.HTTP_2 ? HttpVersion.HTTP_2_0 : HttpVersion.HTTP_1_1;
            } else {
                preferredVersion = versionPolicy == HttpVersionPolicy.HTTP_2 ? HttpVersion.HTTP_2_0 : HttpVersion.HTTP_1_1;
                if (versionPolicy == HttpVersionPolicy.AUTO) {
                    versionPolicy = HttpVersionPolicy.HTTP_1_1;
                }
            }

            int port = uri.getPort();
            if (port < 0) {
                port = secure ? 443 : 80;
            }

            SslConfig sslConfig = null;
            if (secure) {
                sslConfig = copySslConfig(clientConfig, uri, versionPolicy);
            }
            return new ProtocolPlan(uri, secure, versionPolicy, preferredVersion, new InetSocketAddress(uri.getHost(), port), sslConfig);
        }
    }

    private static final class ClientWebSocketSession implements WebSocketClientSession {
        private final URI        uri;
        private final NetChannel channel;
        private final long       streamId;
        private final String     subProtocol;
        private volatile boolean open = true;

        private ClientWebSocketSession(URI uri, NetChannel channel, long streamId, String subProtocol) {
            this.uri = uri;
            this.channel = channel;
            this.streamId = streamId;
            this.subProtocol = subProtocol;
        }

        @Override
        public URI uri() {
            return this.uri;
        }

        @Override
        public long streamId() {
            return this.streamId;
        }

        @Override
        public String subProtocol() {
            return this.subProtocol;
        }

        @Override
        public boolean isOpen() {
            return this.open && !this.channel.isClose();
        }

        @Override
        public void sendText(String message) throws IOException {
            this.send(WebSocketUtils.textMessage(ByteBuf.wrap((message == null ? "" : message).getBytes(StandardCharsets.UTF_8))).streamId(this.streamId));
        }

        @Override
        public void sendBinary(byte[] data) throws IOException {
            this.send(WebSocketUtils.binaryMessage(ByteBuf.wrap(data == null ? new byte[0] : data)).streamId(this.streamId));
        }

        @Override
        public void sendPing(byte[] data) {
            PingWebSocketEvent event = WebSocketUtils.pingEvent(ByteBuf.wrap(data == null ? new byte[0] : data));
            event.streamId(this.streamId);
            this.channel.fireEvent(PingWebSocketEvent.class, event);
        }

        @Override
        public void sendPong(byte[] data) {
            PongWebSocketEvent event = WebSocketUtils.pongEvent(ByteBuf.wrap(data == null ? new byte[0] : data));
            event.streamId(this.streamId);
            this.channel.fireEvent(PongWebSocketEvent.class, event);
        }

        @Override
        public void close() throws IOException {
            this.close(1000, "normal closure");
        }

        @Override
        public void close(int statusCode, String reason) throws IOException {
            this.open = false;
            this.send(WebSocketUtils.closeFrame(statusCode, reason).streamId(this.streamId));
            this.channel.close().await();
        }

        private void send(Object message) throws IOException {
            try {
                this.channel.sendData(message).get();
            } catch (Throwable e) {
                throw e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }

        private void markClosed() {
            this.open = false;
        }
    }
}