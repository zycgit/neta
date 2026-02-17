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
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import com.sun.nio.sctp.SctpChannel;
import com.sun.nio.sctp.SctpServerChannel;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import org.junit.Test;
import static net.hasor.neta.channel.AbstractSoTest.safePort;

public class SctpNeta2JvmTest {

    private boolean checkSupport() {
        try {
            com.sun.nio.sctp.SctpChannel.open();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @Test
    public void neta2Jvm() throws Throwable {
        if (!checkSupport()) {
            return;
        }

        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);

        // Native Java SCTP Server (Read)
        AtomicBoolean sctpRead = new AtomicBoolean(false);
        ThreadUtils.daemonThread(true, (Runnable) () -> {
            try {
                SctpServerChannel serverChannel = SctpServerChannel.open();
                serverChannel.bind(address);

                SctpChannel clientChannel = serverChannel.accept();
                ByteBuffer buffer = ByteBuffer.allocate(4096);

                // wait for message
                clientChannel.receive(buffer, null, null);

                buffer.flip();
                byte[] byteArray = new byte[buffer.remaining()];
                buffer.get(byteArray);

                String rcv = new String(byteArray);
                // System.out.println("JVM Server Received: " + rcv);
                if (StringUtils.equals(rcv, "Hello SCTP")) {
                    sctpRead.set(true);
                }

                clientChannel.close();
                serverChannel.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        ThreadUtils.sleep(500); // wait server bind

        // Neta Client
        SctpSoConfig sctpConfig = new SctpSoConfig();
        NetManager neta = new NetManager(new NetConfig());
        ProtoInitializer initializer = ProtoHelper.standard().nextEncoder((ProtoHandler<String, ByteBuf>) (context, src, dst) -> {
            String data = src.takeMessage();
            dst.offerMessage(ByteBuf.wrap(data.getBytes()));
            return ProtoStatus.Next;
        }).build();

        Future<NetChannel> future = neta.connectAsync(address, initializer, sctpConfig);
        NetChannel channel = future.get();
        channel.sendData("Hello SCTP");

        int i = 100 * 50; // max 5 sec
        while (!sctpRead.get()) {
            ThreadUtils.sleep(100);
            i--;
            if (i <= 0) {
                break;
            }
        }
        assert sctpRead.get();
        neta.shutdown();
    }
}
