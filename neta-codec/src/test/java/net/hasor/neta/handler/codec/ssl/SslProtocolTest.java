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
package net.hasor.neta.handler.codec.ssl;
import net.hasor.neta.handler.EmbeddedChannel;
import net.hasor.neta.handler.EmbeddedSoContext;
import net.hasor.neta.handler.EmbeddedTransfer;
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

    @Test
    public void ssl_v3() {
        SslConfig sslConf = sslConfig(SslProtocol.SSL_v3);
        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, createProtoStack(sslConf), context);
        EmbeddedChannel client = new EmbeddedChannel(false, createProtoStack(sslConf), context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);
        System.out.println("server:" + server.getChannelID() + ", client:" + client.getChannelID());

        client.send("Hello Server, this message form client.\n");
        server.send("Hello Client, this message form server.\n");
        transfer(transfer, 600, 10);

        assert client.readRcv().equals("Hello Client, this message form server.");
        assert server.readRcv().equals("Hello Server, this message form client.");
    }

    @Test
    public void tls_v1() {
        SslConfig sslConf = sslConfig(SslProtocol.TLS_v1);
        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, createProtoStack(sslConf), context);
        EmbeddedChannel client = new EmbeddedChannel(false, createProtoStack(sslConf), context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);
        System.out.println("server:" + server.getChannelID() + ", client:" + client.getChannelID());

        client.send("Hello Server, this message form client.\n");
        server.send("Hello Client, this message form server.\n");
        transfer(transfer, 600, 10);

        assert client.readRcv().equals("Hello Client, this message form server.");
        assert server.readRcv().equals("Hello Server, this message form client.");
    }

    @Test
    public void tls_v1_1() {
        SslConfig sslConf = sslConfig(SslProtocol.TLS_v1_1);
        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, createProtoStack(sslConf), context);
        EmbeddedChannel client = new EmbeddedChannel(false, createProtoStack(sslConf), context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);
        System.out.println("server:" + server.getChannelID() + ", client:" + client.getChannelID());

        client.send("Hello Server, this message form client.\n");
        server.send("Hello Client, this message form server.\n");
        transfer(transfer, 600, 10);

        assert client.readRcv().equals("Hello Client, this message form server.");
        assert server.readRcv().equals("Hello Server, this message form client.");
    }

    @Test
    public void tls_v1_2() {
        SslConfig sslConf = sslConfig(SslProtocol.TLS_v1_2);
        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, createProtoStack(sslConf), context);
        EmbeddedChannel client = new EmbeddedChannel(false, createProtoStack(sslConf), context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);
        System.out.println("server:" + server.getChannelID() + ", client:" + client.getChannelID());

        client.send("Hello Server, this message form client.\n");
        server.send("Hello Client, this message form server.\n");
        transfer(transfer, 600, 10);

        assert client.readRcv().equals("Hello Client, this message form server.");
        assert server.readRcv().equals("Hello Server, this message form client.");
    }

    @Test
    public void tls_v1_3() {
        SslConfig sslConf = sslConfig(SslProtocol.TLS_v1_3);
        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, createProtoStack(sslConf), context);
        EmbeddedChannel client = new EmbeddedChannel(false, createProtoStack(sslConf), context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);
        System.out.println("server:" + server.getChannelID() + ", client:" + client.getChannelID());

        client.send("Hello Server, this message form client.\n");
        server.send("Hello Client, this message form server.\n");
        transfer(transfer, 600, 10);

        assert client.readRcv().equals("Hello Client, this message form server.");
        assert server.readRcv().equals("Hello Server, this message form client.");
    }

    @Test
    public void dtls_v1_0() {
        System.setProperty("https.protocols", "TLSv1");

        SslConfig sslConf = sslConfig(SslProtocol.DTLS_v1_0);
        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, createProtoStack(sslConf), context);
        EmbeddedChannel client = new EmbeddedChannel(false, createProtoStack(sslConf), context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);
        System.out.println("server:" + server.getChannelID() + ", client:" + client.getChannelID());

        // round 1
        client.send("Hello Server, this message 1 form client.\n");
        transfer(transfer, 600, 10);
        assert server.readRcv().equals("Hello Server, this message 1 form client.");

        server.send("Hello Client, this message 1 form server.\n");
        transfer(transfer, 600, 10);
        assert client.readRcv().equals("Hello Client, this message 1 form server.");

        // round 2
        client.send("Hello Server, this message 2 form client.\n");
        transfer(transfer, 600, 10);
        assert server.readRcv().equals("Hello Server, this message 2 form client.");

        server.send("Hello Client, this message 2 form server.\n");
        transfer(transfer, 600, 10);
        assert client.readRcv().equals("Hello Client, this message 2 form server.");
    }

    @Test
    public void dtls_v1_2() {
        SslConfig sslConf = sslConfig(SslProtocol.DTLS_v1_2);
        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, createProtoStack(sslConf), context);
        EmbeddedChannel client = new EmbeddedChannel(false, createProtoStack(sslConf), context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);
        System.out.println("server:" + server.getChannelID() + ", client:" + client.getChannelID());

        // round 1
        System.out.println("client say hello");
        client.send("Hello Server, this message 1 form client.\n");
        transfer(transfer, 600, 10);
        assert server.readRcv().equals("Hello Server, this message 1 form client.");

        System.out.println("server say hello");
        server.send("Hello Client, this message 1 form server.\n");
        transfer(transfer, 600, 10);
        assert client.readRcv().equals("Hello Client, this message 1 form server.");

        // round 2
        client.send("Hello Server, this message 2 form client.\n");
        transfer(transfer, 600, 10);
        assert server.readRcv().equals("Hello Server, this message 2 form client.");

        server.send("Hello Client, this message 2 form server.\n");
        transfer(transfer, 600, 10);
        assert client.readRcv().equals("Hello Client, this message 2 form server.");
    }
}