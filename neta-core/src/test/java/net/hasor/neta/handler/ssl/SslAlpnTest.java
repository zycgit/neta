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
import net.hasor.neta.handler.EmbeddedChannel;
import net.hasor.neta.handler.EmbeddedSoContext;
import net.hasor.neta.handler.EmbeddedTransfer;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SslAlpnTest extends AbstractSslTest {

    public static SslConfig sslConfig(SslMode mode) {
        SslConfig sslConfig = new SslConfig();
        sslConfig.setAuthType(SslAuthKeyType.PEM);
        sslConfig.setPemCertChain("ssl/ca/server.crt");
        sslConfig.setPemPrivate("ssl/ca/server.pem");
        sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_2 });
        sslConfig.setSsllog(true);
        sslConfig.setSslMode(mode);
        return sslConfig;
    }

    @Test
    public void sslAlpnTest_01() {
        SslConfig sslConf = sslConfig(SslMode.Always);
        sslConf.setAppProtocol(new String[] { "HTTP", "HTTPS" });
        sslConf.setAppProtocolSelector((channel, sslEngine, protocols) -> {
            return "HTTPS";
        });

        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, createPipeline(sslConf), context);
        EmbeddedChannel client = new EmbeddedChannel(false, createPipeline(sslConf), context);
        SslContext serverSSL = server.findPipeContext(SslContext.class);
        SslContext clientSSL = client.findPipeContext(SslContext.class);
        EmbeddedTransfer transfer = context.joinChannel(client, server);
        System.out.println("server:" + server.getChannelID() + ", client:" + client.getChannelID());

        //
        assert serverSSL.getApplicationProtocol() == null;
        assert clientSSL.getApplicationProtocol() == null;

        client.send("Hello Server, this message form client.\n");
        server.send("Hello Client, this message form server.\n");
        transfer(transfer, 500, 10);
        assert client.readRcv().equals("Hello Client, this message form server.");
        assert server.readRcv().equals("Hello Server, this message form client.");

        //
        assert serverSSL.getApplicationProtocol().equals("HTTPS");
        assert clientSSL.getApplicationProtocol().equals("HTTPS");
    }
}