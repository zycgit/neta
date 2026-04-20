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
package net.hasor.neta.channel.transport.tcp;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class TcpJvm2NetaTest extends AbstractSoTest {
    @Test
    public void jvm2Neta_1() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);

        // server
        AtomicBoolean tcpRead = new AtomicBoolean(false);
        ProtoInitializer initializer = ProtoHelper.standard().nextDecoder((ProtoHandler<ByteBuf, String>) (context, src, dst) -> {
            while (src.hasMore()) {
                ByteBuf data = src.takeMessage();
                int len = data.readableBytes();
                byte[] bytes = new byte[len];
                data.readBytes(bytes);
                tcpRead.set(StringUtils.equals(new String(bytes), "Hello TCP"));
                data.markReader();
            }
            return ProtoStatus.Next;
        }).build();
        NetManager neta = new NetManager();
        NetListen serverSite = neta.bind(address, initializer, TcpSoConfig.TCP());

        // client
        Socket clientSite = new Socket(address.getHostString(), address.getPort());
        clientSite.getOutputStream().write("Hello TCP".getBytes());
        clientSite.getOutputStream().flush();
        clientSite.close();

        // wait finish
        while (!tcpRead.get()) {
            ThreadUtils.sleep(10);
        }
        neta.shutdown();
    }
}