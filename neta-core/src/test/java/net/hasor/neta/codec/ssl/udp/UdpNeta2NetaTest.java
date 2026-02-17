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
package net.hasor.neta.codec.ssl.udp;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetListen;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.udp.UdpSoConfig;
import net.hasor.neta.codec.MyRcvToListProtoHandler;
import net.hasor.neta.codec.ssl.*;
import org.junit.Test;
import static net.hasor.neta.codec.AbstractSoTest.*;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class UdpNeta2NetaTest extends AbstractSslTest {
    @Test
    public void neta_2_neta_1() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        NetManager neta = new NetManager(globalConf());
        SslConfig sslConf = SoSslUtils.sslConfig(SslProtocol.DTLS_v1_2);
        UdpSoConfig udpConf = udpConfig(4096, 4096);
        udpConf.setRcvPacketSize(4096);

        List<String> serverRcvData = new ArrayList<>();
        List<String> clientRcvData = new ArrayList<>();
        ProtoInitializer clientProto = SoSslUtils.udpSslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(clientRcvData));
        ProtoInitializer serverProto = SoSslUtils.udpSslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(serverRcvData));

        // server
        NetListen listen = neta.bind(address, serverProto, udpConf);

        // client
        NetChannel clientSide = neta.connectAsync(address, clientProto, udpConf).get();
        listen.waitAnyAccept();
        NetChannel serverSide = (NetChannel) neta.getContext().findChannel(3);

        // wait shake hands
        SslContext clientSslCtx = SslUtils.getSslContext(clientSide);
        SslContext serverSslCtx = SslUtils.getSslContext(serverSide);
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
        SslConfig sslConf = SoSslUtils.sslConfig(SslProtocol.DTLS_v1_2);
        UdpSoConfig udpConf = udpConfig(4096, 4096);
        udpConf.setRcvPacketSize(4096);

        List<String> serverRcvData = new ArrayList<>();
        List<String> clientRcvData = new ArrayList<>();
        ProtoInitializer clientProto = SoSslUtils.udpSslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(clientRcvData));
        ProtoInitializer serverProto = SoSslUtils.udpSslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(serverRcvData));

        // server
        NetListen listen = neta.bind(address, serverProto, udpConf);

        // client
        NetChannel clientSide = neta.connectAsync(address, clientProto, udpConf).get();
        listen.waitAnyAccept();
        NetChannel serverSide = (NetChannel) neta.getContext().findChannel(3);

        // wait shake hands
        SslContext clientSslCtx = SslUtils.getSslContext(clientSide);
        SslContext serverSslCtx = SslUtils.getSslContext(serverSide);
        while (!clientSslCtx.isReady() || !serverSslCtx.isReady()) {
            ThreadUtils.sleep(100);
        }

        // round 1
        // client say hello
        System.out.println("client say hello");
        clientSide.sendData("Hello Server, this message 1 form client.\n");
        while (serverRcvData.isEmpty()) {
            ThreadUtils.sleep(100);
        }
        assert serverRcvData.get(0).equals("Hello Server, this message 1 form client.");

        // server say hello
        System.out.println("server say hello");
        serverSide.sendData("Hello Client, this message 1 form server.\n");
        while (clientRcvData.isEmpty()) {
            ThreadUtils.sleep(100);
        }
        assert clientRcvData.get(0).equals("Hello Client, this message 1 form server.");

        clientRcvData.clear();
        serverRcvData.clear();

        // round 2
        // client say hello
        System.out.println("client say hello");
        clientSide.sendData("Hello Server, this message 2 form client.\n");
        while (serverRcvData.isEmpty()) {
            ThreadUtils.sleep(100);
        }
        assert serverRcvData.get(0).equals("Hello Server, this message 2 form client.");

        // server say hello
        System.out.println("server say hello");
        serverSide.sendData("Hello Client, this message 2 form server.\n");
        while (clientRcvData.isEmpty()) {
            ThreadUtils.sleep(100);
        }
        assert clientRcvData.get(0).equals("Hello Client, this message 2 form server.");

        // finish
        neta.shutdown();
    }
}