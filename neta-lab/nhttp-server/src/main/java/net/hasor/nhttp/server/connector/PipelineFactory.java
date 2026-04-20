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
package net.hasor.nhttp.server.connector;

import net.hasor.cobble.logging.Logger;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.channel.routing.ProtoPartitionControl;
import net.hasor.neta.channel.routing.ProtoRoutingBuilder;
import net.hasor.neta.channel.routing.ProtoRoutingControl;
import net.hasor.neta.channel.routing.ProtoRoutingDataSelector;
import net.hasor.neta.codec.http.HttpServerDuplexe;
import net.hasor.neta.codec.http.h2.*;
import net.hasor.neta.codec.http.h3.Http3FrameDuplexe;
import net.hasor.neta.codec.http.h3.Http3ObjectDuplexe;
import net.hasor.neta.codec.http.h3.Http3Settings;
import net.hasor.neta.codec.http.routing.H2CUpgradeServerDuplexer;
import net.hasor.neta.codec.http.routing.Http2OverTlsRoute;
import net.hasor.neta.codec.http.routing.HttpAggregatorRoute;
import net.hasor.neta.codec.http.routing.HttpRouteKey;
import net.hasor.neta.codec.http.websocket.WebSocketFrameDuplexer;
import net.hasor.neta.codec.http.websocket.WebSocketHandshakeAuthorizer;
import net.hasor.neta.codec.http.websocket.WebSocketServerUpgradeRouteDuplexer;
import net.hasor.neta.codec.http.websocket.WebSocketVersion;
import net.hasor.neta.codec.ssl.SslDuplexer;
import net.hasor.nhttp.server.ServerConfig;

/**
 * Static factory for neta pipeline initializers.
 *
 * <p>Centralises all pipeline assembly logic previously scattered across
 * {@code NetaHttpServer}'s {@code createXxxInitializer()} private methods.
 * All methods return {@link ProtoInitializer} lambdas; they are stateless and
 * may be called multiple times.</p>
 *
 * <h3>Pipeline tree (plain HTTP with HTTP/2 enabled)</h3>
 * <pre>
 * [protocol-detect (HttpAggregatorRoute)]
 *   ├─ BRANCH_H1  → [HttpServerDuplexe] → [app-layer]
 *   ├─ BRANCH_H2  → [Http2FrameDuplexe] → [Http2ObjectDuplexe] → (per-stream) → [app-layer]
 *   └─ BRANCH_H2C → [HttpServerDuplexe] → [H2CUpgradeServerDuplexe]
 *
 * [app-layer]
 *   [ws-lifecycle (WebSocketLifecycleHandler)]
 *   [server-route (typedRoutingAsDefault HTTP)]
 *     ├─ HTTP  → [ws-upgrade] → [http-handler (HttpRequestHandler)]
 *     └─ SOCKET → [ws-frame (WebSocketFrameDuplexer)] → [ws-handler (WebSocketFrameHandler)]
 * </pre>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public final class PipelineFactory {
    private static final Logger logger = Logger.getLogger(PipelineFactory.class);

    private PipelineFactory() {
    }

    // =========================================================================
    // Application layer (shared by all protocol branches)
    // =========================================================================

    /**
     * Creates the shared application-layer pipeline that processes decoded
     * {@link net.hasor.neta.codec.http.HttpObject} streams.
     *
     * <p>The pipeline consists of:
     * <ol>
     *   <li>{@link WebSocketLifecycleHandler} — intercepts WS handshake events and
     *       connection-close notifications.</li>
     *   <li>An inner routing node that forks between HTTP and WebSocket branches.</li>
     * </ol>
     */
    static ProtoInitializer createApplicationPipeline(ServerConfig config, RequestDispatchCallback callback, boolean secure, WebSocketHandshakeAuthorizer wsAuthorizer) {
        return ctx -> {
            WebSocketLifecycleHandler wsLifecycle = new WebSocketLifecycleHandler(secure, callback);
            ctx.addLast("ws-lifecycle", wsLifecycle, wsLifecycle);

            // Inner routing: HTTP vs WebSocket
            final ProtoRoutingControl[] wsRoutingControlRef = new ProtoRoutingControl[1];
            ProtoRoutingBuilder<Object, Object> routing = ProtoHelper.typedRoutingAsDefault(HttpRouteKey.BRANCH_H1, httpCtx -> {
                // HTTP branch: optional WebSocket upgrade → streaming request handler
                httpCtx.addLast("ws-upgrade", new WebSocketServerUpgradeRouteDuplexer(wsRoutingControlRef[0], WebSocketVersion.V13, HttpRouteKey.BRANCH_SOCKET, wsAuthorizer));
                httpCtx.addLastDecoder("http-handler", new HttpRequestHandler(secure, config, callback));
            }).branchByInitializer(HttpRouteKey.BRANCH_SOCKET, socketCtx -> {
                // WebSocket branch: frame codec + frame dispatcher
                socketCtx.addLast("ws-frame", new WebSocketFrameDuplexer(WebSocketVersion.V13));
                socketCtx.addLastDecoder("ws-handler", new WebSocketFrameHandler());
            });
            wsRoutingControlRef[0] = routing.control();
            ctx.addLast("server-route", routing.build());
        };
    }

    // =========================================================================
    // HTTP/1.1 branch
    // =========================================================================

    /**
     * Creates an HTTP/1.1 branch pipeline.
     *
     * <p>Adds the HTTP server codec, then the shared application pipeline.</p>
     */
    static ProtoInitializer createHttp1BranchPipeline(ServerConfig config, RequestDispatchCallback callback, boolean secure, WebSocketHandshakeAuthorizer wsAuthorizer) {
        return ctx -> {
            ctx.addLast("http-codec", new HttpServerDuplexe(config.getMaxInitialLineLength(), config.getMaxHeaderSize(), config.getMaxChunkSize()));
            createApplicationPipeline(config, callback, secure, wsAuthorizer).config(ctx);
        };
    }

    // =========================================================================
    // H2C upgrade branch
    // =========================================================================

    /**
     * Creates the dedicated cleartext h2c-upgrade branch.
     *
     * <p>This branch exists only for requests already classified by
     * {@link HttpAggregatorRoute} as valid {@code Upgrade: h2c} exchanges. It must
     * stop at {@link H2CUpgradeServerDuplexer}; after a successful upgrade, stream 1
     * and all subsequent traffic are handled by the real HTTP/2 branch.</p>
     */
    static ProtoInitializer createH2cUpgradeBranchPipeline(ServerConfig config, ProtoRoutingControl routingControl) {
        return ctx -> {
            ctx.addLast("http-codec", new HttpServerDuplexe(config.getMaxInitialLineLength(), config.getMaxHeaderSize(), config.getMaxChunkSize()));
            ctx.addLast("h2c-upgrade", new H2CUpgradeServerDuplexer(routingControl));
        };
    }

    // =========================================================================
    // HTTP/2 branch
    // =========================================================================

    /**
     * Creates an HTTP/2 branch pipeline.
     *
     * <p>Adds the HTTP/2 frame and object codec, then a per-stream partition where
     * each stream gets its own application pipeline. The default partition handles
     * the stream management lifecycle; each non-default (data) stream gets the full
     * application pipeline.</p>
     */
    static ProtoInitializer createHttp2BranchPipeline(ServerConfig config, RequestDispatchCallback callback, boolean secure, ProtoRoutingControl routingControl, WebSocketHandshakeAuthorizer wsAuthorizer) {
        return ctx -> ProtoHelper.standard()//
                .nextDuplex("h2-frame", new Http2FrameDuplexe(true))//
                .nextDuplex("h2-message", new Http2ObjectDuplexe(true, routingControl))//
                .nextPartition("h2-stream", new Http2ObjectPartitionSelector(), partition -> {
                    Http2ObjectPartitionPolicy policy = new Http2ObjectPartitionPolicy();
                    ProtoPartitionControl partitionControl = partition.control();
                    partition.policy(policy).byDefault(partitionCtx -> {
                        partitionCtx.addLast("h2-control-lifecycle", new Http2ObjectStreamManager(partitionControl, policy));
                    }).byInitializer(createApplicationPipeline(config, callback, secure, wsAuthorizer));
                }).build().config(ctx);
    }

    // =========================================================================
    // Plain HTTP (H1 + H2 + H2C)
    // =========================================================================

    /**
     * Creates the full plain-HTTP pipeline.
     *
     * <p>When {@link ServerConfig#isHttp2Enabled()} is {@code true} the pipeline uses
     * {@code HttpAggregatorRoute} to detect the initial bytes and branches into H1,
     * H2, and H2C sub-pipelines. When HTTP/2 is disabled a plain HTTP/1.1 pipeline
     * is returned.</p>
     */
    public static ProtoInitializer createHttpPipeline(ServerConfig config, RequestDispatchCallback callback, boolean secure, WebSocketHandshakeAuthorizer wsAuthorizer) {
        if (!config.isHttp2Enabled()) {
            return ctx -> {
                ctx.addLastDecoder("connection-lifecycle", new ConnectionLifecycleHandler(callback));
                ctx.addLast("http-codec", new HttpServerDuplexe(config.getMaxInitialLineLength(), config.getMaxHeaderSize(), config.getMaxChunkSize()));
                createApplicationPipeline(config, callback, secure, wsAuthorizer).config(ctx);
            };
        }

        return ctx -> {
            ctx.addLastDecoder("connection-lifecycle", new ConnectionLifecycleHandler(callback));
            ProtoHelper.standard().nextRouteAsStatic("protocol-detect", new HttpAggregatorRoute(), routing -> {
                ProtoRoutingControl routingControl = routing.control();
                routing.branchByInitializer(HttpRouteKey.BRANCH_H1, createHttp1BranchPipeline(config, callback, secure, wsAuthorizer));
                routing.branchByInitializer(HttpRouteKey.BRANCH_H2, createHttp2BranchPipeline(config, callback, secure, routingControl, wsAuthorizer));
                routing.branchByInitializer(HttpRouteKey.BRANCH_H2C, createH2cUpgradeBranchPipeline(config, routingControl));
            }).config(ctx);
        };
    }

    // =========================================================================
    // HTTPS sub-pipelines
    // =========================================================================

    /**
     * Creates the HTTP → HTTPS redirect pipeline (for plaintext connections on the
     * HTTPS port). Only the initial {@link net.hasor.neta.codec.http.HttpRequest}
     * is needed; subsequent content chunks are discarded.
     */
    static ProtoInitializer createHttpsRedirectPipeline(ServerConfig config) {
        return ctx -> {
            ctx.addLast("http-codec", new HttpServerDuplexe(config.getMaxInitialLineLength(), config.getMaxHeaderSize(), config.getMaxChunkSize()));
            ctx.addLastDecoder("redirect-handler", new HttpsRedirectHandler(config.getServerName()));
        };
    }

    /**
     * Creates the TLS branch pipeline used after TLS detection on the HTTPS port.
     *
     * <p>Adds {@link SslDuplexer}, then routes by the negotiated ALPN protocol when
     * HTTP/2 is enabled. When HTTP/2 is disabled the pipeline falls directly into
     * HTTP/1.1 over TLS.</p>
     */
    static ProtoInitializer createHttpsTlsBranchPipeline(ServerConfig config, RequestDispatchCallback callback, WebSocketHandshakeAuthorizer wsAuthorizer) {
        return ctx -> {
            ctx.addLast("ssl", new SslDuplexer(config.getSslConfig()));

            if (!config.isHttp2Enabled()) {
                // HTTP/2 disabled — plain HTTP/1.1 over TLS
                ctx.addLast("http-codec", new HttpServerDuplexe(config.getMaxInitialLineLength(), config.getMaxHeaderSize(), config.getMaxChunkSize()));
                createApplicationPipeline(config, callback, true, wsAuthorizer).config(ctx);
                return;
            }

            // ALPN routing: h2 | http/1.1
            ProtoHelper.standard().nextRouteAsStatic("alpn", new Http2OverTlsRoute(), routing -> {
                ProtoRoutingControl routingControl = routing.control();
                routing.branchByInitializer(HttpRouteKey.BRANCH_H1, createHttp1BranchPipeline(config, callback, true, wsAuthorizer));
                routing.branchByInitializer(HttpRouteKey.BRANCH_H2, createHttp2BranchPipeline(config, callback, true, routingControl, wsAuthorizer));
            }).config(ctx);
        };
    }

    // =========================================================================
    // HTTPS ALPN (full)
    // =========================================================================

    /**
     * Creates the full HTTPS ALPN pipeline.
     *
     * <p>First detects whether the incoming connection is TLS or plaintext HTTP by
     * inspecting the first byte:
     * <ul>
     *   <li>0x14–0x17 → TLS → {@link #createHttpsTlsBranchPipeline}</li>
     *   <li>0x41–0x5A → plaintext HTTP → {@link #createHttpsRedirectPipeline}</li>
     *   <li>other → unknown protocol → connection closed</li>
     * </ul>
     */
    public static ProtoInitializer createHttpsAlpnPipeline(ServerConfig config, RequestDispatchCallback callback, WebSocketHandshakeAuthorizer wsAuthorizer) {
        return ctx -> {
            ctx.addLastDecoder("connection-lifecycle", new ConnectionLifecycleHandler(callback));
            ProtoHelper.standard().nextRouteAsStatic("tls-detect",//
                    (ProtoRoutingDataSelector<ByteBuf, ByteBuf>) (context, rcvUp, sndDown) -> {
                        if (rcvUp == null) {
                            return null;
                        }
                        ByteBuf first = rcvUp.peekMessage();
                        if (first == null || first.readableBytes() <= 0) {
                            return null;
                        }
                        int b = first.getByte(0) & 0xFF;
                        if (b >= 0x14 && b <= 0x17) {
                            return "tls";
                        }
                        if (b >= 0x41 && b <= 0x5A) {
                            return "plaintext";
                        }
                        return "unknown";
                    }, routing -> {
                        routing.branchByInitializer("tls", createHttpsTlsBranchPipeline(config, callback, wsAuthorizer));
                        routing.branchByInitializer("plaintext", createHttpsRedirectPipeline(config));
                        routing.branchByInitializer("unknown", unknownCtx -> unknownCtx.addLastDecoder("close", new ProtoHandler<ByteBuf, Object>() {
                            @Override
                            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<Object> dst) {
                                if (src.hasMore()) {
                                    ByteBuf buf = src.peekMessage();
                                    int len = Math.min(buf.readableBytes(), 32);
                                    StringBuilder hex = new StringBuilder();
                                    for (int i = 0; i < len; i++) {
                                        if (i > 0) {
                                            hex.append(' ');
                                        }
                                        hex.append(String.format("%02x", buf.getByte(i) & 0xFF));
                                    }
                                    logger.warn("reject(" + context.getChannel().getChannelId() + ") unknown protocol on HTTPS port, first " + len + " bytes: [" + hex + "]");
                                }
                                context.getChannel().close();
                                return ProtoStatus.Stop;
                            }

                            @Override
                            public ProtoStatus onError(ProtoContext context, Throwable e, ProtoExceptionHolder eh) {
                                context.getChannel().close();
                                eh.clear();
                                return ProtoStatus.Stop;
                            }
                        }));
                    }).config(ctx);
        };
    }

    // =========================================================================
    // HTTP/3
    // =========================================================================

    /**
     * Creates the HTTP/3 over QUIC (UDP) pipeline.
     *
     * <pre>
     * [Http3FrameDuplexe] → [Http3ObjectDuplexe] → [HttpRequestHandler]
     * </pre>
     */
    static ProtoInitializer createHttp3Pipeline(ServerConfig config, RequestDispatchCallback callback) {
        return ctx -> {
            Http3Settings h3Settings = Http3Settings.defaultLocalSettings(true).maxFieldSectionSize(config.getMaxHeaderSize());
            ctx.addLast("h3-frame", new Http3FrameDuplexe(true, h3Settings));
            ctx.addLast("h3-object", new Http3ObjectDuplexe(true, h3Settings));
            // HTTP/3 uses per-stream partitioning like HTTP/2; HttpRequestHandler handles the stream
            ctx.addLastDecoder("h3-handler", new HttpRequestHandler(true, config, callback));
        };
    }
}
