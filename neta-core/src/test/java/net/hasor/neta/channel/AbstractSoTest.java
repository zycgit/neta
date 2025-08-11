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
package net.hasor.neta.channel;
import net.hasor.cobble.RandomUtils;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.function.Callable;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufUtils;
import net.hasor.neta.channel.tcp.TcpSoConfig;
import net.hasor.neta.channel.udp.UdpSoConfig;
import net.hasor.neta.handler.ProtoHandler;
import net.hasor.neta.handler.ProtoRcvQueue;
import net.hasor.neta.handler.ProtoSndQueue;
import net.hasor.neta.handler.ProtoStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
                return i;
            } catch (Exception e) {
                continue;
            }
        }
        throw new SocketException("No ports are available");
    }

    public static NetConfig globalConf() {
        NetConfig config = new NetConfig();
        config.setNetlog(true);
        config.setBufAllocator(ByteBufUtils.DEFAULT_ALLOCATOR);
        config.setThreadFactory((loader, nameTemplate) -> ThreadUtils.threadFactory(loader, nameTemplate, true));
        config.setIoThreads(2);
        config.setTaskThreads(2);
        return config;
    }

    public static TcpSoConfig tcpConfig(int sndSize, int rcvSize) {
        TcpSoConfig tcpConf = SoConfig.TCP();
        tcpConf.setSoRcvBuf(rcvSize);
        tcpConf.setSoSndBuf(sndSize);
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

    public static ProtoHandler counter(AtomicInteger counter) {
        return new ProtoHandler() {
            @Override
            public void onActive(ProtoContext context) throws Throwable {
                counter.incrementAndGet();
            }

            @Override
            public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue src, ProtoSndQueue dst) throws Throwable {
                return ProtoStatus.Next;
            }

            @Override
            public void onClose(ProtoContext context) {
                counter.decrementAndGet();
            }
        };
    }

    public static String toMd5(MessageDigest digest) {
        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
        }
        return sb.toString();
    }

    public static Thread blackHole(Socket socket, AtomicBoolean signal) {
        return ThreadUtils.daemonThread(true, (Callable) () -> {
            try {
                byte[] bytes = new byte[4096];
                InputStream in = socket.getInputStream();
                while (!socket.isClosed()) {
                    int len = Math.min(bytes.length, in.available());
                    int read = in.read(bytes, 0, len);
                    signal.compareAndSet(false, read > 0);
                    Thread.sleep(50);
                }
            } catch (Exception ignored) {
            }
        });
    }

    public static Thread whiteHole(Socket socket) {
        return ThreadUtils.daemonThread(true, (Callable) () -> {
            try {
                OutputStream out = socket.getOutputStream();
                while (!socket.isClosed()) {
                    out.write(RandomUtils.nextBytes(32));
                    Thread.sleep(50);
                }
            } catch (Exception ignored) {
            }
        });
    }

    public static Thread whiteHole(NetChannel channel) {
        return ThreadUtils.daemonThread(true, (Callable) () -> {
            while (!channel.isClose() && !channel.isShutdownOutput()) {
                channel.sendData(ByteBuf.wrap(RandomUtils.nextBytes(32)));
                Thread.sleep(50);
            }
        });
    }

}