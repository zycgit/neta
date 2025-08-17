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
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.handler.ProtoHandler;
import net.hasor.neta.handler.ProtoHelper;
import net.hasor.neta.handler.ProtoStatus;
import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.hasor.neta.channel.AbstractSoTest.*;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class UdpJvm2NetaTest {
    @Test
    public void jvm2Neta() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        NetManager neta = new NetManager(globalConf());

        AtomicBoolean udpRead = new AtomicBoolean(false);
        ProtoInitializer initializer = ctx -> {
            return ProtoHelper.builder().nextDecoder((ProtoHandler<ByteBuf, String>) (context, src, dst) -> {
                while (src.hasMore()) {
                    ByteBuf data = src.takeMessage();
                    int len = data.readableBytes();
                    byte[] bytes = new byte[len];
                    data.readBytes(bytes);
                    udpRead.set(StringUtils.equals(new String(bytes), "Hello UDP"));
                    data.markReader();
                }
                return ProtoStatus.Next;
            }).build();
        };

        UdpSoConfig udpConf = udpConfig(128, 4096);
        udpConf.setSoReadTimeoutMs(-1);
        neta.listen(address, initializer, udpConf);

        //
        DatagramSocket socket = new DatagramSocket();
        byte[] sendData = "Hello UDP".getBytes(StandardCharsets.UTF_8);
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address);
        socket.send(sendPacket);
        socket.close();

        int i = 100 * 50; // max 5sce
        while (!udpRead.get()) {
            ThreadUtils.sleep(100);
            i--;
            if (i <= 0) {
                break;
            }
        }
        assert udpRead.get();
        neta.shutdown();
    }
}