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
package net.hasor.tconsole.launcher.hosts;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.tconsole.TelAttribute;
import net.hasor.tconsole.TelOptions;
import net.hasor.tconsole.launcher.AbstractTelService;
import net.hasor.tconsole.launcher.TelSessionObject;
import net.hasor.tconsole.launcher.TelUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.hasor.tconsole.launcher.TelUtils.aBoolean;

/**
 * 提供一个可以基于本地模式使用的 Tel 命令工具。
 * HostTelService 无需监听任何 Socket 端口， Tel 的命令交互是通过 Reader、Writer 来实现的。
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2019年10月23日
 */
public class HostTelService extends AbstractTelService implements TelOptions, TelAttribute, AutoCloseable {
    protected static Logger           logger     = LoggerFactory.getLogger(HostTelService.class);
    private          TelSessionObject telSession = null;
    private final    AtomicBoolean    signal     = new AtomicBoolean(false);
    private final    AtomicBoolean    runnable   = new AtomicBoolean(false);

    @Override
    public Object getAttribute(String key) {
        return this.telSession.getAttribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        this.telSession.setAttribute(key, value);
    }

    @Override
    public Set<String> getAttributeNames() {
        return this.telSession.getAttributeNames();
    }

    public Future<HostTelService> startAt(boolean async, Reader input, Writer output) {
        if (this.signal.compareAndSet(false, true)) {
            ByteBuf dstCache = ByteBufAllocator.DEFAULT.buffer();
            this.telSession = new TelSessionObject(this, dstCache, output) {
                public boolean isClose() {
                    return false;
                }
            };

            BasicFuture<HostTelService> result = new BasicFuture<>();
            if (async) {
                this.printWelcome();
                Thread ioCopyThread = new Thread(() -> this.doIoCopy(input, dstCache, result));
                ioCopyThread.setDaemon(true);
                ioCopyThread.setName("tConsole-IoCopy-Thread");
                ioCopyThread.start();
            } else {
                this.printWelcome();
                this.doIoCopy(input, dstCache, result);
            }
            return result;
        } else {
            BasicFuture<HostTelService> result = new BasicFuture<>();
            result.failed(new IllegalStateException("tConsole -> has start"));
            return result;
        }
    }

    private void printWelcome() {
        if (TelUtils.aBoolean(this.telSession, SILENT)) {
            logger.info("tConsole -> silent, ignore Welcome info.");
            return;
        }
        logger.info("tConsole -> send Welcome info.");
        // Send greeting for a new connection.
        this.telSession.writeMessage("--------------------------------------------\r\n\r\n");
        this.telSession.writeMessage("Welcome to tConsole!\r\n");
        this.telSession.writeMessage("\r\n");
        this.telSession.writeMessage("     login : " + new Date() + " now. form Session\r\n");
        this.telSession.writeMessage("Tips: You can enter a 'help' or 'help -a' for more information.\r\n");
        this.telSession.writeMessage("use the 'exit' or 'quit' out of the console.\r\n");
        this.telSession.writeMessage("--------------------------------------------\r\n");
        this.telSession.writeMessage(TelUtils.CMD);
    }

    private void doIoCopy(Reader src, ByteBuf dst, BasicFuture<HostTelService> result) {
        BufferedReader bufferedSrc = new BufferedReader(src);
        this.runnable.set(true);
        while (this.signal.get()) {
            if (aBoolean(this, CLOSE_SESSION)) {
                this.runnable.set(false);
                result.completed(this);
                return;
            }

            try {
                if (bufferedSrc.ready()) {
                    dst.writeString(bufferedSrc.readLine() + "\n", StandardCharsets.UTF_8);
                    dst.flush();
                    this.doWork(dst);
                } else {
                    ThreadUtils.sleep(10);
                }
            } catch (Throwable e) {
                logger.error(e.getMessage(), e);
                this.runnable.set(false);
                result.failed(e);
                return;
            }
        }

        this.runnable.set(false);
        result.completed(this);
    }

    private void doWork(ByteBuf dst) throws IOException {
        int lastBufferSize = dst.readableBytes();
        while (this.telSession.tryReceiveEvent()) {
            if (lastBufferSize == dst.readableBytes()) {
                break;
            }
            lastBufferSize = dst.readableBytes();
        }
    }

    @Override
    public void close() {
        if (this.signal.compareAndSet(true, false)) {
            logger.info("tConsole -> wait HostTelService exit.");

            long t1 = System.currentTimeMillis();
            long t2 = System.currentTimeMillis();
            while (this.runnable.get()) {
                try {
                    Thread.sleep(50);

                    if (t2 + 1000 < System.currentTimeMillis()) {
                        logger.info("tConsole -> waiting exit..");
                        t2 = System.currentTimeMillis();
                    }

                    if (t1 + 10000 < System.currentTimeMillis()) {
                        logger.warn("tConsole -> wait HostTelService exit timeout.");
                        return;
                    }
                } catch (Exception e) { /**/ }
            }
            logger.info("tConsole -> HostTelService exit.");
        }
    }
}