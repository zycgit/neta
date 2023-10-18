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
import java.io.IOException;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;

/**
 * config Socket
 * @version : 2023-09-24
 * @author 赵永春 (zyc@hasor.net)
 */
class SoConfigUtils {
    public static void configListen(SoConfig config, AsynchronousServerSocketChannel channel) throws IOException {
        Integer soRcvBuf = config.getSoRcvBuf();
        Integer soSndBuf = config.getSoSndBuf();
        if (soRcvBuf != null) {
            channel.setOption(SoOptions.SO_RCVBUF, soRcvBuf);
        }
        if (soSndBuf != null) {
            channel.setOption(SoOptions.SO_SNDBUF, soSndBuf);
        }
        channel.setOption(SoOptions.SO_REUSEADDR, true);
    }

    public static void configSocket(SoConfig config, AsynchronousSocketChannel channel) throws IOException {
        Integer soRcvBuf = config.getSoRcvBuf();
        Integer soSndBuf = config.getSoSndBuf();
        if (soRcvBuf != null) {
            channel.setOption(SoOptions.SO_RCVBUF, soRcvBuf);
        }
        if (soSndBuf != null) {
            channel.setOption(SoOptions.SO_SNDBUF, soSndBuf);
        }

        if (Boolean.TRUE.equals(config.getSoKeepAlive())) {
            channel.setOption(SoOptions.SO_KEEPALIVE, true);
            if (config.getSoKeepIdleSec() != null) {
                channel.setOption(SoOptions.TCP_KEEPIDLE, config.getSoKeepIdleSec());
            }
            if (config.getSoKeepIntervalSec() != null) {
                channel.setOption(SoOptions.TCP_KEEPINTERVAL, config.getSoKeepIntervalSec());
            }
            if (config.getSoKeepCount() != null) {
                channel.setOption(SoOptions.TCP_KEEPCOUNT, config.getSoKeepCount());
            }
        }
    }
}