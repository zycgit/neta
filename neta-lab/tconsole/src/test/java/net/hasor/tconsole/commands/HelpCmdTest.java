/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.tconsole.commands;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import net.hasor.tconsole.client.TelClient;
import net.hasor.tconsole.launcher.telnet.SocketTelService;
import net.hasor.test.tconsole.TestExecutor;
import org.junit.Test;

public class HelpCmdTest {
    @Test
    public void helpTest_1() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();

        try (SocketTelService server = new SocketTelService(); TelClient client = new TelClient()) {
            server.addCommand("test", new TestExecutor());

            server.start(port);
            client.connectTo(new InetSocketAddress(port));

            String help = client.sendCommand("help");
            assert help.contains("- exit  out of console.");
            assert help.contains("- set   set/get environment variables of console.");
            assert help.contains("- test  hello help.");

            client.close();
            assert !client.isConnected();
        }
    }

    @Test
    public void helpTest_2() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();

        try (SocketTelService server = new SocketTelService(); TelClient client = new TelClient()) {
            server.addCommand("test", new TestExecutor());

            server.start(port);
            client.connectTo(new InetSocketAddress(port));

            String help1 = client.sendCommand("help test");
            assert help1.contains("hello help.");

            String help2 = client.sendCommand("help help");
            assert help2.contains("help quit  (show the 'quit' command help info.)");

            String help3 = client.sendCommand("help abc");
            assert help3.contains("[ERROR] command 'abc' does not exist.");
        }
    }
}
