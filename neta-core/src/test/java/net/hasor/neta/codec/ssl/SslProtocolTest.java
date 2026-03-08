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
import net.hasor.neta.channel.SubscribeMode;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtListen;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import org.junit.Assume;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SslProtocolTest extends AbstractSslTest {

    public static SslConfig sslConfig(String protocol) {
        SslConfig sslConfig = new SslConfig();
        sslConfig.setAuthType(SslAuthKeyType.PEM);
        sslConfig.setPemCertChain("ssl/ca/server.crt");
        sslConfig.setPemPrivate("ssl/ca/server.pem");
        sslConfig.setProtocols(new String[] { protocol });
        return sslConfig;
    }

    private void runProtocolTest(String protocol) throws Throwable {
        this.autoCloseNeta(neta -> {
            SslConfig sslConf = sslConfig(protocol);

            CountDownLatch handshakeDone = new CountDownLatch(2);
            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtListen = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtListen, createProtoStackWithHandshakeLatch(sslConf, handshakeDone), config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtListen, createProtoStackWithHandshakeLatch(sslConf, handshakeDone), config);
            listen.waitAnyAccept();
            VrtChannel server = (VrtChannel) neta.findChannel(3);

            assert server != null : "Server channel was not accepted";
            boolean handshakeCompleted = handshakeDone.await(10, TimeUnit.SECONDS);
            if (isLegacyProtocol(protocol)) {
                Assume.assumeTrue("Protocol is disabled by current JDK: " + protocol, handshakeCompleted);
            }
            assert handshakeCompleted : "SSL handshake did not complete in time";

            CountDownLatch dataReceived = new CountDownLatch(2);
            Queue<Object> serverRcvData = new ArrayDeque<>();
            Queue<Object> clientRcvData = new ArrayDeque<>();
            server.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> {
                serverRcvData.offer(d.getData());
                dataReceived.countDown();
            });
            client.subscribe(PlayLoad::isInbound, SubscribeMode.SYNC, d -> {
                clientRcvData.offer(d.getData());
                dataReceived.countDown();
            });

            client.sendData("Hello Server, this message form client.\n");
            server.sendData("Hello Client, this message form server.\n");

            assert dataReceived.await(5, TimeUnit.SECONDS) : "Data was not received in time";
            assert clientRcvData.poll().equals("Hello Client, this message form server.");
            assert serverRcvData.poll().equals("Hello Server, this message form client.");
        });
    }

    private boolean isLegacyProtocol(String protocol) {
        return SslProtocol.SSL_v3.equals(protocol) || SslProtocol.TLS_v1.equals(protocol) || SslProtocol.TLS_v1_1.equals(protocol);
    }

    @Test
    public void ssl_v3() throws Throwable {
        runProtocolTest(SslProtocol.SSL_v3);
    }

    @Test
    public void tls_v1() throws Throwable {
        runProtocolTest(SslProtocol.TLS_v1);
    }

    @Test
    public void tls_v1_1() throws Throwable {
        runProtocolTest(SslProtocol.TLS_v1_1);
    }

    @Test
    public void tls_v1_2() throws Throwable {
        runProtocolTest(SslProtocol.TLS_v1_2);
    }

    @Test
    public void tls_v1_3() throws Throwable {
        runProtocolTest(SslProtocol.TLS_v1_3);
    }

    //    @Test
    //    public void dtls_v1_0() {
    //        System.setProperty("https.protocols", "TLSv1");
    //
    //        SslConfig sslConf = sslConfig(SslProtocol.DTLS_v1_0);
    //        EmbeddedSoContext context = new EmbeddedSoContext();
    //        EmbeddedChannel server = new EmbeddedChannel(true, createProtoStackOld(sslConf), context);
    //        EmbeddedChannel client = new EmbeddedChannel(false, createProtoStackOld(sslConf), context);
    //        EmbeddedTransfer transfer = context.joinChannel(client, server);
    //        System.out.println("server:" + server.getChannelId() + ", client:" + client.getChannelId());
    //
    //        // round 1
    //        client.send("Hello Server, this message 1 form client.\n");
    //        transfer(transfer, 600, 1);
    //        assert server.readRcv().equals("Hello Server, this message 1 form client.");
    //
    //        server.send("Hello Client, this message 1 form server.\n");
    //        transfer(transfer, 600, 1);
    //        assert client.readRcv().equals("Hello Client, this message 1 form server.");
    //
    //        // round 2
    //        client.send("Hello Server, this message 2 form client.\n");
    //        transfer(transfer, 600, 1);
    //        assert server.readRcv().equals("Hello Server, this message 2 form client.");
    //
    //        server.send("Hello Client, this message 2 form server.\n");
    //        transfer(transfer, 600, 1);
    //        assert client.readRcv().equals("Hello Client, this message 2 form server.");
    //    }
    //
    //    @Test
    //    public void dtls_v1_2() {
    //        SslConfig sslConf = sslConfig(SslProtocol.DTLS_v1_2);
    //        EmbeddedSoContext context = new EmbeddedSoContext();
    //        EmbeddedChannel server = new EmbeddedChannel(true, createProtoStackOld(sslConf), context);
    //        EmbeddedChannel client = new EmbeddedChannel(false, createProtoStackOld(sslConf), context);
    //        EmbeddedTransfer transfer = context.joinChannel(client, server);
    //        System.out.println("server:" + server.getChannelId() + ", client:" + client.getChannelId());
    //
    //        // round 1
    //        System.out.println("client say hello");
    //        client.send("Hello Server, this message 1 form client.\n");
    //        transfer(transfer, 600, 1);
    //        assert server.readRcv().equals("Hello Server, this message 1 form client.");
    //
    //        System.out.println("server say hello");
    //        server.send("Hello Client, this message 1 form server.\n");
    //        transfer(transfer, 600, 1);
    //        assert client.readRcv().equals("Hello Client, this message 1 form server.");
    //
    //        // round 2
    //        client.send("Hello Server, this message 2 form client.\n");
    //        transfer(transfer, 600, 1);
    //        assert server.readRcv().equals("Hello Server, this message 2 form client.");
    //
    //        server.send("Hello Client, this message 2 form server.\n");
    //        transfer(transfer, 600, 1);
    //        assert client.readRcv().equals("Hello Client, this message 2 form server.");
    //    }
}