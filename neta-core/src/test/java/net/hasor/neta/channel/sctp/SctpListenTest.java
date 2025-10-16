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
package net.hasor.neta.channel.sctp;
import java.net.InetSocketAddress;
import net.hasor.neta.channel.AbstractSoTest;
import net.hasor.neta.channel.NetListen;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoHelper;
import net.hasor.neta.channel.tcp.TcpSoConfig;
import org.junit.Test;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SctpListenTest extends AbstractSoTest {

    @Test
    public void acceptTest_1() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        TcpSoConfig tcpConf = tcpConfig(2, 30);

        // server
        NetManager server = new NetManager(globalConf());
        NetListen listen = server.bind(address, ProtoHelper.standard().build(), tcpConf);

        server.shutdown();
    }
}