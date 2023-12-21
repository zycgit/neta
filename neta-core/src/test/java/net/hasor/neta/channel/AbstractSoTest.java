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
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.bytebuf.ByteBufUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class AbstractSoTest {
    public static int safePort() throws IOException {
        for (int i = 24608; i < 65525; i++) {
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

    public static NetListener counter(AtomicInteger counter) {
        return new NetListener() {
            @Override
            public void accept(NetChannel channel) {
                counter.incrementAndGet();
            }

            @Override
            public void close(NetChannel channel) {
                counter.decrementAndGet();
            }
        };
    }
}