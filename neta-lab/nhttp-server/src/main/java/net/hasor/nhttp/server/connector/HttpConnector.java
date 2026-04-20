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
import net.hasor.neta.channel.NetListen;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SoConfig;
import net.hasor.neta.codec.http.websocket.WebSocketHandshakeAuthorizer;
import net.hasor.nhttp.server.ServerConfig;

/**
 * Plain-text HTTP connector supporting HTTP/1.1 and optionally h2c
 * (HTTP/2 cleartext prior-knowledge and upgrade).
 *
 * <p>When {@link ServerConfig#isHttp2Enabled()} is {@code true} the pipeline uses
 * {@code HttpAggregatorRoute} to detect the initial bytes and branches into:
 * <ul>
 *   <li>{@code BRANCH_H1} — regular HTTP/1.1 request flow</li>
 *   <li>{@code BRANCH_H2} — HTTP/2 prior-knowledge (PRI * HTTP/2.0)</li>
 *   <li>{@code BRANCH_H2C} — HTTP/1.1 Upgrade: h2c negotiation</li>
 * </ul>
 * When HTTP/2 is disabled the pipeline is plain HTTP/1.1 without protocol
 * detection overhead.</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public class HttpConnector implements Connector {
    private final WebSocketHandshakeAuthorizer wsAuthorizer;

    private volatile NetListen         listen;
    private volatile InetSocketAddress localAddress;

    /**
     * @param wsAuthorizer WebSocket authorizer supplied by the container layer;
     *                     may be {@code null} to reject all WebSocket upgrades
     */
    public HttpConnector(WebSocketHandshakeAuthorizer wsAuthorizer) {
        this.wsAuthorizer = wsAuthorizer;
    }

    @Override
    public String getProtocol() {
        return "http";
    }

    @Override
    public void start(NetManager netManager, InetSocketAddress address, ServerConfig config, RequestDispatchCallback callback) throws Exception {
        ProtoInitializer pipeline = PipelineFactory.createHttpPipeline(config, callback, false, this.wsAuthorizer);
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
}
