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
package net.hasor.neta.channel.transport.udp;

import static net.hasor.neta.channel.AbstractSoTest.globalConf;
import static net.hasor.neta.channel.AbstractSoTest.safePort;

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
import net.hasor.neta.codec.HandlerUtils;
import net.hasor.neta.codec.MyRcvToListProtoHandler;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class UdpNeta2NetaTest {
    @Test
    public void udpWrite_Test() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        List<String> serverRcvData = new ArrayList<>();
        List<String> clientRcvData = new ArrayList<>();
        ProtoInitializer clientProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(clientRcvData));
        ProtoInitializer serverProto = HandlerUtils.addListProtoStack(new MyRcvToListProtoHandler(serverRcvData));
        NetManager neta = new NetManager(globalConf());

        UdpSoConfig udpConfig = UdpSoConfig.UDP();
        udpConfig.setRcvPacketSize(4096);

        // server
        NetListen listen = neta.bind(address, serverProto, udpConfig);

        // client
        Future<NetChannel> connect = neta.connectAsync(address, clientProto, udpConfig);
        NetChannel clientSite = connect.get();
        Future<?> send1 = clientSite.sendData("Hello Server, this message form client.\n");

        // server
        listen.waitAnyAccept();
        NetChannel server = (NetChannel) neta.getContext().findChannel(3);
        Future<?> send2 = server.sendData("Hello Client, this message form server.\n");

        // wait finish
        int i = 100 * 50; // max 5sec
        while ((serverRcvData.isEmpty() || clientRcvData.isEmpty()) && i-- > 0) {
            ThreadUtils.sleep(100);
        }

        assert serverRcvData.get(0).equals("Hello Server, this message form client.");
        assert clientRcvData.get(0).equals("Hello Client, this message form server.");

        neta.shutdown();
    }
}