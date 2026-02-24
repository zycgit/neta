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
package net.hasor.neta.codec.ssl;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.hasor.neta.channel.PlayLoad;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtListen;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SslAuthTypeTest extends AbstractSslTest {

    @Test
    public void byPemCert() throws Throwable {
        this.autoCloseNeta(neta -> {
            SslConfig sslConf = new SslConfig();
            sslConf.setAuthType(SslAuthKeyType.PEM);
            sslConf.setPemCertChain("ssl/ca/server.crt");
            sslConf.setPemPrivate("ssl/ca/server.pem");
            sslConf.setProtocols(new String[] { SslProtocol.TLS_v1_2 });

            CountDownLatch handshakeDone = new CountDownLatch(2); // server + client
            // Use synchronous VRT so the SSL handshake completes inline without competing for
            // worker threads — this makes the test deterministic in the full suite environment.
            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);
            VrtSocketAddress vrtListen = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtListen, createProtoStackWithHandshakeLatch(sslConf, handshakeDone), config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtListen, createProtoStackWithHandshakeLatch(sslConf, handshakeDone), config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();

            // Wait for SSL handshake on both sides — no sleep needed
            assert handshakeDone.await(10, TimeUnit.SECONDS) : "SSL handshake did not complete in time (byPemCert)";

            // transfer
            CountDownLatch dataReceived = new CountDownLatch(2);
            Queue<Object> serverRcvData = new ArrayDeque<>();
            Queue<Object> clientRcvData = new ArrayDeque<>();
            server.subscribe(PlayLoad::isInbound, d -> {
                serverRcvData.offer(d.getData());
                dataReceived.countDown();
            });
            client.subscribe(PlayLoad::isInbound, d -> {
                clientRcvData.offer(d.getData());
                dataReceived.countDown();
            });
            System.out.println("server:" + server.getChannelId() + ", client:" + client.getChannelId());

            //
            client.sendData("Hello Server, this message form client.\n");
            server.sendData("Hello Client, this message form server.\n");
            assert dataReceived.await(5, TimeUnit.SECONDS) : "Data was not received in time";
            assert clientRcvData.poll().equals("Hello Client, this message form server.");
            assert serverRcvData.poll().equals("Hello Server, this message form client.");
        });
    }

    @Test
    public void byJks() throws Throwable {
        this.autoCloseNeta(neta -> {
            SslConfig sslConf = new SslConfig();
            sslConf.setAuthType(SslAuthKeyType.JKS);
            sslConf.setJksResource("ssl/jks/keystore.jks");
            sslConf.setKeyPassword("123456");
            sslConf.setProtocols(new String[] { SslProtocol.TLS_v1_2 });

            CountDownLatch handshakeDone = new CountDownLatch(2); // server + client
            // Use synchronous VRT so the SSL handshake completes inline without competing for
            // worker threads — this makes the test deterministic in the full suite environment.
            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);
            VrtSocketAddress vrtListen = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtListen, createProtoStackWithHandshakeLatch(sslConf, handshakeDone), config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtListen, createProtoStackWithHandshakeLatch(sslConf, handshakeDone), config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();

            // Wait for SSL handshake on both sides — no sleep needed
            assert handshakeDone.await(10, TimeUnit.SECONDS) : "SSL handshake did not complete in time (byJks)";

            // transfer
            CountDownLatch dataReceived = new CountDownLatch(2);
            Queue<Object> serverRcvData = new ArrayDeque<>();
            Queue<Object> clientRcvData = new ArrayDeque<>();
            server.subscribe(PlayLoad::isInbound, d -> {
                serverRcvData.offer(d.getData());
                dataReceived.countDown();
            });
            client.subscribe(PlayLoad::isInbound, d -> {
                clientRcvData.offer(d.getData());
                dataReceived.countDown();
            });
            System.out.println("server:" + server.getChannelId() + ", client:" + client.getChannelId());

            //
            client.sendData("Hello Server, this message form client.\n");
            server.sendData("Hello Client, this message form server.\n");
            assert dataReceived.await(5, TimeUnit.SECONDS) : "Data was not received in time";
            assert clientRcvData.poll().equals("Hello Client, this message form server.");
            assert serverRcvData.poll().equals("Hello Server, this message form client.");
        });
    }
}