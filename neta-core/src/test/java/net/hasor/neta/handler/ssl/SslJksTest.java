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
public class SslJksTest extends AbstractSslTest {
    @Test
    public void sslHandshakeTest_1() {
        EmbeddedSoContext context = new EmbeddedSoContext();
        EmbeddedChannel server = new EmbeddedChannel(true, createPipeStackUsingJKS(), context);
        EmbeddedChannel client = new EmbeddedChannel(false, createPipeStackUsingJKS(), context);
        EmbeddedTransfer transfer = context.joinChannel(client, server);
        System.out.println("server:" + server.getChannelID() + ", client:" + client.getChannelID());

        client.writeSndUp("Hello Server, this message form client.\n");
        server.writeSndUp("Hello Client, this message form server.\n");
        transfer(transfer, 500, 10);

        String clientRcv = (String) client.readRcvDown();
        String serverRcv = (String) server.readRcvDown();
        assert clientRcv.equals("Hello Client, this message form server.");
        assert serverRcv.equals("Hello Server, this message form client.");
    }
}