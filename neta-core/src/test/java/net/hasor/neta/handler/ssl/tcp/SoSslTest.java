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
package net.hasor.neta.handler.ssl.tcp;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.NetConfig;
import net.hasor.neta.channel.tcp.TcpOptions;
import net.hasor.neta.handler.ssl.*;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static net.hasor.neta.channel.AbstractSoTest.*;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SoSslTest extends AbstractSslTest {
    @Test
    public void netaToNetaTest_01() throws IOException {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        TcpOptions tcpConf = tcpConfig(128, 4096);
        NetConfig soConf = globalConf();
        soConf.setNetlog(true);
        SslConfig sslConf = SoSslUtils.sslConfig(SslMode.Always);

        NetManager neta = new NetManager(soConf);

        // Server
        List<String> serverRcvData = new ArrayList<>();
        neta.listen(address, SoSslUtils.sslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(serverRcvData)), tcpConf);

        // Client
        List<String> clientRcvData = new ArrayList<>();
        Future<NetChannel> connect = neta.connect(address, SoSslUtils.sslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(clientRcvData)), tcpConf);

        //
        while (!connect.isDone()) {
            ThreadUtils.sleep(100);
        }

        NetChannel client = (NetChannel) neta.getContext().findChannel(2);
        NetChannel server = (NetChannel) neta.getContext().findChannel(3);

        Future<?> send1 = client.sendData("Hello Server, this message form client.\n");
        Future<?> send2 = server.sendData("Hello Client, this message form server.\n");

        while (serverRcvData.isEmpty() || clientRcvData.isEmpty()) {
            ThreadUtils.sleep(100);
        }

        assert serverRcvData.get(0).equals("Hello Server, this message form client.");
        assert clientRcvData.get(0).equals("Hello Client, this message form server.");

        neta.shutdown();
    }

    @Test
    public void sslModelManualTest_1() throws IOException {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        TcpOptions tcpConf = tcpConfig(128, 4096);
        NetConfig soConf = globalConf();
        soConf.setNetlog(true);
        SslConfig sslConf = SoSslUtils.sslConfig(SslMode.Manual);
        NetManager neta = new NetManager(soConf);

        // Server
        List<String> serverRcvData = new ArrayList<>();
        neta.listen(address, SoSslUtils.sslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(serverRcvData)), tcpConf);

        // Client
        List<String> clientRcvData = new ArrayList<>();
        Future<NetChannel> connect = neta.connect(address, SoSslUtils.sslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(clientRcvData)), tcpConf);
        while (!connect.isDone()) {
            ThreadUtils.sleep(100);
        }

        NetChannel client = (NetChannel) neta.getContext().findChannel(2);
        NetChannel server = (NetChannel) neta.getContext().findChannel(3);
        System.out.println("server:" + server.getChannelID() + ", client:" + client.getChannelID());

        // SSL disable
        clientRcvData.clear();
        serverRcvData.clear();
        client.sendData("Hello Server, this message no encryption.\n");
        server.sendData("Hello Client, this message no encryption.\n");
        while (serverRcvData.isEmpty() || clientRcvData.isEmpty()) {
            ThreadUtils.sleep(100);
        }

        assert clientRcvData.get(0).equals("Hello Client, this message no encryption.");
        assert serverRcvData.get(0).equals("Hello Server, this message no encryption.");

        // Switch to no encryption, there is keep connect, close SSL
        SslContext clientSSL = client.findProtoContext(SslContext.class);
        SslContext serverSSL = server.findProtoContext(SslContext.class);
        clientSSL.openSSL();
        serverSSL.openSSL();

        // SSL enable
        clientRcvData.clear();
        serverRcvData.clear();
        client.sendData("Hello Server, this message using encryption.\n");
        server.sendData("Hello Client, this message using encryption.\n");
        while (serverRcvData.isEmpty() || clientRcvData.isEmpty()) {
            ThreadUtils.sleep(100);
        }
        assert clientRcvData.get(0).equals("Hello Client, this message using encryption.");
        assert serverRcvData.get(0).equals("Hello Server, this message using encryption.");

        neta.shutdown();
    }
}