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
import net.hasor.neta.channel.tcp.TcpSoConfig;
import net.hasor.neta.channel.udp.UdpSoConfig;

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