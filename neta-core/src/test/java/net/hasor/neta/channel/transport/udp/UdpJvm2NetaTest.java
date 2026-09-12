/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.channel.transport.udp;

import static net.hasor.neta.channel.AbstractSoTest.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
public class UdpJvm2NetaTest {
    @Test
    public void jvm2Neta() throws Throwable {
        int safePort = safePort();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", safePort);
        NetManager neta = new NetManager(globalConf());

        AtomicBoolean udpRead = new AtomicBoolean(false);
        ProtoInitializer initializer = ProtoHelper.standard().nextDecoder((ProtoHandler<ByteBuf, String>) (context, src, dst) -> {
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

        UdpSoConfig udpConf = udpConfig(128, 4096);
        udpConf.setSoReadTimeoutMs(-1);
        neta.bind(address, initializer, udpConf);

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
