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
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import net.hasor.tconsole.client.TelClient;
import net.hasor.tconsole.launcher.telnet.SocketTelService;
import net.hasor.test.tconsole.TestExecutor;
import org.junit.Test;

public class GetSetCmdTest {
    @Test
    public void getsetTest_1() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();

        try (SocketTelService server = new SocketTelService(); TelClient client = new TelClient()) {
            server.addCommand("test", new TestExecutor());

            server.start(port);
            client.connectTo(new InetSocketAddress(port));

            String vat_a = client.sendCommand("get a");
            assert vat_a.equals("");
            client.sendCommand("set a=asd");

            vat_a = client.sendCommand("get a");
            assert vat_a.equals("asd");
        }
    }

    @Test
    public void getsetTest_2() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();

        try (SocketTelService server = new SocketTelService(); TelClient client = new TelClient()) {
            server.addCommand("test", new TestExecutor());

            server.start(port);
            client.connectTo(new InetSocketAddress(port));

            String setResult = client.sendCommand("set a");
            assert setResult.contains("java.lang.Exception: args count error.");

            setResult = client.sendCommand("set");
            assert setResult.contains("var name undefined.");

            setResult = client.sendCommand("set a=asd");
            assert setResult.equals("");
            setResult = client.sendCommand("get a");
            assert setResult.equals("asd");
        }
    }
}