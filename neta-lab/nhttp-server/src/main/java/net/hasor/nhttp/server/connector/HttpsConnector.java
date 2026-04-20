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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.hasor.neta.channel.NetListen;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SoConfig;
import net.hasor.neta.codec.http.websocket.WebSocketHandshakeAuthorizer;
import net.hasor.neta.codec.ssl.SslConfig;
import net.hasor.nhttp.server.ServerConfig;

/**
 * HTTPS connector with TLS detection and ALPN-based protocol negotiation.
 *
 * <p>Listens on a single TCP port and handles two connection types detected by
 * examining the first byte of the stream:
 * <ul>
 *   <li><b>TLS</b> (first byte 0x14–0x17) — SslDuplexer → ALPN-route h2 / http/1.1</li>
 *   <li><b>Plaintext HTTP</b> (first byte 0x41–0x5A, uppercase letter) — HttpServerDuplexe →
 *       {@link HttpsRedirectHandler} (301 redirect to https://…)</li>
 *   <li><b>Unknown</b> — connection is closed immediately</li>
 * </ul>
 * ALPN negotiation order: h2 (preferred when {@link ServerConfig#isHttp2Enabled()}) then
 * http/1.1 as fallback.</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public class HttpsConnector implements Connector {
    private final WebSocketHandshakeAuthorizer wsAuthorizer;

    private volatile NetListen         listen;
    private volatile InetSocketAddress localAddress;

    /**
     * @param wsAuthorizer WebSocket authorizer supplied by the container layer;
     *                     may be {@code null} to reject all WebSocket upgrades
     */
    public HttpsConnector(WebSocketHandshakeAuthorizer wsAuthorizer) {
        this.wsAuthorizer = wsAuthorizer;
    }

    @Override
    public String getProtocol() {
        return "https";
    }

    @Override
    public void start(NetManager netManager, InetSocketAddress address, ServerConfig config, RequestDispatchCallback callback) throws Exception {
        SslConfig sslConfig = Objects.requireNonNull(config.getSslConfig(), "SslConfig is required for HttpsConnector");

        // Configure ALPN on SslConfig
        configureAlpn(sslConfig, config.isHttp2Enabled());

        ProtoInitializer pipeline = PipelineFactory.createHttpsAlpnPipeline(config, callback, this.wsAuthorizer);
        this.listen = netManager.bind(address, pipeline, SoConfig.TCP());
        this.localAddress = (InetSocketAddress) this.listen.getLocalAddr();
    }

    @Override
    public void stop() {
        NetListen l = this.listen;
        if (l != null) {
            l.close();
            this.listen = null;
        }
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return this.localAddress;
    }

    // -------------------------------------------------------------------------

    private static void configureAlpn(SslConfig sslConfig, boolean http2Enabled) {
        List<String> protocols = new ArrayList<>();
        if (http2Enabled) {
            protocols.add("h2");
        }
        protocols.add("http/1.1");

        sslConfig.setAppProtocol(protocols.toArray(new String[0]));
        sslConfig.setAppProtocolSelector((channel, clientProtocols) -> {
            if (http2Enabled && clientProtocols.contains("h2")) {
                return "h2";
            }
            return "http/1.1";
        });
    }
}
