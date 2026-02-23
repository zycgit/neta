/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package net.hasor.tconsole.client;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.bytebuf.ByteBufAllocator;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.NetManager;
import net.hasor.neta.channel.PlayLoad;
import net.hasor.neta.channel.SoConfig;
import net.hasor.neta.codec.LineBasedFrameHandler;
import net.hasor.neta.codec.string.StringDuplexer;
import net.hasor.tconsole.TelAttribute;
import net.hasor.tconsole.TelOptions;
import net.hasor.tconsole.launcher.AttributeObject;
import net.hasor.tconsole.launcher.TelUtils;
import static net.hasor.tconsole.TelOptions.ENDCODE_OF_SILENT;
import static net.hasor.tconsole.TelOptions.SILENT;

/**
 * 可以连接远端 tConsole 并执行指令。
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2019年10月30日
 */
public class TelClient implements TelAttribute, AutoCloseable {
    private static final Logger          logger          = LoggerFactory.getLogger(TelClient.class);
    private static final long            DEFAULT_TIMEOUT = 15_000;
    private final        AttributeObject attributeObject;
    private              NetManager      netManager;
    private              NetChannel      channel;
    private final        ByteBuf         receiveDataBuffer;

    public TelClient() {
        this.receiveDataBuffer = ByteBufAllocator.DEFAULT.heapBuffer();
        this.attributeObject = new AttributeObject();
        String endcodeOfSilent = "----" + UUID.randomUUID().toString().replace("-", "") + "----";
        this.attributeObject.setAttribute(SILENT, true);                       // 静默输出
        this.attributeObject.setAttribute(ENDCODE_OF_SILENT, endcodeOfSilent); // 结束符
    }

    /**
     * 远程地址
     */
    public SocketAddress remoteAddress() {
        return this.channel == null ? null : this.channel.getRemoteAddr();
    }

    public boolean isConnected() {
        return this.channel != null && !this.channel.isClose();
    }

    public void connectTo(SocketAddress remote) {
        this.connectTo(remote, DEFAULT_TIMEOUT);
    }

    public void connectTo(SocketAddress remote, long timeout) {
        this.netManager = new NetManager();

        // 1. 注册事件
        TelClientHandler handler = new TelClientHandler(this, this::close, receiveDataBuffer);
        this.netManager.subscribe(PlayLoad::isInbound, handler);

        // 2. 连接到服务端
        Future<NetChannel> connected = this.netManager.connectAsync(remote, ctx -> {
            //split according to \r\n, max line is 4K
            ctx.addLastDecoder("max length", new LineBasedFrameHandler(4096, true));
            // encoder/decoder string
            ctx.addLast("string", new StringDuplexer());
        }, SoConfig.TCP());

        // 3. 等待连接成功
        try {
            this.channel = connected.get(timeout, TimeUnit.MILLISECONDS);
            logger.info("tConsole -> TelClient connect to " + remote);

            // 设置属性
            StringBuilder sendBuffer = new StringBuilder();
            sendBuffer.append(String.format("set %s=%s\n", TelOptions.SILENT, true));
            for (String key : this.getAttributeNames()) {
                Object attr = this.getAttribute(key);
                if (attr == null) {
                    attr = "";
                }
                if (TelOptions.SILENT.equalsIgnoreCase(key) || TelOptions.ENDCODE_OF_SILENT.equalsIgnoreCase(key)) {
                    continue;
                }
                sendBuffer.append(String.format("set %s=%s\n", key, attr));
            }
            sendBuffer.append(String.format("set %s=%s\n", TelOptions.ENDCODE_OF_SILENT, this.getAttribute(ENDCODE_OF_SILENT)));
            this.channel.sendData(sendBuffer.toString());

            handler.waitActive(timeout);
            logger.info("tConsole -> TelClient initialize ok.");
        } catch (TimeoutException e) {
            logger.error("tConsole -> TelClient connect timeout (" + timeout + "ms) -> " + remote, e);
            this.close();
        } catch (Throwable e) {
            if (e instanceof ExecutionException) {
                e = e.getCause();
            }
            logger.error("tConsole -> TelClient initialize failed -> " + e.getMessage(), e);
            this.close();
        }
    }

    /**
     * 发送命令
     */
    public String sendCommand(String message) {
        return this.sendCommand(message, DEFAULT_TIMEOUT);
    }

    public String sendCommand(String message, long timeout) {
        if (!this.isConnected()) {
            throw new IllegalStateException("the TelClient has been closed or not init.");
        }

        String endcodeOfSilent = TelUtils.aString(this, ENDCODE_OF_SILENT);
        this.channel.sendData(message.trim() + "\n");
        long deadline = System.currentTimeMillis() + timeout;
        while (this.isConnected()) {
            this.receiveDataBuffer.resetReader();
            int readLength = TelUtils.waitString(this.receiveDataBuffer, endcodeOfSilent);
            if (readLength > -1) {
                String dat = this.receiveDataBuffer.getString(0, readLength, StandardCharsets.UTF_8);
                this.receiveDataBuffer.clear();// 完全释放
                this.receiveDataBuffer.markReader();
                return StringUtils.trimBlankEnd(dat);
            }
            if (System.currentTimeMillis() > deadline) {
                this.close();
                throw new IllegalStateException("sendCommand timeout (" + timeout + "ms), server did not respond to: " + message.trim());
            }
            try {
                Thread.sleep(10);
            } catch (Exception e) { /**/ }
        }

        this.receiveDataBuffer.resetReader();
        return new String(this.receiveDataBuffer.asByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (this.channel != null) {
            try {
                this.channel.close().get();
            } catch (Exception e) { /**/ }
        }
        if (this.netManager != null) {
            try {
                this.netManager.shutdown();
            } catch (IOException e) {
                logger.error("tConsole -> TelClient close failed -> " + e.getMessage(), e);
            }
        }
    }

    @Override
    public Object getAttribute(String key) {
        return this.attributeObject.getAttribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        if (SILENT.equalsIgnoreCase(key)) {
            throw new UnsupportedOperationException("the client does not support set SILENT attribute.");
        }
        if (ENDCODE_OF_SILENT.equalsIgnoreCase(key)) {
            throw new UnsupportedOperationException("the client does not support set ENDCODE_OF_SILENT attribute.");
        }
        this.attributeObject.setAttribute(key, value);
    }

    @Override
    public Set<String> getAttributeNames() {
        return this.attributeObject.getAttributeNames();
    }
}