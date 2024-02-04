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
package net.hasor.neta.handler.ssl;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetaSocket;
import net.hasor.neta.channel.SoConfig;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static net.hasor.neta.channel.AbstractSoTest.crateConfig;
import static net.hasor.neta.channel.AbstractSoTest.safePort;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SoSslTest extends AbstractSslTest {
    @Test
    public void netaToNetaTest_01() throws IOException {
        int safePort = safePort();
        SoConfig soConf = crateConfig(128, 4096);
        soConf.setNetlog(true);
        SslConfig sslConf = SoSslUtils.sslConfig(SslMode.Always);
        NetaSocket neta = new NetaSocket(soConf);

        // Server
        List<String> serverRcvData = new ArrayList<>();
        neta.listen("127.0.0.1", safePort, SoSslUtils.sslSocketPipeline(sslConf, new MyRcvToListPipeHandler(serverRcvData)));

        // Client
        List<String> clientRcvData = new ArrayList<>();
        Future<NetChannel> connect = neta.connect("127.0.0.1", safePort, SoSslUtils.sslSocketPipeline(sslConf, new MyRcvToListPipeHandler(clientRcvData)));

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

    //    @Test
    //    public void sslModelAlwaysTest_1() throws IOException {
    //        int safePort = safePort();
    //        SoConfig soConf = crateConfig(128, 4096);
    //        soConf.setNetlog(true);
    //        SslConfig sslConf = SoSslUtils.sslConfig(SslMode.Once);
    //        NetaSocket neta = new NetaSocket(soConf);
    //
    //        // Server
    //        List<String> serverRcvData = new ArrayList<>();
    //        neta.listen("127.0.0.1", safePort, SoSslUtils.sslSocketPipeline(sslConf, new MyRcvToListPipeHandler(serverRcvData)));
    //
    //        // Client
    //        List<String> clientRcvData = new ArrayList<>();
    //        Future<NetChannel> connect = neta.connect("127.0.0.1", safePort, SoSslUtils.sslSocketPipeline(sslConf, new MyRcvToListPipeHandler(clientRcvData)));
    //
    //        //
    //        while (!connect.isDone()) {
    //            ThreadUtils.sleep(100);
    //        }
    //
    //        NetChannel client = (NetChannel) neta.getContext().findChannel(2);
    //        NetChannel server = (NetChannel) neta.getContext().findChannel(3);
    //        System.out.println("server:" + server.getChannelID() + ", client:" + client.getChannelID());
    //
    //        // SSL enable
    //        Future<?> future1 = server.sendData("Hello Client, this message form server.\n");
    //        Future<?> future2 = client.sendData("Hello Server, this message form client.\n");
    //        while (serverRcvData.isEmpty() || clientRcvData.isEmpty()) {
    //            ThreadUtils.sleep(100);
    //        }
    //        assert clientRcvData.get(0).equals("Hello Client, this message form server.");
    //        assert serverRcvData.get(0).equals("Hello Server, this message form client.");
    //
    //        // SSL disable
    //        // Switch to no encryption, there is keep connect, close SSL
    //        SslContext sslContext = client.findPipeContext(SslContext.class);
    //        sslContext.closeSSL();
    //
    //        clientRcvData.clear();
    //        serverRcvData.clear();
    //        client.sendData("Hello Server, this message using encryption.\n");
    //        server.sendData("Hello Client, this message using encryption.\n");
    //        while (serverRcvData.isEmpty() || clientRcvData.isEmpty()) {
    //            ThreadUtils.sleep(100);
    //        }
    //
    //        assert clientRcvData.get(1).equals("Hello Client, this message using encryption.");
    //        assert serverRcvData.get(1).equals("Hello Server, this message using encryption.");
    //    }
    //
    //    @Test
    //    public void sslModelOnceTest_1() {
    //        SslConfig sslConf = SoSslUtils.sslConfig(SslMode.Once);
    //        EmbeddedSoContext context = new EmbeddedSoContext();
    //        EmbeddedChannel server = new EmbeddedChannel(true, createPipeline(sslConf), context);
    //        EmbeddedChannel client = new EmbeddedChannel(false, createPipeline(sslConf), context);
    //        EmbeddedTransfer transfer = context.joinChannel(client, server);
    //        System.out.println("server:" + server.getChannelID() + ", client:" + client.getChannelID());
    //
    //        // SSL enable
    //        client.send("Hello Server, this message using encryption.\n");
    //        server.send("Hello Client, this message using encryption.\n");
    //        transfer(transfer, 500, 10);
    //        assert client.readRcv().equals("Hello Client, this message using encryption.");
    //        assert server.readRcv().equals("Hello Server, this message using encryption.");
    //
    //        // Switch to no encryption, there is keep connect, close SSL
    //        SslContext clientSSL = client.findPipeContext(SslContext.class);
    //        clientSSL.closeSSL();
    //        transfer(transfer, 500, 10);
    //
    //        // SSL disable
    //        client.send("Hello Server, this message no encryption.\n");
    //        server.send("Hello Client, this message no encryption.\n");
    //        transfer(transfer, 500, 10);
    //        assert client.readRcv().equals("Hello Client, this message no encryption.");
    //        assert server.readRcv().equals("Hello Server, this message no encryption.");
    //    }
    //
    //    @Test
    //    public void sslModelOnceTest_2() {
    //        SslConfig sslConf = SoSslUtils.sslConfig(SslMode.Once);
    //        EmbeddedSoContext context = new EmbeddedSoContext();
    //        EmbeddedChannel server = new EmbeddedChannel(true, createPipeline(sslConf), context);
    //        EmbeddedChannel client = new EmbeddedChannel(false, createPipeline(sslConf), context);
    //        EmbeddedTransfer transfer = context.joinChannel(client, server);
    //        System.out.println("server:" + server.getChannelID() + ", client:" + client.getChannelID());
    //
    //        // SSL enable
    //        client.send("Hello Server, this message using encryption.\n");
    //        server.send("Hello Client, this message using encryption.\n");
    //        transfer(transfer, 500, 10);
    //        assert client.readRcv().equals("Hello Client, this message using encryption.");
    //        assert server.readRcv().equals("Hello Server, this message using encryption.");
    //
    //        // Switch to no encryption, there is keep connect, close SSL
    //        SslContext serverSSL = server.findPipeContext(SslContext.class);
    //        serverSSL.closeSSL();
    //        transfer(transfer, 500, 10);
    //
    //        // SSL disable
    //        client.send("Hello Server, this message no encryption.\n");
    //        server.send("Hello Client, this message no encryption.\n");
    //        transfer(transfer, 500, 10);
    //        assert client.readRcv().equals("Hello Client, this message no encryption.");
    //        assert server.readRcv().equals("Hello Server, this message no encryption.");
    //    }
    //
    //    @Test
    //    public void sslModelManualTest_1() {
    //        SslConfig sslConf = SoSslUtils.sslConfig(SslMode.Manual);
    //        EmbeddedSoContext context = new EmbeddedSoContext();
    //        EmbeddedChannel server = new EmbeddedChannel(true, createPipeline(sslConf), context);
    //        EmbeddedChannel client = new EmbeddedChannel(false, createPipeline(sslConf), context);
    //        EmbeddedTransfer transfer = context.joinChannel(client, server);
    //        System.out.println("server:" + server.getChannelID() + ", client:" + client.getChannelID());
    //
    //        // SSL disable
    //        client.send("Hello Server, this message no encryption.\n");
    //        server.send("Hello Client, this message no encryption.\n");
    //        transfer(transfer, 500, 10);
    //        assert client.readRcv().equals("Hello Client, this message no encryption.");
    //        assert server.readRcv().equals("Hello Server, this message no encryption.");
    //
    //        // Switch to no encryption, there is keep connect, close SSL
    //        SslContext clientSSL = client.findPipeContext(SslContext.class);
    //        SslContext serverSSL = server.findPipeContext(SslContext.class);
    //        clientSSL.openSSL();
    //        serverSSL.openSSL();
    //        transfer(transfer, 500, 10);
    //
    //        // SSL enable
    //        client.send("Hello Server, this message using encryption.\n");
    //        server.send("Hello Client, this message using encryption.\n");
    //        transfer(transfer, 500, 10);
    //        assert client.readRcv().equals("Hello Client, this message using encryption.");
    //        assert server.readRcv().equals("Hello Server, this message using encryption.");
    //    }
}