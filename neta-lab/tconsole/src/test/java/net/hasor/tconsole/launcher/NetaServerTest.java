/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.tconsole.launcher;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import net.hasor.tconsole.client.TelClient;
import net.hasor.tconsole.launcher.telnet.SocketTelService;
import net.hasor.test.tconsole.TestExecutor;
import org.junit.Test;

public class NetaServerTest {
    @Test
    public void server_test_1() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();

        try (SocketTelService server = new SocketTelService(); TelClient client = new TelClient()) {
            // server
            server.addCommand("test", new TestExecutor());
            server.start(port);

            // client
            client.connectTo(new InetSocketAddress(port));
            assert ((InetSocketAddress) client.remoteAddress()).getPort() == port;

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
