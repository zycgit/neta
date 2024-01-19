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
import net.hasor.cobble.ResourcesUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.function.Callable;
import net.hasor.neta.channel.CobbleSocket;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.PipelineFactory;
import net.hasor.neta.channel.SoConfig;
import net.hasor.neta.handler.PipeInitializer;
import org.junit.Test;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.hasor.neta.channel.AbstractSoTest.crateConfig;
import static net.hasor.neta.channel.AbstractSoTest.safePort;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SslSocketTest extends AbstractSslTest {
    public static SSLContext sslContext() throws Exception {
        char[] password = "123456".toCharArray();
        KeyStore jsk = KeyStore.getInstance("JKS");
        SslUtils.loadKeyStore(jsk, ResourcesUtils.getResourceAsStream("ssl/jks/keystore.jks"), password);
        //KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        //kmf.init(jsk,password);
        KeyManagerFactory kmf = SslUtils.buildKeyManagerFactory(jsk, password, null);

        // SSL Server
        SSLContext sslContext = SSLContext.getInstance("SSLv3");
        sslContext.init(kmf.getKeyManagers(), null, null);

        return sslContext;
    }

    public static SslConfig sslConfig(SslMode mode) {
        SslConfig sslConfig = new SslConfig();
        sslConfig.setAuthType(SslAuthKeyType.JKS);
        sslConfig.setJksResource("ssl/jks/keystore.jks");
        sslConfig.setKeyPassword("123456");
        sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_2 });
        sslConfig.setSsllog(true);
        sslConfig.setSslMode(mode);
        return sslConfig;
    }

    public static PipelineFactory createPipeline(SslConfig sslConf) {
        //  Net      SSL     Message
        // Bytes -> Bytes -> String
        // Bytes <- Bytes <- String
        return new PipeInitializer()
                // SSL
                .nextTo("SSL", new SslPipeLayer(sslConf))
                // bytes <-> String
                .nextTo("String", AbstractSslTest::doDecoder1, AbstractSslTest::doEncoder1)
                // create Stack
                .build();
    }

    @Test
    public void realSocketTest_01() throws Exception {
        int safePort = safePort();
        SSLServerSocketFactory sslFactory = sslContext().getServerSocketFactory();
        SSLServerSocket serverSocket = (SSLServerSocket) sslFactory.createServerSocket(safePort);// 创建并进入监听

        // wait message
        AtomicBoolean readFinish = new AtomicBoolean();
        List<String> rcvMessage = new ArrayList<>();
        ThreadUtils.daemonThread(true, (Callable) () -> {
            try {
                SSLSocket socket = (SSLSocket) serverSocket.accept();
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                rcvMessage.add(input.readLine());
                readFinish.set(true);
            } catch (Exception e) {
                readFinish.set(true);
            }
        });

        // client
        SoConfig soConf = crateConfig(128, 4096);
        soConf.setNetlog(true);
        SslConfig sslConf = sslConfig(SslMode.Always);
        CobbleSocket neta = new CobbleSocket(soConf);
        Future<NetChannel> connect = neta.connect("127.0.0.1", safePort, createPipeline(sslConf));
        while (!connect.isDone()) {
            ThreadUtils.sleep(100);
        }

        NetChannel client = connect.get();
        Future<?> send = client.sendData("Hello Server, this message form client.\n");
        send.get();

        while (!readFinish.get()) {
            ThreadUtils.sleep(100);
        }

        assert rcvMessage.get(0).equals("Hello Server, this message form client.");

        serverSocket.close();
        neta.shutdown();
    }

    //    @Test
    //    public void realSocketTest_02() throws IOException {
    //        int safePort = safePort();
    //        SoConfig soConf = crateConfig(128, 4096);
    //        soConf.setNetlog(true);
    //        SslConfig sslConf = sslConfig(SslMode.Always);
    //        CobbleSocket neta = new CobbleSocket(soConf);
    //
    //        // Server
    //        List<String> serverRcvData = new ArrayList<>();
    //        neta.listen("127.0.0.1", safePort, createPipeline(sslConf, (channel, data) -> {
    //            serverRcvData.add(data);
    //        }));
    //
    //        // Client
    //        List<String> clientRcvData = new ArrayList<>();
    //        Future<NetChannel> connect = neta.connect("127.0.0.1", safePort, createPipeline(sslConf, (channel, data) -> {
    //            clientRcvData.add(data);
    //        }));
    //
    //        //
    //        while (!connect.isDone()) {
    //            ThreadUtils.sleep(100);
    //        }
    //
    //        NetChannel client = (NetChannel) neta.getContext().findChannel(2);
    //        NetChannel server = (NetChannel) neta.getContext().findChannel(3);
    //
    //        Future<?> send1 = client.sendData("Hello Server, this message form client.\n");
    //        Future<?> send2 = server.sendData("Hello Client, this message form server.\n");
    //
    //        while (serverRcvData.isEmpty() || clientRcvData.isEmpty()) {
    //            ThreadUtils.sleep(100);
    //        }
    //
    //        assert serverRcvData.get(0).equals("Hello Server, this message form client.");
    //        assert clientRcvData.get(0).equals("Hello Client, this message form server.");
    //
    //        neta.shutdown();
    //    }

}