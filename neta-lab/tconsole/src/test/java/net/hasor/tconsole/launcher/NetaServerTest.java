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
package net.hasor.tconsole.launcher;
import net.hasor.tconsole.client.TelClient;
import net.hasor.tconsole.launcher.telnet.SocketTelService;
import net.hasor.test.tconsole.TestExecutor;
import org.junit.Test;

import java.net.InetSocketAddress;

public class NetaServerTest {
    @Test
    public void server_test_1() throws Exception {
        try (SocketTelService server = new SocketTelService(); TelClient client = new TelClient()) {
            // server
            server.addCommand("test", new TestExecutor());
            server.start(8082);

            // client
            client.connectTo(new InetSocketAddress(8082));
            assert ((InetSocketAddress) client.remoteAddress()).getPort() == 8082;

            // test 1
            String help = client.sendCommand("help");
            assert help.contains(" - exit  out of console.");
            assert help.contains(" - set   set/get environment variables of console.");
            assert help.contains(" - test  hello help.");

            // test 2
            String exit = client.sendCommand("exit");
            assert exit.equals("");
            assert !client.isConnected();
        }
    }
}