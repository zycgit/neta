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
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.channel.PlayLoad;
import net.hasor.neta.channel.virtual.VrtChannel;
import net.hasor.neta.channel.virtual.VrtListen;
import net.hasor.neta.channel.virtual.VrtSoConfig;
import net.hasor.neta.channel.virtual.VrtSocketAddress;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SslAlpnTest extends AbstractSslTest {

    public static SslConfig sslConfig() {
        SslConfig sslConfig = new SslConfig();
        sslConfig.setAuthType(SslAuthKeyType.PEM);
        sslConfig.setPemCertChain("ssl/ca/server.crt");
        sslConfig.setPemPrivate("ssl/ca/server.pem");
        sslConfig.setProtocols(new String[] { SslProtocol.TLS_v1_2 });
        return sslConfig;
    }

    @Test
    public void alpnTest_01() throws Throwable {
        this.autoCloseNeta(neta -> {
            SslConfig sslConf = sslConfig();
            sslConf.setAppProtocol(new String[] { "HTTP", "HTTPS" });
            sslConf.setAppProtocolSelector((channel, sslEngine, protocols) -> {
                return "HTTPS";
            });

            VrtSoConfig config = VrtSoConfig.asDefault();
            config.setAsynchronous(false);

            VrtSocketAddress vrtListen = new VrtSocketAddress(0, true);
            VrtListen listen = (VrtListen) neta.bind(vrtListen, createProtoStack(sslConf), config);
            VrtChannel client = (VrtChannel) neta.connectSync(vrtListen, createProtoStack(sslConf), config);
            VrtChannel server = (VrtChannel) neta.findChannel(3);
            listen.waitAnyAccept();

            // transfer
            Queue<Object> serverRcvData = new ArrayDeque<>();
            Queue<Object> clientRcvData = new ArrayDeque<>();
            server.subscribe(PlayLoad::isInbound, d -> serverRcvData.offer(d.getData()));
            client.subscribe(PlayLoad::isInbound, d -> clientRcvData.offer(d.getData()));
            System.out.println("server:" + server.getChannelId() + ", client:" + client.getChannelId());

            //
            SslContext serverSSL = server.findProtoContext(SslContext.class);
            SslContext clientSSL = client.findProtoContext(SslContext.class);
            ThreadUtils.sleep(1000);
            assert serverSSL.getApplicationProtocol().equals("HTTPS");
            assert clientSSL.getApplicationProtocol().equals("HTTPS");

            //
            client.sendData("Hello Server, this message form client.\n");
            server.sendData("Hello Client, this message form server.\n");
            ThreadUtils.sleep(1000);
            assert clientRcvData.poll().equals("Hello Client, this message form server.");
            assert serverRcvData.poll().equals("Hello Server, this message form client.");
        });
    }
}