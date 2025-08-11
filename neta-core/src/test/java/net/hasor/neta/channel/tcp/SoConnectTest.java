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
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.function.Callable;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.*;
import net.hasor.neta.handler.*;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SoConnectTest extends AbstractSoTest {
    @Test
    public void connectToTest_01() throws Exception {
        // start server
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        ServerSocket server = new ServerSocket(safePort);
        ThreadUtils.daemonThread(true, (Callable) () -> {
            Socket remote = server.accept();
            OutputStream out = remote.getOutputStream();
            out.write("Hello\n".getBytes());
            out.flush();
            remote.close();
        });

        //
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        NetManager neta = new NetManager(crateConfig(2, 30));
        Future<NetChannel> future = neta.connect(address, new ProtoInitializer() {
            @Override
            public ProtoStack<ByteBuf> config(ProtoContext ctx) {
                return ProtoHelper.builder().nextDecoder(new ProtoHandler<ByteBuf, ByteBuf>() {
                    @Override
                    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> src, ProtoSndQueue<ByteBuf> dst) throws Throwable {
                        while (src.hasMore()) {
                            ByteBuf data = src.takeMessage();
                            buf.writeBuffer(data);
                            data.markReader();
                            buf.markWriter();
                        }
                        return ProtoStatus.Next;
                    }
                }).build();
            }
        }, NetOptions.TCP());

        NetChannel remote = future.get();
        while (!remote.isClose()) {
            Thread.sleep(10);
        }

        assert "Hello".equals(buf.readLine());
        neta.shutdown();
        server.close();
    }

    @Test
    public void connectToTest_02() throws Exception {
        // start server
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);

        NetManager neta = new NetManager(crateConfig(2, 30));
        Future<NetChannel> future = neta.connect(address, new ProtoInitializer() {
            @Override
            public ProtoStack<ByteBuf> config(ProtoContext ctx) {
                return ProtoHelper.builder().build();
            }
        }, NetOptions.TCP());
        neta.shutdown();

        ServerSocket server = new ServerSocket(safePort);
        ThreadUtils.daemonThread(true, (Callable) () -> {
            Socket remote = server.accept();
            OutputStream out = remote.getOutputStream();
            out.write("Hello\n".getBytes());
            out.flush();
            remote.close();
        });

        assert future.isDone();
        assert future.getCause().getMessage().equals("Connection refused");

        server.close();
    }
}