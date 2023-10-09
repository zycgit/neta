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
package net.hasor.cobble.net;
import net.hasor.cobble.bytebuf.ByteBufUtil;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Future;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SocketClientTest {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        ThreadFactory tf1 = ThreadUtils.threadFactory(loader, "IO-Thread-%s", true);
        ThreadFactory tf2 = ThreadUtils.threadFactory(loader, "WORK-Thread-%s", true);

        // 监听处理线程
        SoConfig config = new SoConfig();
        config.setSwapBuf(2, 2);
        config.setLocalBuf(128, 128);
        //        config.setSoReadTimeoutMs(6000);
        config.setSoKeepAlive(true);
        config.setSoKeepIntervalSec(2);
        config.setSoKeepIdleSec(2);
        config.setBufAllocator(ByteBufUtil.DEFAULT_HEAP_ALLOCATOR);
        //
        config.setIoExecutor(Executors.newFixedThreadPool(1, tf1));
        config.setTaskExecutorFactory((cfg, ctxName) -> Executors.newFixedThreadPool(1, tf2));

        AtomicBoolean exit = new AtomicBoolean(false);
        try (CobbleSocket client = new CobbleSocket(config)) {
            Future<NetChannel> connect = client.connect(new InetSocketAddress("127.0.0.1", 5567));
            connect.onCompleted(f -> {
                ThreadUtils.runFrontThread(() -> clientWorking(f.getResult(), exit));
            }).onFailed(f -> {
                System.out.println(f.getCause().getMessage());
            }).onFinal(future -> {
                System.out.println("after connect.");
            });

            while (!exit.get()) {
                ThreadUtils.sleep(100);
            }
            System.out.println("exit");
        }
    }

    private static void clientWorking(NetChannel client, AtomicBoolean exit) {
        client.sendData(("say Hello 1\r\n").getBytes());
        int i = 0;
        while (true) {
            ThreadUtils.sleep(1000);
            //

            if (i > 10) {
                exit.set(true);
            }
        }
    }
}

