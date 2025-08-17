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
package net.hasor.neta.handler.codec.ssl.udp;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetListen;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.udp.UdpSoConfig;
import net.hasor.neta.handler.codec.ssl.*;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static net.hasor.neta.handler.codec.AbstractSoTest.*;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class UdpNeta2NetaTest extends AbstractSslTest {
    //    @Test
    public void neta_2_neta() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        NetManager neta = new NetManager(globalConf());
        SslConfig sslConf = SoSslUtils.sslConfig(SslProtocol.DTLS_v1_2, SslMode.Always);
        UdpSoConfig tcpConf = udpConfig(14096, 14096);
        tcpConf.setRcvPacketSize(14096);

        List<String> serverRcvData = new ArrayList<>();
        List<String> clientRcvData = new ArrayList<>();
        ProtoInitializer clientProto = SoSslUtils.sslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(clientRcvData));
        ProtoInitializer serverProto = SoSslUtils.sslSocketProtoStack(sslConf, new MyRcvToListProtoHandler(serverRcvData));

        // server start
        NetListen listen = neta.listen(address, serverProto, tcpConf);

        // client connect
        NetChannel clientSide = neta.connect(address, clientProto, tcpConf).get();
        assert clientSide.getChannelID() == 2;

        // client say hello
        Future<?> send1_1 = clientSide.sendData("Hello Server, this message form client.\n");
        Future<?> send1_2 = clientSide.sendData("Hello Server, this message form client.\n");

        // server accept data
        listen.waitAnyAccept();
        NetChannel serverSide = (NetChannel) neta.getContext().findChannel(3);
        while (serverRcvData.size() < 2) {
            ThreadUtils.sleep(100);
        }
        assert serverRcvData.get(0).equals("Hello Server, this message form client.");

        //
        serverRcvData.clear();
        while (serverRcvData.isEmpty()) {
            ThreadUtils.sleep(100);
        }
        assert serverRcvData.get(0).equals("Hello Server, this message form client.");
        //
        //
        //
        //

        //        assert serverRcvData.get(0).equals("Hello Server, this message form client.");
        //
        //        // server say hello
        //        Future<?> send2 = serverSide.sendData("Hello Client, this message form server.\n");
        //        send2.get();
        //
        //        // client accept data
        //        while (clientRcvData.isEmpty()) {
        //            ThreadUtils.sleep(100);
        //        }
        //        assert clientRcvData.get(0).equals("Hello Client, this message form server.");

        // finish
        neta.shutdown();
    }
}