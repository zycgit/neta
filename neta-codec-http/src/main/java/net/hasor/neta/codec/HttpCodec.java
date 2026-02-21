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
package net.hasor.neta.codec;

import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.codec.http.HttpClientDuplexe;
import net.hasor.neta.codec.http.HttpObjectAggregator;
import net.hasor.neta.codec.http.HttpServerDuplexe;
import net.hasor.neta.codec.http.websocket.WebSocketFrameDecoder;
import net.hasor.neta.codec.http.websocket.WebSocketFrameEncoder;
import net.hasor.neta.codec.http2.Http2ClientDuplexe;
import net.hasor.neta.codec.http2.Http2ServerDuplexe;
import net.hasor.neta.codec.http3.Http3ClientDuplexe;
import net.hasor.neta.codec.http3.Http3ServerDuplexe;

/**
 * Quick entry point for building HTTP-family protocol pipelines.
 * <p>
 * Provides static factory methods that return ready-to-use {@link ProtoInitializer}
 * for all HTTP-related protocols: HTTP/1.1, HTTP/2, HTTP/3, and WebSocket.
 * </p>
 * <h3>Usage examples:</h3>
 * <pre>{@code
 * // HTTP/1.1 server
 * NetManager neta = new NetManager();
 * neta.bind(address, HttpCodec.httpServer(), SoConfig.TCP());
 * // HTTP/2 client
 * NetChannel ch = neta.connectSync(address, HttpCodec.http2Client(), SoConfig.TCP());
 * // WebSocket server (after HTTP upgrade)
 * neta.bind(address, HttpCodec.websocketServer(), SoConfig.TCP());
 * }</pre>
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2024-01-15
 */
public final class HttpCodec {
    /** Default maximum content length for aggregation (1 MB). */
    public static final int DEFAULT_MAX_CONTENT_LENGTH = 1048576;

    private HttpCodec() {
    }

    // ========================= HTTP/1.1 =========================

    /**
     * Creates an HTTP/1.1 server pipeline (decode requests, encode responses)
     * with default settings and {@link HttpObjectAggregator}.
     */
    public static ProtoInitializer httpServer() {
        return httpServer(DEFAULT_MAX_CONTENT_LENGTH);
    }

    /**
     * Creates an HTTP/1.1 server pipeline with custom max content length.
     * @param maxContentLength maximum aggregated content size in bytes
     */
    public static ProtoInitializer httpServer(int maxContentLength) {
        return ctx -> {
            ctx.addLast("http", new HttpServerDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(maxContentLength));
        };
    }

    /**
     * Creates an HTTP/1.1 client pipeline (encode requests, decode responses)
     * with default settings and {@link HttpObjectAggregator}.
     */
    public static ProtoInitializer httpClient() {
        return httpClient(DEFAULT_MAX_CONTENT_LENGTH);
    }

    /**
     * Creates an HTTP/1.1 client pipeline with custom max content length.
     * @param maxContentLength maximum aggregated content size in bytes
     */
    public static ProtoInitializer httpClient(int maxContentLength) {
        return ctx -> {
            ctx.addLast("http", new HttpClientDuplexe());
            ctx.addLastDecoder("aggregator", new HttpObjectAggregator(maxContentLength));
        };
    }

    // ========================= HTTP/2 =========================

    /**
     * Creates an HTTP/2 server pipeline (decode HTTP/2 frames → HttpObject,
     * encode HttpObject → HTTP/2 frames).
     */
    public static ProtoInitializer http2Server() {
        return ctx -> {
            ctx.addLast("http2", new Http2ServerDuplexe());
        };
    }

    /**
     * Creates an HTTP/2 client pipeline.
     */
    public static ProtoInitializer http2Client() {
        return ctx -> {
            ctx.addLast("http2", new Http2ClientDuplexe());
        };
    }

    // ========================= HTTP/3 (QUIC + HTTP/3) =========================

    /**
     * Creates an HTTP/3 server pipeline (QUIC transport + HTTP/3 framing).
     * Registers both {@link net.hasor.neta.codec.http3.Http3Context} and
     * {@link net.hasor.neta.codec.quic.QuicContext} on the pipeline context.
     */
    public static ProtoInitializer http3Server() {
        return ctx -> {
            ctx.addLast("http3", new Http3ServerDuplexe());
        };
    }

    /**
     * Creates an HTTP/3 client pipeline.
     */
    public static ProtoInitializer http3Client() {
        return ctx -> {
            ctx.addLast("http3", new Http3ClientDuplexe());
        };
    }

    // ========================= WebSocket =========================

    /**
     * Creates a WebSocket server pipeline (decode/encode WebSocket frames).
     * <p>
     * Note: This pipeline handles WebSocket frames only. The HTTP upgrade
     * handshake should be handled separately using
     * {@link net.hasor.neta.codec.http.websocket.WebSocketServerHandshaker}.
     * </p>
     */
    public static ProtoInitializer websocketServer() {
        return ctx -> {
            ctx.addLast("websocket", new WebSocketFrameDecoder(), new WebSocketFrameEncoder());
        };
    }

    /**
     * Creates a WebSocket client pipeline (decode/encode WebSocket frames).
     * <p>
     * The client encoder produces masked frames as required by RFC 6455.
     * </p>
     */
    public static ProtoInitializer websocketClient() {
        return ctx -> {
            ctx.addLast("websocket", new WebSocketFrameDecoder(), new WebSocketFrameEncoder());
        };
    }
}
