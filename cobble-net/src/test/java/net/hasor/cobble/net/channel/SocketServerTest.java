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
package net.hasor.cobble.net.channel;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.net.bytebuf.ByteBufUtil;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SocketServerTest {
    public static void main(String[] args) throws Exception {
        SoConfig config = new SoConfig();
        config.setSwapBuf(2, 2);
        config.setLocalBuf(32, 32);
        //        config.setSoReadTimeoutMs(6000);
        //        config.setSoKeepAlive(true);
        //        config.setSoKeepIntervalSec(10);
        //        config.setSoKeepIdleSec(10);
        config.setBufAllocator(ByteBufUtil.DEFAULT_HEAP_ALLOCATOR);
        //
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        ThreadFactory tf1 = ThreadUtils.threadFactory(loader, "IO-Thread-%s", true);
        ThreadFactory tf2 = ThreadUtils.threadFactory(loader, "WORK-Thread-%s", true);
        config.setIoExecutor(Executors.newFixedThreadPool(1, tf1));
        config.setTaskExecutorFactory((cfg, ctxName) -> Executors.newFixedThreadPool(1, tf2));

        try (CobbleSocket socket = new CobbleSocket(config)) {
            NetListen listen = socket.listen("127.0.0.1", 5567, null);
            read(listen);
        }
    }

    private static void read(NetListen netListen) {
        try {
            System.out.println("10s after suspend.");
            ThreadUtils.sleep(10000);
            netListen.suspend();

            System.out.println("5s after resume.");
            ThreadUtils.sleep(5000);
            netListen.resume();

            System.out.println("resume.");
            System.in.read();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

