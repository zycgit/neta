/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.neta.codec;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.NetConfig;
import net.hasor.neta.channel.SoConfig;
import net.hasor.neta.channel.transport.tcp.TcpSoConfig;
import net.hasor.neta.channel.transport.udp.UdpSoConfig;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class AbstractSoTest {
    public static int safePort() throws IOException {
        for (int i = 24601; i < 65525; i++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.bind(new InetSocketAddress("127.0.0.1", i));
                ss.close();
                DatagramSocket ds = new DatagramSocket(null);
                ds.bind(new InetSocketAddress("127.0.0.1", i));
                ds.close();
                return i;
            } catch (Exception e) {
                continue;
            }
        }
        throw new SocketException("No ports are available");
    }

    public static NetConfig globalConf() {
        NetConfig config = new NetConfig();
        config.setPrintLog(true);
        config.setBufAllocator(ByteBufUtils.DEFAULT_ALLOCATOR);
        config.setThreadFactory((loader, nameTemplate) -> ThreadUtils.threadFactory(loader, nameTemplate, true));
        config.setIoThreads(2);
        config.setTaskThreads(2);
        return config;
    }

    public static TcpSoConfig tcpConfig(int sndSize, int rcvSize) {
        TcpSoConfig tcpConf = SoConfig.TCP();
        tcpConf.setSwapRcvBuf(rcvSize);
        tcpConf.setSwapSndBuf(sndSize);
        tcpConf.setSoReadTimeoutMs(1000);
        tcpConf.setSoWriteTimeoutMs(1000);
        tcpConf.setSoKeepAlive(true);
        tcpConf.setSoKeepIntervalSec(10);
        tcpConf.setSoKeepIdleSec(10);
        return tcpConf;
    }

    public static UdpSoConfig udpConfig(int sndSize, int rcvSize) {
        UdpSoConfig udpConf = SoConfig.UDP();
        udpConf.setSoRcvBuf(rcvSize);
        udpConf.setSoSndBuf(sndSize);
        udpConf.setSoReadTimeoutMs(1000);
        udpConf.setSoWriteTimeoutMs(1000);
        return udpConf;
    }
}
