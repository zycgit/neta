/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.tconsole.launcher.telnet;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.neta.channel.NetConfig;
import net.hasor.neta.channel.NetListen;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.SoConfig;
import net.hasor.tconsole.TelConfig;
import net.hasor.tconsole.launcher.AbstractTelService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Predicate;

/**
 * tConsole 服务，提供 Telnet 形式的交互界面。
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2016年09月20日
 */
public class SocketTelService extends AbstractTelService implements AutoCloseable {
    private       NetManager netManager;
    private       NetListen  netListen = null;
    private final NetConfig  netConfig;
    private final TelConfig  telConfig;

    public SocketTelService() {
        this(new NetConfig(), new TelConfig());
    }

    public SocketTelService(NetConfig netConfig, TelConfig telConfig) {
        this.netConfig = netConfig;
        this.telConfig = telConfig;
    }

    public void start(int listenPort) throws IOException {
        this.start(new InetSocketAddress("0.0.0.0", listenPort), s -> true);
    }

    public void start(String listenAddr, int listenPort) throws IOException {
        this.start(new InetSocketAddress(listenAddr, listenPort), s -> true);
    }

    public void start(InetSocketAddress listen, Predicate<String> inBoundMatcher) throws IOException {
        if (this.netManager != null) {
            throw new IllegalStateException("tConsole -> has start");
        }

        // config
        this.silent(telConfig.isSilent());

        // work
        String shortName = "tConsole-Work";
        int workSize = Math.max(2, telConfig.getWorkSize());
        ExecutorService workerExecutor;
        if (telConfig.getThreadFactory() == null) {
            workerExecutor = Executors.newScheduledThreadPool(workSize, ThreadUtils.threadFactory(SocketTelService.class.getClassLoader(), shortName, true));
        } else {
            workerExecutor = Executors.newScheduledThreadPool(workSize, telConfig.getThreadFactory());
        }
        ThreadPoolExecutor threadPool = (ThreadPoolExecutor) workerExecutor;
        threadPool.setCorePoolSize(workSize);
        threadPool.setMaximumPoolSize(workSize);
        logger.info("tConsole -> create TelnetHandler , threadShortName=" + shortName + " , workThreadSize = " + workSize);

        // network
        try {
            this.netManager = new NetManager(netConfig);
            logger.info("tConsole -> starting... at " + listen);
            this.netListen = this.netManager.bind(listen, ctx -> {
                Predicate<String> matcher = inBoundMatcher == null ? (s -> true) : inBoundMatcher;
                ctx.addLastDecoder(new SocketTelHandler(this, workerExecutor, matcher));
            }, SoConfig.TCP());
            logger.info("tConsole -> bindSocket at " + listen);
        } catch (Throwable e) {
            logger.error("tConsole -> start failed, " + e.getMessage(), e);
            this.netManager.shutdown();
        }
    }

    @Override
    public void close() {
        if (this.netListen != null) {
            logger.info("tConsole -> netListen.close");
            this.netListen.suspend();
            this.netListen.close();
            this.netListen = null;
        }
        if (this.netManager != null) {
            try {
                this.netManager.shutdown();
                this.netManager = null;
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }
}