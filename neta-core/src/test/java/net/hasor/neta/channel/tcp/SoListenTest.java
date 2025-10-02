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
package net.hasor.neta.channel.tcp;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.channel.AbstractSoTest;
import net.hasor.neta.channel.NetListen;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoHelper;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SoListenTest extends AbstractSoTest {
    @Test
    public void acceptTest_1() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        TcpSoConfig tcpConf = tcpConfig(2, 30);

        // server
        NetManager server = new NetManager(globalConf());
        NetListen listen = server.bind(address, context -> ProtoHelper.standard().build(), tcpConf);

        // client 1 and 2
        Socket client1 = new Socket("127.0.0.1", safePort);
        Socket client2 = new Socket("127.0.0.1", safePort);

        // wait connected
        listen.waitAnyAccept();
        while (true) {
            if (listen.getChannelCount() == 2) {
                break;
            } else {
                ThreadUtils.sleep(100);
            }
        }

        // close listen
        listen.closeNow();
        assert listen.getChannelCount() == 2;
        try {
            Socket badClient = new Socket("127.0.0.1", safePort);
            assert false;
        } catch (Exception e) {
            assert e.getMessage().startsWith("Connection refused");
        }

        // client close
        client1.close();
        client2.close();
        while (true) {
            if (listen.getChannelCount() == 0) {
                break;
            } else {
                ThreadUtils.sleep(100);
            }
        }

        server.shutdown();
    }

    @Test
    public void suspendTest_1() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        TcpSoConfig tcpConf = TcpSoConfig.TCP();

        // server
        NetManager server = new NetManager(globalConf());
        NetListen listen = server.bind(address, context -> ProtoHelper.standard().build(), tcpConf);

        // client 1 and 2
        Socket client1 = new Socket("127.0.0.1", safePort);
        Socket client2 = new Socket("127.0.0.1", safePort);

        // wait connected
        listen.waitAnyAccept();
        while (true) {
            if (listen.getChannelCount() == 2) {
                break;
            } else {
                ThreadUtils.sleep(100);
            }
        }

        // close listen
        listen.suspend();
        assert listen.getChannelCount() == 2;
        Socket badClient = new Socket("127.0.0.1", safePort);
        int read = badClient.getInputStream().read();
        assert read == -1;

        // client close
        client1.close();
        client2.close();
        while (true) {
            if (listen.getChannelCount() == 0) {
                break;
            } else {
                ThreadUtils.sleep(100);
            }
        }

        server.shutdown();
    }

    @Test
    public void acceptListener_1() throws Throwable {
        NetManager server = new NetManager();
        NetListen listen1 = server.bind(new InetSocketAddress("127.0.0.1", safePort()), context -> ProtoHelper.standard().build(), TcpSoConfig.TCP());
        NetListen listen2 = server.bind(new InetSocketAddress("127.0.0.1", safePort()), context -> ProtoHelper.standard().build(), TcpSoConfig.TCP());
        int safePort1 = listen1.getListenPort();
        int safePort2 = listen2.getListenPort();

        assert listen1.getChannelCount() == 0;
        assert listen2.getChannelCount() == 0;
        Socket client1 = new Socket("127.0.0.1", safePort1);
        listen1.waitAnyAccept();
        assert listen1.getChannelCount() == 1;
        assert listen2.getChannelCount() == 0;

        assert listen1.getChannelCount() == 1;
        assert listen2.getChannelCount() == 0;
        Socket client2 = new Socket("127.0.0.1", safePort2);
        listen2.waitAnyAccept();
        assert listen1.getChannelCount() == 1;
        assert listen2.getChannelCount() == 1;

        client1.close();
        listen1.waitIdle();
        assert listen1.getChannelCount() == 0;
        assert listen2.getChannelCount() == 1;

        client2.close();
        listen2.waitIdle();
        assert listen1.getChannelCount() == 0;
        assert listen2.getChannelCount() == 0;
        server.shutdown();
    }

    @Test
    public void foundTest_1() throws Throwable {
        // start server
        int safePort = safePort();
        TcpSoConfig tcpConf = tcpConfig(2, 30);
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        NetManager server = new NetManager(globalConf());
        NetListen listen = server.bind(address, context -> ProtoHelper.standard().build(), tcpConf);

        assert listen == server.findListen(safePort);

        server.shutdown();
    }
}