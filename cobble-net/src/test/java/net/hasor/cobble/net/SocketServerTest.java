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
import net.hasor.cobble.concurrent.ThreadUtils;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2022-11-01
 */
public class SocketServerTest {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        ThreadFactory tf1 = ThreadUtils.threadFactory(loader, "IO-Thread-%s", true);
        ThreadFactory tf2 = ThreadUtils.threadFactory(loader, "BB-Thread-%s", true);

        // 监听处理线程
        SocketConfig config = new SocketConfig();
        config.setSwapBufSize(2);
        config.setRcvBufSize(128);
        config.setIoExecutor(Executors.newFixedThreadPool(1, tf1));
        config.setWorkerExecutor(Executors.newFixedThreadPool(1, tf2));

        SocketServer server = new SocketServer(config);
        try (AutoCloseable close = server.listen(new InetSocketAddress(5567))) {
            System.in.read();
        }
    }
}

