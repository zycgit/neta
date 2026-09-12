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

public class QuitCmdTest {
    @Test
    public void autoexit_test_1() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();

        try (SocketTelService server = new SocketTelService(); TelClient client = new TelClient()) {
            server.addCommand("test", new TestExecutor());

            server.start(port);
            client.connectTo(new InetSocketAddress(port));

            client.sendCommand("set a=asd");
            client.sendCommand("exit -next");
            String vat_a = client.sendCommand("get a");
            assert vat_a.trim().equals("asd");
            assert !client.isConnected(); // exit -next 生效
        }
    }

    @Test
    public void exit_n_test_1() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();

        try (SocketTelService server = new SocketTelService(); TelClient client = new TelClient()) {
            server.addCommand("test", new TestExecutor());

            server.start(port);
            client.connectTo(new InetSocketAddress(port));

            client.sendCommand("exit -n3");

            String res1 = client.sendCommand("set a=asd");
            String res2 = client.sendCommand("set a=123");
            String res3 = client.sendCommand("get a");
            assert !client.isConnected();
            assert res3.trim().equals("123");
        }
    }
}
