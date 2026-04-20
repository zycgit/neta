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
package net.hasor.neta.channel.transport.sctp;

import java.net.InetSocketAddress;

import org.junit.Test;

import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.channel.*;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SctpListenTest extends AbstractSoTest {

    private boolean checkSupport() {
        try {
            com.sun.nio.sctp.SctpServerChannel.open().close();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Test
    public void acceptTest_1() throws Throwable {
        if (!checkSupport()) {
            System.out.println("SCTP not supported on this platform, skip test.");
            return;
        }

        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        SctpSoConfig sctpConf = new SctpSoConfig();

        // server
        NetManager server = new NetManager(globalConf());
        NetListen listen = server.bind(address, ProtoHelper.standard().build(), sctpConf);

        // client
        NetManager client = new NetManager(globalConf());
        NetChannel clientChannel = client.connectSync(address, ProtoHelper.standard().build(), sctpConf);

        // wait connected
        listen.waitAnyAccept();
        while (true) {
            if (listen.getChannelCount() == 1) {
                break;
            } else {
                ThreadUtils.sleep(100);
            }
        }

        // close client
        clientChannel.close();
        while (true) {
            if (listen.getChannelCount() == 0) {
                break;
            } else {
                ThreadUtils.sleep(100);
            }
        }

        // close listen
        listen.closeNow();

        client.shutdown();
        server.shutdown();
    }

    @Test
    public void acceptTest_2() throws Throwable {
        if (!checkSupport()) {
            System.out.println("SCTP not supported on this platform, skip test.");
            return;
        }

        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        SctpSoConfig sctpConf = new SctpSoConfig();

        // server
        NetManager server = new NetManager(globalConf());
        NetListen listen = server.bind(address, ProtoHelper.standard().build(), sctpConf);

        // client 1 & 2
        NetManager client = new NetManager(globalConf());
        NetChannel clientChannel1 = client.connectSync(address, ProtoHelper.standard().build(), sctpConf);
        NetChannel clientChannel2 = client.connectSync(address, ProtoHelper.standard().build(), sctpConf);

        // wait connected
        listen.waitAnyAccept();
        while (true) {
            if (listen.getChannelCount() == 2) {
                break;
            } else {
                ThreadUtils.sleep(100);
            }
        }

        clientChannel1.close();
        while (true) {
            if (listen.getChannelCount() == 1) {
                break;
            } else {
                ThreadUtils.sleep(100);
            }
        }

        clientChannel2.close();
        while (true) {
            if (listen.getChannelCount() == 0) {
                break;
            } else {
                ThreadUtils.sleep(100);
            }
        }

        server.shutdown();
        client.shutdown();
    }
}