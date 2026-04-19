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
import net.hasor.neta.channel.transport.udp.UdpSoConfig;
import net.hasor.nhttp.server.ServerConfig;

/**
 * HTTP/3 over QUIC (UDP) connector.
 *
 * <p>Binds to a UDP port and sets up the
 * {@code Http3FrameDuplexe → Http3ObjectDuplexe → HttpRequestHandler} pipeline.</p>
 *
 * @author 赵永春 (zyc@hasor.net)
 */
public class Http3Connector implements Connector {
    private volatile NetListen         listen;
    private volatile InetSocketAddress localAddress;

    @Override
    public String getProtocol() {
        return "h3";
    }

    @Override
    public void start(NetManager netManager, InetSocketAddress address, ServerConfig config, RequestDispatchCallback callback) throws Exception {
        ProtoInitializer pipeline = PipelineFactory.createHttp3Pipeline(config, callback);
        UdpSoConfig udpConfig = SoConfig.UDP();
        udpConfig.setRcvPacketSize(65535); // max UDP packet size for QUIC
        this.listen = netManager.bind(address, pipeline, udpConfig);
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
