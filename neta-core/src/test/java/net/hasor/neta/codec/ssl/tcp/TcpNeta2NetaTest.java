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
package net.hasor.neta.codec.ssl.tcp;

import static net.hasor.neta.codec.AbstractSoTest.*;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetListen;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.transport.tcp.TcpSoConfig;
import net.hasor.neta.codec.MyRcvToListProtoHandler;
import net.hasor.neta.codec.ssl.*;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class TcpNeta2NetaTest extends AbstractSslTest {
    @Test
    public void neta_2_neta_1() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        NetManager neta = new NetManager(globalConf());
        SslConfig sslConf = SoSslUtils.sslConfig(SslProtocol.TLS_v1_2);
        TcpSoConfig tcpConf = tcpConfig(128, 4096);

        List<String> serverRcvData = new ArrayList<>();
        List<String> clientRcvData = new ArrayList<>();
        ProtoInitializer clientProto = SoSslUtils.tcpSslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(clientRcvData));
        ProtoInitializer serverProto = SoSslUtils.tcpSslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(serverRcvData));

        // server
        NetListen listen = neta.bind(address, serverProto, tcpConf);

        // client
        NetChannel clientSide = neta.connectAsync(address, clientProto, tcpConf).get();
        listen.waitAnyAccept();
        NetChannel serverSide = (NetChannel) neta.getContext().findChannel(3);

        // shake hands
        SslContext clientSslCtx = SslUtils.getSslContextFromRoot(clientSide);
        SslContext serverSslCtx = SslUtils.getSslContextFromRoot(serverSide);

        // wait shake hands
        while (!clientSslCtx.isReady() || !serverSslCtx.isReady()) {
            ThreadUtils.sleep(100);
        }

        assert clientSide.getMonitor().getSndCounterBytes() > 0;
        assert serverSide.getMonitor().getSndCounterBytes() > 0;

        neta.shutdown();
    }

    @Test
    public void neta_2_neta_2() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        NetManager neta = new NetManager(globalConf());
        SslConfig sslConf = SoSslUtils.sslConfig(SslProtocol.TLS_v1_2);
        TcpSoConfig tcpConf = tcpConfig(128, 4096);

        List<String> serverRcvData = new ArrayList<>();
        List<String> clientRcvData = new ArrayList<>();
        ProtoInitializer clientProto = SoSslUtils.tcpSslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(clientRcvData));
        ProtoInitializer serverProto = SoSslUtils.tcpSslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(serverRcvData));

        // server
        NetListen listen = neta.bind(address, serverProto, tcpConf);

        // client
        NetChannel clientSide = neta.connectAsync(address, clientProto, tcpConf).get();
        assert clientSide.getChannelId() == 2;
        Future<?> send1 = clientSide.sendData("Hello Server, this message form client.\n");

        // server
        NetChannel serverSide = (NetChannel) neta.getContext().findChannel(3);
        Future<?> send2 = serverSide.sendData("Hello Client, this message form server.\n");

        // wait finish
        while (serverRcvData.isEmpty() || clientRcvData.isEmpty()) {
            ThreadUtils.sleep(100);
        }
        assert serverRcvData.get(0).equals("Hello Server, this message form client.");
        assert clientRcvData.get(0).equals("Hello Client, this message form server.");
        neta.shutdown();
    }
}