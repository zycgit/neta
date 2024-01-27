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
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.bytebuf.ByteBufUtil;
import net.hasor.neta.handler.PipeHandler;
import net.hasor.neta.handler.PipeRcvQueue;
import net.hasor.neta.handler.PipeSndQueue;
import net.hasor.neta.handler.PipeStatus;

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

    public static SoConfig crateConfig(int swapSize, int bufSize) {
        SoConfig config = new SoConfig();
        config.setNetlog(true);
        config.setSwapBuf(swapSize, swapSize);
        config.setLocalBuf(bufSize, bufSize);

        config.setSoReadTimeoutMs(1000);
        config.setSoWriteTimeoutMs(1000);
        config.setSoKeepAlive(true);
        config.setSoKeepIntervalSec(10);
        config.setSoKeepIdleSec(10);
        config.setBufAllocator(ByteBufUtil.DEFAULT_HEAP_ALLOCATOR);
        //
        config.setThreadFactory((loader, nameTemplate) -> ThreadUtils.threadFactory(loader, nameTemplate, true));
        config.setIoThreads(2);
        config.setTaskThreads(2);
        return config;
    }

    public static PipeHandler counter(AtomicInteger counter) {
        return new PipeHandler() {
            @Override
            public void onActive(PipeContext context) throws Throwable {
                counter.incrementAndGet();
            }

            @Override
            public PipeStatus onMessage(PipeContext context, PipeRcvQueue src, PipeSndQueue dst) throws Throwable {
                return PipeStatus.Next;
            }

            @Override
            public void onClose(PipeContext context) {
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
                channel.sendData(ByteBufAllocator.DEFAULT.wrap(RandomUtils.nextBytes(32)));
                Thread.sleep(50);
            }
        });
    }

}