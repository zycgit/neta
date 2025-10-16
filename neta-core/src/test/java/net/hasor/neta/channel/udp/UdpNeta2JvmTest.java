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
package net.hasor.neta.channel.udp;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import org.junit.Test;
import static net.hasor.neta.channel.AbstractSoTest.safePort;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class UdpNeta2JvmTest {
    @Test
    public void neta2Jvm() throws IOException, ExecutionException, InterruptedException {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);

        // read
        AtomicBoolean udpRead = new AtomicBoolean(false);
        ThreadUtils.daemonThread(true, (Runnable) () -> {
            try {
                DatagramChannel channel = DatagramChannel.open();
                channel.bind(address);

                ByteBuffer buffer = ByteBuffer.allocate(4096);
                SocketAddress receive = channel.receive(buffer);
                buffer.flip();
                byte[] byteArray = ByteBuf.wrap(buffer).asByteArray();

                udpRead.set(StringUtils.equals(new String(byteArray), "Hello UDP"));

                channel.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        //
        UdpSoConfig udpConfig = UdpSoConfig.UDP();
        udpConfig.setRcvPacketSize(4096);
        NetManager neta = new NetManager(new NetConfig());
        ProtoInitializer initializer = ProtoHelper.standard().nextEncoder((ProtoHandler<String, ByteBuf>) (context, src, dst) -> {
            String data = src.takeMessage();
            dst.offerMessage(ByteBuf.wrap(data.getBytes()));
            return ProtoStatus.Next;
        }).build();
        Future<NetChannel> future = neta.connectAsync(address, initializer, udpConfig);
        NetChannel channel = future.get();
        channel.sendData("Hello UDP");

        int i = 100 * 50; // max 5sce
        while (!udpRead.get()) {
            ThreadUtils.sleep(100);
            i--;
            if (i <= 0) {
                break;
            }
        }
        assert udpRead.get();
    }
}