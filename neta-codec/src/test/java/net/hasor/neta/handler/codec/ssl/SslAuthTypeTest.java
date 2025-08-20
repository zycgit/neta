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
public class SslAuthTypeTest extends AbstractSslTest {

    @Test
    public void byPemCert() {
        SslConfig sslConf = new SslConfig();
        sslConf.setAuthType(SslAuthKeyType.PEM);
        sslConf.setPemCertChain("ssl/ca/server.crt");
        sslConf.setPemPrivate("ssl/ca/server.pem");
        sslConf.setProtocols(new String[] { SslProtocol.TLS_v1_2 });

        //
        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, createProtoStack(sslConf), context);
        EmbeddedChannel client = new EmbeddedChannel(false, createProtoStack(sslConf), context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);
        System.out.println("server:" + server.getChannelId() + ", client:" + client.getChannelId());

        client.send("Hello Server, this message form client.\n");
        server.send("Hello Client, this message form server.\n");
        transfer(transfer, 500, 10);

        assert client.readRcv().equals("Hello Client, this message form server.");
        assert server.readRcv().equals("Hello Server, this message form client.");
    }

    @Test
    public void byJks() {
        SslConfig sslConf = new SslConfig();
        sslConf.setAuthType(SslAuthKeyType.JKS);
        sslConf.setJksResource("ssl/jks/keystore.jks");
        sslConf.setKeyPassword("123456");
        sslConf.setProtocols(new String[] { SslProtocol.TLS_v1_2 });

        //
        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, createProtoStack(sslConf), context);
        EmbeddedChannel client = new EmbeddedChannel(false, createProtoStack(sslConf), context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);
        System.out.println("server:" + server.getChannelId() + ", client:" + client.getChannelId());

        client.send("Hello Server, this message form client.\n");
        server.send("Hello Client, this message form server.\n");
        transfer(transfer, 500, 10);

        assert client.readRcv().equals("Hello Client, this message form server.");
        assert server.readRcv().equals("Hello Server, this message form client.");
    }
}