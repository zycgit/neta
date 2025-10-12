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
package net.hasor.tconsole.commands;
import net.hasor.tconsole.client.TelClient;
import net.hasor.tconsole.launcher.telnet.SocketTelService;
import net.hasor.test.tconsole.TestExecutor;
import org.junit.Test;

import java.net.InetSocketAddress;

public class QuitCmdTest {
    @Test
    public void autoexit_test_1() throws Exception {
        try (SocketTelService server = new SocketTelService(); TelClient client = new TelClient()) {
            server.addCommand("test", new TestExecutor());

            server.start(8082);
            client.connectTo(new InetSocketAddress(8082));

            client.sendCommand("set a=asd");
            client.sendCommand("exit -next");
            String vat_a = client.sendCommand("get a");
            assert vat_a.trim().equals("asd");
            assert !client.isConnected(); // exit -next 生效
        }
    }

    @Test
    public void exit_n_test_1() throws Exception {
        try (SocketTelService server = new SocketTelService(); TelClient client = new TelClient()) {
            server.addCommand("test", new TestExecutor());

            server.start(8082);
            client.connectTo(new InetSocketAddress(8082));

            client.sendCommand("exit -n3");

            String res1 = client.sendCommand("set a=asd");
            String res2 = client.sendCommand("set a=123");
            String res3 = client.sendCommand("get a");
            assert !client.isConnected();
            assert res3.trim().equals("123");
        }
    }
}