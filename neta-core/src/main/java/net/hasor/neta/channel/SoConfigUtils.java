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
import net.hasor.cobble.logging.Logger;

import java.io.IOException;
import java.nio.channels.NetworkChannel;

/**
 * config Socket
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2023-09-24
 */
public class SoConfigUtils {
    private static final Logger logger = Logger.getLogger(NetManager.class);

    public static void configListen(SoConfig config, NetworkChannel channel) throws IOException {
        Integer soRcvBuf = config.getSoRcvBuf();
        Integer soSndBuf = config.getSoSndBuf();
        if (soRcvBuf != null) {
            try {
                channel.setOption(SoOptions.SO_RCVBUF, soRcvBuf);
            } catch (UnsupportedOperationException e) {
                logger.warn("the platform does not support SO_RCVBUF");
            }
        }
        if (soSndBuf != null) {
            try {
                channel.setOption(SoOptions.SO_SNDBUF, soSndBuf);
            } catch (UnsupportedOperationException e) {
                logger.warn("the platform does not support SO_SNDBUF");
            }
        }
        channel.setOption(SoOptions.SO_REUSEADDR, true);
    }

    public static void configSocket(SoConfig config, NetworkChannel channel) throws IOException {
        Integer soRcvBuf = config.getSoRcvBuf();
        Integer soSndBuf = config.getSoSndBuf();
        if (soRcvBuf != null) {
            try {
                channel.setOption(SoOptions.SO_RCVBUF, soRcvBuf);
            } catch (UnsupportedOperationException e) {
                logger.warn("the platform does not support SO_RCVBUF");
            }
        }
        if (soSndBuf != null) {
            try {
                channel.setOption(SoOptions.SO_SNDBUF, soSndBuf);
            } catch (UnsupportedOperationException e) {
                logger.warn("the platform does not support SO_SNDBUF");
            }
        }

        if (Boolean.TRUE.equals(config.getSoKeepAlive())) {
            channel.setOption(SoOptions.SO_KEEPALIVE, true);
            if (config.getSoKeepIdleSec() != null && SoOptions.TCP_KEEPIDLE != null) {
                channel.setOption(SoOptions.TCP_KEEPIDLE, config.getSoKeepIdleSec());
            }
            if (config.getSoKeepIntervalSec() != null && SoOptions.TCP_KEEPINTERVAL != null) {
                channel.setOption(SoOptions.TCP_KEEPINTERVAL, config.getSoKeepIntervalSec());
            }
            if (config.getSoKeepCount() != null && SoOptions.TCP_KEEPCOUNT != null) {
                channel.setOption(SoOptions.TCP_KEEPCOUNT, config.getSoKeepCount());
            }
        }
    }
}