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
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.function.Callable;
import net.hasor.neta.channel.AbstractSoTest;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoHelper;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class TcpNeta2JvmTest extends AbstractSoTest {
    @Test
    public void neta2Jvm_1() throws Exception {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);

        // server
        AtomicBoolean serverRead = new AtomicBoolean(false);
        ThreadUtils.daemonThread(true, (Callable) () -> {
            ServerSocket server = new ServerSocket(safePort);
            Socket remote = server.accept();

            byte[] byteArray = new byte[1024];
            int read = remote.getInputStream().read(byteArray);
            serverRead.set(StringUtils.equals(new String(byteArray, 0, read), "Hello TCP"));
            remote.close();
            server.close();
        });

        // client send
        NetManager neta = new NetManager();
        NetChannel clientSite = neta.connectAsync(address, ProtoHelper.standard().build(), TcpSoConfig.TCP()).get();
        clientSite.sendData("Hello TCP".getBytes());

        // wait finish
        while (!serverRead.get()) {
            ThreadUtils.sleep(10);
        }
        neta.shutdown();
    }

    @Test
    public void neta2Jvm_2() throws Exception {
        // no server
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);

        NetManager neta = new NetManager();
        Future<NetChannel> future = neta.connectAsync(address, ProtoHelper.standard().build(), TcpSoConfig.TCP());
        future.await();

        assert future.isDone();
        assert future.getCause().getMessage().equals("Connection refused");
        neta.shutdown();
    }
}